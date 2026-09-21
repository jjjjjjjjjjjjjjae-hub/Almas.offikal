package kz.almasofficial.br;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

public class VoiceService extends Service {
    private static final String BROKER = "ssl://broker.emqx.io:8883";
    private static final String BASE = "almasbridge/v1/";
    private static final String CHANNEL = "almas_voice";
    private static final int RATE = 16000;
    private volatile boolean running;
    private String myId = "", peerId = "";
    private MqttClient mqtt;
    private AudioRecord rec;
    private AudioTrack track;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2002, notification());
        if (intent == null) return START_NOT_STICKY;
        myId = intent.getStringExtra("myId"); peerId = intent.getStringExtra("peerId");
        if (myId == null || peerId == null || myId.isEmpty() || peerId.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        new Thread(this::connectAndRun).start();
        return START_STICKY;
    }

    private void connectAndRun() {
        try {
            mqtt = new MqttClient(BROKER, "almas-mic-" + UUID.randomUUID(), new MemoryPersistence());
            mqtt.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable cause) {}
                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                @Override public void messageArrived(String topic, MqttMessage message) {
                    String sender = topic.substring(topic.lastIndexOf('/') + 1);
                    if (sender.equals(peerId) && track != null) track.write(message.getPayload(), 0, message.getPayload().length);
                }
            });
            MqttConnectOptions o = new MqttConnectOptions();
            o.setAutomaticReconnect(true); o.setCleanSession(true); o.setConnectionTimeout(8); o.setKeepAliveInterval(20);
            mqtt.connect(o);
            mqtt.subscribe(BASE + "voice/" + myId + "/+", 0);
            startAudio();
        } catch (Exception e) { stopSelf(); }
    }

    private void startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return; }
        int inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(2048, Math.max(inMin, outMin));
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2);
        track = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2, AudioTrack.MODE_STREAM);
        rec.startRecording(); track.play();
        byte[] data = new byte[1024];
        while (running) {
            int n = rec.read(data, 0, data.length);
            if (n > 0 && mqtt != null && mqtt.isConnected()) {
                byte[] chunk = new byte[n]; System.arraycopy(data, 0, chunk, 0, n);
                try {
                    MqttMessage m = new MqttMessage(chunk); m.setQos(0); m.setRetained(false);
                    mqtt.publish(BASE + "voice/" + peerId + "/" + myId, m);
                } catch (Exception ignored) {}
            }
        }
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ALMAS Voice", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("ALMAS OFFICAL").setContentText("Дауыс байланысы қосулы").setSmallIcon(android.R.drawable.presence_audio_online).setOngoing(true).build();
    }

    @Override public void onDestroy() {
        running = false;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {}
        try { if (mqtt != null) mqtt.disconnect(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
