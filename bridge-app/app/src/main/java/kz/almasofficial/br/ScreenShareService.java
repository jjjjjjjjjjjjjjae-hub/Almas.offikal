package kz.almasofficial.br;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

public class ScreenShareService extends Service {
    private static final String BROKER = "ssl://broker.emqx.io:8883";
    private static final String BASE = "almasbridge/v1/";
    private static final String CHANNEL = "almas_screen";
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private MqttClient mqtt;
    private String myId = "", peerId = "";
    private long lastFrame = 0L;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2001, notification());
        if (intent == null) return START_NOT_STICKY;
        myId = intent.getStringExtra("myId");
        peerId = intent.getStringExtra("peerId");
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("resultData");
        if (myId == null || peerId == null || myId.isEmpty() || peerId.isEmpty() || data == null) {
            stopSelf(); return START_NOT_STICKY;
        }
        new Thread(this::connect).start();
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, null);
        startCapture();
        return START_STICKY;
    }

    private void connect() {
        try {
            mqtt = new MqttClient(BROKER, "almas-screen-" + UUID.randomUUID(), new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setAutomaticReconnect(true); o.setCleanSession(true); o.setConnectionTimeout(8); o.setKeepAliveInterval(20);
            mqtt.connect(o);
        } catch (Exception ignored) {}
    }

    private void startCapture() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            dm.widthPixels = b.width(); dm.heightPixels = b.height(); dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        int srcW = Math.max(1, dm.widthPixels), srcH = Math.max(1, dm.heightPixels);
        int outW = Math.min(720, srcW);
        int outH = Math.max(1, Math.round((float) srcH * outW / srcW));
        reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("AlmasScreen", outW, outH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        reader.setOnImageAvailableListener(this::onImage, null);
    }

    private void onImage(ImageReader r) {
        long now = System.currentTimeMillis();
        if (now - lastFrame < 250) {
            Image x = r.acquireLatestImage(); if (x != null) x.close(); return;
        }
        lastFrame = now;
        Image image = r.acquireLatestImage();
        if (image == null) return;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap full = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap crop = Bitmap.createBitmap(full, 0, 0, image.getWidth(), image.getHeight());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, 58, out);
            byte[] bytes = out.toByteArray();
            full.recycle(); crop.recycle();
            publish(bytes);
        } catch (Exception ignored) {
        } finally { image.close(); }
    }

    private void publish(byte[] bytes) {
        MqttClient c = mqtt;
        if (c == null || !c.isConnected()) return;
        new Thread(() -> {
            try {
                MqttMessage m = new MqttMessage(bytes); m.setQos(0); m.setRetained(false);
                c.publish(BASE + "screen/" + peerId + "/" + myId, m);
            } catch (Exception ignored) {}
        }).start();
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ALMAS Screen", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("ALMAS OFFICAL").setContentText("Экран бөлісу қосулы").setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
    }

    @Override public void onDestroy() {
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (mqtt != null) mqtt.disconnect(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
