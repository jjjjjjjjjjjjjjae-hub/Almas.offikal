package kz.almasofficial.br;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.util.Base64;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RtcManager {
    public interface Events {
        void sendSignal(String targetId, String payload);
        void onState(String state);
        void onRemoteVideo(boolean visible);
        void onDataMessage(String message);
        void onError(String error);
    }

    private final Context context;
    private final SurfaceViewRenderer remoteRenderer;
    private final Events events;
    private final EglBase eglBase;
    private final PeerConnectionFactory factory;

    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private DataChannel dataChannel;
    private VideoSource screenSource;
    private VideoTrack screenTrack;
    private ScreenCapturerAndroid screenCapturer;
    private SurfaceTextureHelper screenHelper;
    private RtpSender screenSender;
    private VideoTrack remoteVideoTrack;

    private String myId = "";
    private String peerId = "";
    private boolean makingOffer = false;
    private boolean remoteDescriptionSet = false;
    private final List<IceCandidate> pendingIce = new ArrayList<>();

    public RtcManager(Context context, SurfaceViewRenderer remoteRenderer, Events events) {
        this.context = context.getApplicationContext();
        this.remoteRenderer = remoteRenderer;
        this.events = events;

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this.context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());

        eglBase = EglBase.create();
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.setEnableHardwareScaler(true);
        remoteRenderer.setMirror(false);

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    public synchronized void connect(String myId, String peerId, boolean initiator) {
        if (myId == null || peerId == null || myId.isEmpty() || peerId.isEmpty()) return;
        if (peerConnection != null && this.peerId.equals(peerId)) {
            if (initiator && peerConnection.getLocalDescription() == null) makeOffer();
            return;
        }

        resetPeer();
        this.myId = myId;
        this.peerId = peerId;
        createPeer(initiator);
        if (initiator) makeOffer();
    }

    private void createPeer(boolean initiator) {
        List<PeerConnection.IceServer> iceServers = Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        );
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}

            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                switch (state) {
                    case CONNECTED:
                    case COMPLETED:
                        events.onState("P2P connected");
                        break;
                    case CHECKING:
                        events.onState("P2P connecting…");
                        break;
                    case DISCONNECTED:
                        events.onState("P2P reconnecting…");
                        break;
                    case FAILED:
                        events.onState("P2P failed");
                        events.onError("Тікелей P2P байланыс орнамады");
                        break;
                    case CLOSED:
                        events.onState("P2P closed");
                        break;
                    default:
                        break;
                }
            }

            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

            @Override public void onIceCandidate(IceCandidate candidate) {
                String mid = candidate.sdpMid == null ? "" : candidate.sdpMid;
                String payload = "ICE|" + myId + "|"
                        + b64(mid) + "|" + candidate.sdpMLineIndex + "|" + b64(candidate.sdp);
                events.sendSignal(peerId, payload);
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}

            @Override public void onDataChannel(DataChannel dc) {
                attachDataChannel(dc);
            }

            @Override public void onRenegotiationNeeded() {}

            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                attachRemoteTrack(receiver == null ? null : receiver.track());
            }

            @Override public void onTrack(RtpTransceiver transceiver) {
                if (transceiver != null && transceiver.getReceiver() != null) {
                    attachRemoteTrack(transceiver.getReceiver().track());
                }
            }
        });

        if (peerConnection == null) {
            events.onError("PeerConnection жасалмады");
            return;
        }

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googAutoGainControl", "true"));
        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("almas-audio", audioSource);
        audioTrack.setEnabled(false);
        peerConnection.addTrack(audioTrack, Collections.singletonList("almas"));

        if (initiator) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            attachDataChannel(peerConnection.createDataChannel("almas-chat", init));
        }
    }

    private void attachRemoteTrack(MediaStreamTrack track) {
        if (!(track instanceof VideoTrack)) return;
        VideoTrack vt = (VideoTrack) track;
        if (remoteVideoTrack == vt) return;
        if (remoteVideoTrack != null) {
            try { remoteVideoTrack.removeSink(remoteRenderer); } catch (Exception ignored) {}
        }
        remoteVideoTrack = vt;
        remoteVideoTrack.setEnabled(true);
        remoteVideoTrack.addSink(remoteRenderer);
        events.onRemoteVideo(true);
    }

    private void attachDataChannel(DataChannel dc) {
        if (dc == null) return;
        if (dataChannel != null && dataChannel != dc) {
            try { dataChannel.unregisterObserver(); } catch (Exception ignored) {}
            try { dataChannel.dispose(); } catch (Exception ignored) {}
        }
        dataChannel = dc;
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {}

            @Override public void onStateChange() {
                if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
                    events.onState("P2P ready");
                }
            }

            @Override public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer bb = buffer.data;
                byte[] bytes = new byte[bb.remaining()];
                bb.get(bytes);
                events.onDataMessage(new String(bytes, StandardCharsets.UTF_8));
            }
        });
    }

    public synchronized void handleSignal(String payload) {
        if (payload == null || payload.isEmpty()) return;
        String[] p = payload.split("\\|", 5);
        if (p.length < 3) return;
        String type = p[0];
        String from = p[1];

        if (!peerId.isEmpty() && !peerId.equals(from)) return;
        if (peerConnection == null) {
            if (myId.isEmpty()) return;
            peerId = from;
            createPeer(false);
        }

        try {
            if ("OFFER".equals(type) && p.length >= 3) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.OFFER, unb64(p[2]));
                setRemoteDescription(sdp, this::makeAnswer);
            } else if ("ANSWER".equals(type) && p.length >= 3) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.ANSWER, unb64(p[2]));
                setRemoteDescription(sdp, null);
            } else if ("ICE".equals(type) && p.length >= 5) {
                String mid = unb64(p[2]);
                int line = Integer.parseInt(p[3]);
                String candidate = unb64(p[4]);
                IceCandidate ice = new IceCandidate(mid.isEmpty() ? null : mid, line, candidate);
                if (remoteDescriptionSet) {
                    peerConnection.addIceCandidate(ice);
                } else {
                    pendingIce.add(ice);
                }
            }
        } catch (Exception e) {
            events.onError("RTC signal қатесі: " + e.getClass().getSimpleName());
        }
    }

    private void setRemoteDescription(SessionDescription sdp, Runnable after) {
        if (peerConnection == null) return;
        peerConnection.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                flushPendingIce();
                if (after != null) after.run();
            }

            @Override public void onSetFailure(String error) {
                events.onError("Remote SDP: " + error);
            }
        }, sdp);
    }

    private void flushPendingIce() {
        if (peerConnection == null) return;
        for (IceCandidate ice : pendingIce) {
            try { peerConnection.addIceCandidate(ice); } catch (Exception ignored) {}
        }
        pendingIce.clear();
    }

    private synchronized void makeOffer() {
        if (peerConnection == null || makingOffer) return;
        PeerConnection.SignalingState state = peerConnection.signalingState();
        if (state != PeerConnection.SignalingState.STABLE) return;
        makingOffer = true;

        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        makingOffer = false;
                        events.sendSignal(peerId, "OFFER|" + myId + "|" + b64(sdp.description));
                    }

                    @Override public void onSetFailure(String error) {
                        makingOffer = false;
                        events.onError("Local offer: " + error);
                    }
                }, sdp);
            }

            @Override public void onCreateFailure(String error) {
                makingOffer = false;
                events.onError("Offer: " + error);
            }
        }, c);
    }

    private void makeAnswer() {
        if (peerConnection == null) return;
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createAnswer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        events.sendSignal(peerId, "ANSWER|" + myId + "|" + b64(sdp.description));
                    }

                    @Override public void onSetFailure(String error) {
                        events.onError("Local answer: " + error);
                    }
                }, sdp);
            }

            @Override public void onCreateFailure(String error) {
                events.onError("Answer: " + error);
            }
        }, c);
    }

    public synchronized void setMicEnabled(boolean enabled) {
        if (audioTrack != null) audioTrack.setEnabled(enabled);
    }

    public synchronized boolean sendChat(String message) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return false;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    public synchronized boolean startScreen(Intent permissionData, int width, int height, int fps) {
        if (permissionData == null || peerConnection == null) return false;
        stopScreenInternal(false);

        try {
            screenCapturer = new ScreenCapturerAndroid(permissionData,
                    new MediaProjection.Callback() {
                        @Override public void onStop() {
                            stopScreenInternal(false);
                            events.onRemoteVideo(false);
                        }
                    });
            screenSource = factory.createVideoSource(true);
            screenSource.adaptOutputFormat(width, height, fps);
            screenHelper = SurfaceTextureHelper.create(
                    "AlmasScreenCapture", eglBase.getEglBaseContext());
            screenCapturer.initialize(
                    screenHelper, context, screenSource.getCapturerObserver());
            screenCapturer.startCapture(width, height, fps);

            screenTrack = factory.createVideoTrack("almas-screen", screenSource);
            screenTrack.setEnabled(true);
            screenSender = peerConnection.addTrack(
                    screenTrack, Collections.singletonList("almas"));
            makeOffer();
            return true;
        } catch (Exception e) {
            events.onError("Экран WebRTC қатесі: " + e.getClass().getSimpleName());
            stopScreenInternal(false);
            return false;
        }
    }

    public synchronized void stopScreen() {
        stopScreenInternal(true);
    }

    private void stopScreenInternal(boolean renegotiate) {
        if (peerConnection != null && screenSender != null) {
            try { peerConnection.removeTrack(screenSender); } catch (Exception ignored) {}
        }
        screenSender = null;

        if (screenCapturer != null) {
            try { screenCapturer.stopCapture(); } catch (Exception ignored) {}
            try { screenCapturer.dispose(); } catch (Exception ignored) {}
        }
        screenCapturer = null;

        if (screenTrack != null) {
            try { screenTrack.setEnabled(false); } catch (Exception ignored) {}
            try { screenTrack.dispose(); } catch (Exception ignored) {}
        }
        screenTrack = null;

        if (screenSource != null) {
            try { screenSource.dispose(); } catch (Exception ignored) {}
        }
        screenSource = null;

        if (screenHelper != null) {
            try { screenHelper.dispose(); } catch (Exception ignored) {}
        }
        screenHelper = null;

        if (renegotiate && peerConnection != null) makeOffer();
    }

    public synchronized void resetPeer() {
        stopScreenInternal(false);
        remoteDescriptionSet = false;
        pendingIce.clear();
        makingOffer = false;

        if (remoteVideoTrack != null) {
            try { remoteVideoTrack.removeSink(remoteRenderer); } catch (Exception ignored) {}
        }
        remoteVideoTrack = null;
        events.onRemoteVideo(false);

        if (dataChannel != null) {
            try { dataChannel.unregisterObserver(); } catch (Exception ignored) {}
            try { dataChannel.close(); } catch (Exception ignored) {}
            try { dataChannel.dispose(); } catch (Exception ignored) {}
        }
        dataChannel = null;

        if (audioTrack != null) {
            try { audioTrack.setEnabled(false); } catch (Exception ignored) {}
            try { audioTrack.dispose(); } catch (Exception ignored) {}
        }
        audioTrack = null;

        if (audioSource != null) {
            try { audioSource.dispose(); } catch (Exception ignored) {}
        }
        audioSource = null;

        if (peerConnection != null) {
            try { peerConnection.close(); } catch (Exception ignored) {}
            try { peerConnection.dispose(); } catch (Exception ignored) {}
        }
        peerConnection = null;
        peerId = "";
    }

    public synchronized void destroy() {
        resetPeer();
        try { remoteRenderer.release(); } catch (Exception ignored) {}
        try { factory.dispose(); } catch (Exception ignored) {}
        try { eglBase.release(); } catch (Exception ignored) {}
    }

    private String b64(String s) {
        return Base64.encodeToString(
                s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private String unb64(String s) {
        return new String(
                Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
    }

    private abstract static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
