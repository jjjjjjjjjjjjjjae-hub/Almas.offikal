package kz.almasofficial.br;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.webrtc.SurfaceViewRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final String BROKER = "ssl://broker.emqx.io:8883";
    private static final String BASE = "almasbridge/v1/";
    private static final String INVITE = "https://jjjjjjjjjjjjjjae-hub.github.io/Almas.offikal/br-live/";
    private static final int REQ_CAPTURE = 7101;
    private static final int REQ_AUDIO = 7102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> profiles = new ConcurrentHashMap<>();
    private final Map<String, String> nameOwners = new ConcurrentHashMap<>();

    private SharedPreferences prefs;
    private MqttClient mqtt;
    private RtcManager rtc;

    private String myId = "";
    private String myNick = "";
    private String peerId = "";
    private String peerNick = "";
    private String pendingInviteId = "";
    private String pendingInviteNick = "";
    private String rtcState = "";

    private boolean mqttOnline = false;
    private boolean voiceOn = false;
    private boolean screenOn = false;

    private TextView connectionView, identityView, peerView, searchResult, chatText;
    private EditText searchId, messageInput;
    private SurfaceViewRenderer remoteScreen;
    private Button pairButton, voiceButton, screenButton;
    private ScrollView chatScroll;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("almas_bridge", MODE_PRIVATE);
        myId = prefs.getString("id", "");
        myNick = prefs.getString("nick", "");
        peerId = prefs.getString("peer_id", "");
        peerNick = prefs.getString("peer_nick", "");

        readInvite(getIntent());
        buildUi();
        initRtc();
        connectMqtt();
        askRuntimePermissions();
    }

    private void initRtc() {
        rtc = new RtcManager(this, remoteScreen, new RtcManager.Events() {
            @Override public void sendSignal(String targetId, String payload) {
                publish(BASE + "rtc/" + targetId, payload, false, 1);
            }

            @Override public void onState(String state) {
                rtcState = state;
                ui.post(MainActivity.this::refreshConnection);
            }

            @Override public void onRemoteVideo(boolean visible) {
                ui.post(() -> remoteScreen.setVisibility(visible ? View.VISIBLE : View.GONE));
            }

            @Override public void onDataMessage(String message) {
                ui.post(() -> appendChat((peerNick.isEmpty() ? "Дос" : peerNick) + ": " + message));
            }

            @Override public void onError(String error) {
                ui.post(() -> toast(error));
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readInvite(intent);
        if (!pendingInviteId.isEmpty()) {
            searchId.setText(pendingInviteId);
            searchResult.setText("Шақыру: " + pendingInviteNick + " • ID " + pendingInviteId);
            pairButton.setVisibility(View.VISIBLE);
        }
    }

    private void readInvite(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri u = intent.getData();
        String id = u.getQueryParameter("friendId");
        if (id == null) id = u.getQueryParameter("fromId");
        String nick = u.getQueryParameter("friendNick");
        if (nick == null) nick = u.getQueryParameter("fromNick");
        pendingInviteId = id == null ? "" : id.trim();
        pendingInviteNick = nick == null ? "" : nick.trim();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(18));
        root.setBackgroundColor(Color.rgb(9, 11, 15));

        TextView title = text("ALMAS OFFICAL", 27, Color.WHITE, true);
        title.setLetterSpacing(.06f);
        root.addView(title, lp(-1, dp(42)));

        TextView sub = text("2 PHONE BRIDGE • LOW LATENCY", 11,
                Color.rgb(138, 148, 168), true);
        root.addView(sub, lp(-1, dp(28)));

        connectionView = text("Серверге қосылуда…", 13,
                Color.rgb(255, 196, 76), false);
        root.addView(connectionView, lp(-1, dp(30)));

        identityView = text("Профиль дайындалуда…", 17, Color.WHITE, true);
        identityView.setPadding(dp(14), dp(12), dp(14), dp(12));
        identityView.setBackground(card());
        identityView.setOnClickListener(v -> copyId());
        root.addView(identityView, lp(-1, dp(64)));

        Button share = button("ID / шақыру сілтемесін жіберу");
        share.setOnClickListener(v -> shareInvite());
        root.addView(share, topLp(dp(48), 10));

        TextView findTitle = text("ID арқылы адам табу", 15, Color.WHITE, true);
        root.addView(findTitle, topLp(dp(34), 16));

        LinearLayout findRow = new LinearLayout(this);
        findRow.setOrientation(LinearLayout.HORIZONTAL);
        searchId = input("8 таңбалы ID");
        findRow.addView(searchId, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button find = button("Іздеу");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(98), dp(50));
        flp.leftMargin = dp(8);
        findRow.addView(find, flp);
        root.addView(findRow, lp(-1, dp(50)));
        find.setOnClickListener(v -> searchPerson());

        searchResult = text("", 14, Color.rgb(196, 202, 216), false);
        root.addView(searchResult, topLp(dp(44), 8));

        pairButton = button("Осы адаммен қосылу");
        pairButton.setVisibility(View.GONE);
        pairButton.setOnClickListener(v -> requestPair());
        root.addView(pairButton, lp(-1, dp(48)));

        peerView = text("Қазір ешкім қосылмаған", 16, Color.WHITE, true);
        peerView.setPadding(dp(14), dp(12), dp(14), dp(12));
        peerView.setBackground(card());
        root.addView(peerView, topLp(dp(60), 14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        voiceButton = button("🎙 Дауыс HQ");
        screenButton = button("▣ Экран 720p/20fps");
        actions.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(50), 1f));

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        slp.leftMargin = dp(8);
        actions.addView(screenButton, slp);
        root.addView(actions, topLp(dp(50), 10));

        voiceButton.setOnClickListener(v -> toggleVoice());
        screenButton.setOnClickListener(v -> toggleScreen());

        remoteScreen = new SurfaceViewRenderer(this);
        remoteScreen.setBackgroundColor(Color.BLACK);
        remoteScreen.setVisibility(View.GONE);
        root.addView(remoteScreen, topLp(dp(220), 10));

        chatText = text("", 14, Color.WHITE, false);
        chatText.setPadding(dp(12), dp(12), dp(12), dp(12));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(false);
        chatScroll.setBackground(card());
        chatScroll.addView(chatText);
        root.addView(chatScroll, topLp(dp(150), 10));

        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);

        messageInput = input("Хабарлама…");
        msgRow.addView(messageInput, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button send = button("Жіберу");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(96), dp(50));
        sendLp.leftMargin = dp(8);
        msgRow.addView(send, sendLp);
        root.addView(msgRow, topLp(dp(50), 8));
        send.setOnClickListener(v -> sendChat());

        Button disconnect = button("Жұпты ажырату");
        disconnect.setOnClickListener(v -> clearPeer());
        root.addView(disconnect, topLp(dp(48), 12));

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.addView(root);
        setContentView(page);

        refreshIdentity();
        refreshPeer();

        if (!pendingInviteId.isEmpty()) {
            searchId.setText(pendingInviteId);
            searchResult.setText("Шақыру: " + pendingInviteNick + " • ID " + pendingInviteId);
            pairButton.setVisibility(View.VISIBLE);
        }
    }

    private void connectMqtt() {
        new Thread(() -> {
            try {
                mqtt = new MqttClient(
                        BROKER, "almas-" + java.util.UUID.randomUUID(), new MemoryPersistence());

                mqtt.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        mqttOnline = true;
                        ui.post(MainActivity.this::refreshConnection);
                        subscribeBase();

                        if (!myNick.isEmpty() && !myId.isEmpty()) {
                            publishIdentity();
                            if (!peerId.isEmpty()) {
                                publish(BASE + "friend/" + peerId,
                                        "READY|" + myId + "|" + safe(myNick), false, 1);
                                ui.postDelayed(() ->
                                        ensureRtc(myId.compareTo(peerId) < 0), 400);
                            }
                        } else {
                            ui.postDelayed(MainActivity.this::showNickDialog, 350);
                        }
                    }

                    @Override public void connectionLost(Throwable cause) {
                        mqttOnline = false;
                        ui.post(MainActivity.this::refreshConnection);
                    }

                    @Override public void messageArrived(String topic, MqttMessage message) {
                        handleMessage(topic, message.getPayload());
                    }

                    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                MqttConnectOptions o = new MqttConnectOptions();
                o.setAutomaticReconnect(true);
                o.setCleanSession(true);
                o.setConnectionTimeout(8);
                o.setKeepAliveInterval(20);
                mqtt.connect(o);

            } catch (Exception e) {
                mqttOnline = false;
                ui.post(() -> {
                    refreshConnection();
                    toast("Сервер қатесі: " + e.getClass().getSimpleName());
                });
            }
        }).start();
    }

    private void subscribeBase() {
        try {
            mqtt.subscribe(BASE + "profile/+", 0);
            if (!myId.isEmpty()) {
                mqtt.subscribe(BASE + "friend/" + myId, 1);
                mqtt.subscribe(BASE + "chat/" + myId, 1);
                mqtt.subscribe(BASE + "rtc/" + myId, 1);
            }
        } catch (Exception ignored) {}
    }

    private void handleMessage(String topic, byte[] payloadBytes) {
        try {
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);

            if (topic.startsWith(BASE + "profile/")) {
                String id = topic.substring(topic.lastIndexOf('/') + 1);
                String[] p = payload.split("\\|", 3);
                if (p.length >= 2) profiles.put(id, p[1]);
                return;
            }

            if (topic.startsWith(BASE + "name/")) {
                nameOwners.put(topic, payload);
                return;
            }

            if (topic.equals(BASE + "friend/" + myId)) {
                handleFriend(payload);
                return;
            }

            if (topic.equals(BASE + "chat/" + myId)) {
                handleFallbackChat(payload);
                return;
            }

            if (topic.equals(BASE + "rtc/" + myId) && rtc != null) {
                rtc.handleSignal(payload);
            }
        } catch (Exception ignored) {}
    }

    private void showNickDialog() {
        if (isFinishing() || !myNick.isEmpty()) return;

        final EditText input = input("Мысалы: Almas");
        input.setSingleLine(true);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Бірегей nickname")
                .setMessage("Бұл атты бір адам ғана қолдана алады.")
                .setView(input)
                .setPositiveButton("Тіркелу", null)
                .setCancelable(false)
                .create();

        d.setOnShowListener(x ->
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String n = input.getText().toString().trim();
                    if (n.length() < 2 || n.length() > 24) {
                        input.setError("2–24 таңба");
                        return;
                    }
                    claimNickname(n, d, input);
                }));
        d.show();
    }

    private void claimNickname(String nick, AlertDialog dialog, EditText input) {
        if (mqtt == null || !mqtt.isConnected()) {
            input.setError("Интернетке қосылмаған");
            return;
        }

        if (myId.isEmpty()) myId = generateId();
        final String id = myId;
        final String topic = BASE + "name/" + sha256(nick.toLowerCase(Locale.ROOT));

        try { mqtt.subscribe(topic, 1); } catch (Exception ignored) {}

        ui.postDelayed(() -> {
            String old = nameOwners.get(topic);
            if (old != null && !old.isEmpty()) {
                String[] a = old.split("\\|", 3);
                if (a.length > 0 && !a[0].equals(id)) {
                    input.setError("Бұл nickname бос емес");
                    return;
                }
            }

            publish(topic, id + "|" + nick, true, 1);

            ui.postDelayed(() -> {
                String now = nameOwners.get(topic);
                if (now != null && now.startsWith(id + "|")) {
                    myNick = nick;
                    prefs.edit()
                            .putString("id", myId)
                            .putString("nick", myNick)
                            .apply();

                    try {
                        mqtt.subscribe(BASE + "friend/" + myId, 1);
                        mqtt.subscribe(BASE + "chat/" + myId, 1);
                        mqtt.subscribe(BASE + "rtc/" + myId, 1);
                    } catch (Exception ignored) {}

                    publishIdentity();
                    dialog.dismiss();
                    refreshIdentity();

                    if (!pendingInviteId.isEmpty()) {
                        searchId.setText(pendingInviteId);
                        searchPerson();
                    }
                } else {
                    input.setError("Nickname тіркелмеді, қайта бас");
                }
            }, 550);
        }, 550);
    }

    private void publishIdentity() {
        publish(BASE + "profile/" + myId,
                myId + "|" + myNick + "|" + System.currentTimeMillis(), true, 1);

        String nt = BASE + "name/" + sha256(myNick.toLowerCase(Locale.ROOT));
        publish(nt, myId + "|" + myNick, true, 1);
        profiles.put(myId, myNick);
    }

    private void searchPerson() {
        String id = searchId.getText().toString().trim();

        if (id.equals(myId)) {
            searchResult.setText("Бұл өз ID-ың");
            pairButton.setVisibility(View.GONE);
            return;
        }

        String n = profiles.get(id);
        if (n == null && id.equals(pendingInviteId) && !pendingInviteNick.isEmpty()) {
            n = pendingInviteNick;
        }

        if (n == null) {
            searchResult.setText("ID табылмады. Екінші телефон online болсын.");
            pairButton.setVisibility(View.GONE);
        } else {
            pendingInviteId = id;
            pendingInviteNick = n;
            searchResult.setText(n + " • ID " + id);
            pairButton.setVisibility(View.VISIBLE);
        }
    }

    private void requestPair() {
        String id = pendingInviteId.isEmpty()
                ? searchId.getText().toString().trim()
                : pendingInviteId;

        if (id.isEmpty() || myId.isEmpty()) return;

        String n = profiles.get(id);
        if (n == null) n = pendingInviteNick;
        if (n == null || n.isEmpty()) n = "User";

        pendingInviteNick = n;
        publish(BASE + "friend/" + id,
                "REQ|" + myId + "|" + safe(myNick), false, 1);
        searchResult.setText("Сұрау жіберілді: " + n);
    }

    private void handleFriend(String payload) {
        String[] p = payload.split("\\|", 4);
        if (p.length < 3) return;

        if ("REQ".equals(p[0])) {
            final String id = p[1];
            final String nick = p[2];

            if (!peerId.isEmpty() && !peerId.equals(id)) {
                publish(BASE + "friend/" + id,
                        "BUSY|" + myId + "|" + safe(myNick), false, 1);
                return;
            }

            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Қосылу сұрауы")
                    .setMessage(nick + " • ID " + id)
                    .setNegativeButton("Бас тарту", null)
                    .setPositiveButton("Қосу", (d, w) -> {
                        savePeer(id, nick);
                        ensureRtc(false);
                        publish(BASE + "friend/" + id,
                                "ACK|" + myId + "|" + safe(myNick), false, 1);
                    })
                    .show());

        } else if ("ACK".equals(p[0])) {
            savePeer(p[1], p[2]);
            ensureRtc(true);
            ui.post(() -> toast("Қосылды: " + p[2]));

        } else if ("BUSY".equals(p[0])) {
            ui.post(() -> toast("Бұл адам қазір басқа телефонмен жұптасқан"));

        } else if ("READY".equals(p[0])) {
            if (peerId.equals(p[1])) {
                ensureRtc(myId.compareTo(peerId) < 0);
            }
        }
    }

    private void ensureRtc(boolean initiator) {
        if (rtc == null || myId.isEmpty() || peerId.isEmpty()) return;
        rtc.connect(myId, peerId, initiator);
    }

    private void savePeer(String id, String nick) {
        peerId = id;
        peerNick = nick;
        prefs.edit()
                .putString("peer_id", id)
                .putString("peer_nick", nick)
                .apply();
        ui.post(this::refreshPeer);
    }

    private void clearPeer() {
        stopVoice();
        stopScreen();

        if (rtc != null) rtc.resetPeer();

        peerId = "";
        peerNick = "";
        rtcState = "";
        prefs.edit().remove("peer_id").remove("peer_nick").apply();

        remoteScreen.setVisibility(View.GONE);
        chatText.setText("");

        refreshPeer();
        refreshConnection();
    }

    private void sendChat() {
        if (peerId.isEmpty()) {
            toast("Алдымен адам қос");
            return;
        }

        String msg = messageInput.getText().toString().trim();
        if (msg.isEmpty()) return;

        ensureRtc(myId.compareTo(peerId) < 0);

        boolean p2pSent = rtc != null && rtc.sendChat(msg);
        if (!p2pSent) {
            String b64 = Base64.encodeToString(
                    msg.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            publish(BASE + "chat/" + peerId,
                    myId + "|" + safe(myNick) + "|" + b64, false, 1);
        }

        appendChat("Мен: " + msg);
        messageInput.setText("");
    }

    private void handleFallbackChat(String payload) {
        String[] p = payload.split("\\|", 3);
        if (p.length < 3 || !p[0].equals(peerId)) return;

        try {
            String msg = new String(
                    Base64.decode(p[2], Base64.DEFAULT), StandardCharsets.UTF_8);
            ui.post(() -> appendChat(p[1] + ": " + msg));
        } catch (Exception ignored) {}
    }

    private void appendChat(String line) {
        String old = chatText.getText().toString();
        if (old.length() > 5000) {
            old = old.substring(old.length() - 3500);
        }
        chatText.setText(old + (old.isEmpty() ? "" : "\n") + line);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toggleVoice() {
        if (peerId.isEmpty()) {
            toast("Алдымен адам қос");
            return;
        }

        if (!voiceOn) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                return;
            }

            ensureRtc(myId.compareTo(peerId) < 0);

            Intent keep = new Intent(this, VoiceService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(keep);
            else startService(keep);

            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(true);

            rtc.setMicEnabled(true);
            voiceOn = true;
            voiceButton.setText("🎙 Дауыс ON • Opus");

        } else {
            stopVoice();
        }
    }

    private void stopVoice() {
        if (rtc != null) rtc.setMicEnabled(false);
        stopService(new Intent(this, VoiceService.class));

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);

        voiceOn = false;
        if (voiceButton != null) voiceButton.setText("🎙 Дауыс HQ");
    }

    private void toggleScreen() {
        if (peerId.isEmpty()) {
            toast("Алдымен адам қос");
            return;
        }

        if (!screenOn) {
            ensureRtc(myId.compareTo(peerId) < 0);
            MediaProjectionManager m =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        } else {
            stopScreen();
        }
    }

    private void stopScreen() {
        if (rtc != null) rtc.stopScreen();
        stopService(new Intent(this, ScreenShareService.class));

        screenOn = false;
        if (screenButton != null) screenButton.setText("▣ Экран 720p/20fps");
    }

    @Override protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {

            Intent service = new Intent(this, ScreenShareService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);

            int[] size = captureSize();
            ui.postDelayed(() -> {
                boolean ok = rtc != null
                        && rtc.startScreen(data, size[0], size[1], 20);
                if (ok) {
                    screenOn = true;
                    screenButton.setText("■ Экранды тоқтату • P2P");
                } else {
                    stopService(new Intent(this, ScreenShareService.class));
                    screenOn = false;
                }
            }, 180);
        }
    }

    private int[] captureSize() {
        int w;
        int h;

        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b =
                    getSystemService(android.view.WindowManager.class)
                            .getCurrentWindowMetrics().getBounds();
            w = Math.max(2, b.width());
            h = Math.max(2, b.height());
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            w = Math.max(2, dm.widthPixels);
            h = Math.max(2, dm.heightPixels);
        }

        int maxSide = Math.max(w, h);
        float scale = maxSide > 1280 ? 1280f / maxSide : 1f;

        int outW = Math.max(2, Math.round(w * scale));
        int outH = Math.max(2, Math.round(h * scale));

        if ((outW & 1) == 1) outW--;
        if ((outH & 1) == 1) outH--;

        return new int[]{outW, outH};
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);

        if (requestCode == REQ_AUDIO
                && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoice();
        }
    }

    private void askRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9901);
        }
    }

    private void shareInvite() {
        if (myId.isEmpty() || myNick.isEmpty()) {
            toast("Профиль әлі дайын емес");
            return;
        }

        String url = INVITE
                + "?fromId=" + Uri.encode(myId)
                + "&fromNick=" + Uri.encode(myNick);

        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT,
                "ALMAS OFFICAL: " + myNick + " • ID " + myId + "\n" + url);
        startActivity(Intent.createChooser(s, "Шақыруды жіберу"));
    }

    private void copyId() {
        if (myId.isEmpty()) return;

        ClipboardManager c =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        c.setPrimaryClip(ClipData.newPlainText("ALMAS ID", myId));
        toast("ID көшірілді: " + myId);
    }

    private void refreshIdentity() {
        if (identityView == null) return;

        if (myNick.isEmpty()) {
            identityView.setText("Nickname тіркелмеген");
        } else {
            identityView.setText(
                    myNick + "\nID: " + myId + "   • бассаң көшіріледі");
        }
    }

    private void refreshPeer() {
        if (peerView == null) return;

        if (peerId.isEmpty()) {
            peerView.setText("Қазір ешкім қосылмаған");
        } else {
            peerView.setText("Қосылған: " + peerNick + "\nID: " + peerId);
        }
    }

    private void refreshConnection() {
        if (connectionView == null) return;

        String server = mqttOnline ? "● Server online" : "● Server offline";
        if (!rtcState.isEmpty()) server += " • " + rtcState;
        connectionView.setText(server);
    }

    private void publish(
            String topic, String value, boolean retained, int qos) {
        if (mqtt == null || !mqtt.isConnected()) return;

        new Thread(() -> {
            try {
                MqttMessage m = new MqttMessage(
                        value.getBytes(StandardCharsets.UTF_8));
                m.setRetained(retained);
                m.setQos(qos);
                mqtt.publish(topic, m);
            } catch (Exception ignored) {}
        }).start();
    }

    private String generateId() {
        Random r = new Random();

        for (int x = 0; x < 50; x++) {
            String id = String.valueOf(10000000 + r.nextInt(90000000));
            if (!profiles.containsKey(id)) return id;
        }

        return String.valueOf(System.currentTimeMillis()).substring(5, 13);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();

            for (byte v : b) {
                out.append(String.format(Locale.US, "%02x", v));
            }
            return out.toString();

        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("|", " ").replace("\n", " ");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(
            String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);

        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(110, 120, 140));
        e.setBackground(card());
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setTextSize(15);
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackground(cardAccent());
        return b;
    }

    private android.graphics.drawable.GradientDrawable card() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.rgb(20, 24, 32));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), Color.rgb(44, 50, 64));
        return g;
    }

    private android.graphics.drawable.GradientDrawable cardAccent() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.rgb(104, 76, 235));
        g.setCornerRadius(dp(14));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams topLp(int h, int top) {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(-1, h);
        p.topMargin = dp(top);
        return p;
    }

    @Override protected void onDestroy() {
        stopVoice();
        stopScreen();

        if (rtc != null) {
            try { rtc.destroy(); } catch (Exception ignored) {}
            rtc = null;
        }

        try {
            if (mqtt != null) mqtt.disconnect();
        } catch (Exception ignored) {}

        super.onDestroy();
    }
}
