package com.almasfox.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView rootText;
    private TextView slotText;
    private TextView statusText;
    private ProgressBar progress;
    private volatile boolean busy = false;

    private static final int PICK_BACKUP = 77;
    private static final int BG = Color.rgb(13, 15, 18);
    private static final int CARD = Color.rgb(29, 32, 37);
    private static final int ORANGE = Color.rgb(255, 132, 36);
    private static final int GREEN = Color.rgb(80, 200, 120);
    private static final int MUTED = Color.rgb(180, 184, 191);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        refreshDeviceInfo(false);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ALMAS FOX", 30, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = text("Қазақша қауіпсіз recovery көмекшісі", 15, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        LinearLayout info = card();
        rootText = text("Root: тексерілуде…", 17, Color.WHITE, true);
        slotText = text("Белсенді слот: …", 15, MUTED, false);
        TextView device = text("Құрылғы: " + Build.MANUFACTURER + " " + Build.MODEL, 15, MUTED, false);
        info.addView(rootText);
        info.addView(slotText);
        info.addView(device);
        root.addView(info);

        Button rootCheck = button("ROOT РҰҚСАТЫН ТЕКСЕРУ");
        rootCheck.setOnClickListener(v -> refreshDeviceInfo(true));
        root.addView(rootCheck);

        Button backup = button("ҚАУІПСІЗ BACKUP ЖАСАУ");
        backup.setOnClickListener(v -> showBackupWarning());
        root.addView(backup);

        Button restore = button("BACKUP ФАЙЛЫН ТАҢДАУ");
        restore.setOnClickListener(v -> chooseBackup());
        root.addView(restore);

        Button recovery = button("RECOVERY-ГЕ ҚАЙТА ЖҮКТЕУ");
        recovery.setOnClickListener(v -> confirmRecoveryReboot());
        root.addView(recovery);

        LinearLayout statusCard = card();
        TextView statusTitle = text("Күйі", 17, Color.WHITE, true);
        statusText = text("Дайын. Backup файлы Download/AlmasFox ішінде сақталады.", 14, MUTED, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(1000);
        progress.setProgress(0);
        statusCard.addView(statusTitle);
        statusCard.addView(statusText);
        statusCard.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        root.addView(statusCard);

        TextView note = text(
                "Маңызды: APK recovery-дің boot.img файлын алмастырмайды. Бұл қолданба backup/root басқару көмекшісі. " +
                "Қолданбалардың аппараттық кілттері, банк сессиялары және DRM кілттері Android қауіпсіздігіне байланысты толық қайтпауы мүмкін.",
                13, MUTED, false);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(note);
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(15), dp(16), dp(15));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        box.setLayoutParams(lp);
        return box;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(43, 47, 54));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(65, 70, 78));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.setMargins(0, 0, 0, dp(12));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.15f);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private void refreshDeviceInfo(boolean askRoot) {
        worker.execute(() -> {
            String slot = shellNoRoot("getprop ro.boot.slot_suffix").trim();
            if (slot.isEmpty()) slot = shellNoRoot("getprop ro.boot.slot").trim();
            final String shownSlot = slot.isEmpty() ? "анықталмады" : slot.replace("_", "").toUpperCase(Locale.ROOT);
            boolean root = checkRoot();
            runOnUiThread(() -> {
                slotText.setText("Белсенді слот: " + shownSlot);
                rootText.setText(root ? "Root: рұқсат бар ✓" : "Root: рұқсат жоқ");
                rootText.setTextColor(root ? GREEN : Color.rgb(255, 105, 105));
                if (askRoot) Toast.makeText(this,
                        root ? "Root рұқсаты жұмыс істейді" : "Magisk/SU рұқсаты берілмеді",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private boolean checkRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(p.getInputStream(), out, false);
            int code = p.waitFor();
            return code == 0 && out.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private String shellNoRoot(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(p.getInputStream(), out, false);
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void showBackupWarning() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Қауіпсіз backup")
                .setMessage("AlmasFox фото/видео, ішкі жад және root қолжетімді қолданба деректерін бір .afbackup архивіне жинайды. " +
                        "Телефон жұмыс істеп тұрған кезде өзгеріп жатқан қолданба деректері толық синхронды болмауы мүмкін. " +
                        "Ең сенімді толық restore AlmasFox recovery арқылы жасалады.\n\nЖалғастырамыз ба?")
                .setNegativeButton("Бас тарту", null)
                .setPositiveButton("Backup жасау", (d, w) -> startBackup())
                .show();
    }

    private void startBackup() {
        if (busy) return;
        busy = true;
        progress.setIndeterminate(true);
        statusText.setText("Root тексерілуде…");

        worker.execute(() -> {
            if (!checkRoot()) {
                finishBusy("Backup жасалмады: Root рұқсатын Magisk арқылы беріңіз.", false);
                return;
            }

            Uri uri = null;
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String model = Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
                String name = "AlmasFox-" + model + "-" + stamp + ".afbackup";

                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlmasFox");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("Файл жасау мүмкін болмады");

                String command =
                        "TMP=/data/local/tmp/almasfox_$$; " +
                        "mkdir -p \"$TMP\"; " +
                        "SLOT=$(getprop ro.boot.slot_suffix); " +
                        "dd if=/dev/block/by-name/boot$SLOT of=\"$TMP/boot.img\" bs=4M 2>/dev/null || true; " +
                        "getprop > \"$TMP/getprop.txt\"; " +
                        "pm list packages -f > \"$TMP/packages.txt\"; " +
                        "cd /; " +
                        "tar -czpf - " +
                        "--exclude='data/media/0/Download/AlmasFox' " +
                        "--exclude='data/user/0/com.almasfox.manager/cache' " +
                        "data/media/0 data/user/0 data/system/packages.xml \"$TMP\" 2>/dev/null; " +
                        "R=$?; rm -rf \"$TMP\"; exit $R";

                Process p = new ProcessBuilder("su", "-c", command).start();
                long total = 0;
                try (InputStream in = new BufferedInputStream(p.getInputStream(), 256 * 1024);
                     OutputStream raw = getContentResolver().openOutputStream(uri, "w");
                     OutputStream out = new BufferedOutputStream(raw, 256 * 1024)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    long lastUi = 0;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total - lastUi >= 32L * 1024L * 1024L) {
                            lastUi = total;
                            long shown = total;
                            runOnUiThread(() -> statusText.setText(
                                    "Backup жасалуда… " + (shown / 1024 / 1024) + " МБ"));
                        }
                    }
                    out.flush();
                }
                int code = p.waitFor();
                if (code != 0 || total < 1024) throw new IllegalStateException("tar аяқталмады (код " + code + ")");

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                finishBusy("Дайын ✓  Download/AlmasFox/" + name + "\nUSB арқылы компьютерге көшіріңіз.", true);
            } catch (Exception e) {
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                }
                finishBusy("Backup қатесі: " + e.getMessage(), false);
            }
        });
    }

    private void chooseBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            statusText.setText("Backup таңдалды:\n" + uri + "\n\nҚалпына келтіру қауіпсіздік үшін AlmasFox recovery ішінде орындалады.");
            new AlertDialog.Builder(this)
                    .setTitle("Backup таңдалды")
                    .setMessage("Android жұмыс істеп тұрғанда /data үстіне толық restore жасау қауіпті. Сондықтан бұл нұсқа файлды тек таңдап/тексеруге дайындайды. Толық restore boot.img recovery компонентінде іске қосылады.")
                    .setPositiveButton("Түсіндім", null)
                    .show();
        }
    }

    private void confirmRecoveryReboot() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Recovery режимі")
                .setMessage("Телефон recovery режиміне қайта жүктеледі. Сақталмаған жұмысты жабыңыз.")
                .setNegativeButton("Бас тарту", null)
                .setPositiveButton("Қайта жүктеу", (d, w) -> worker.execute(() -> {
                    try {
                        Process p = new ProcessBuilder("su", "-c", "reboot recovery").start();
                        int code = p.waitFor();
                        if (code != 0) finishBusy("Recovery-ге жүктеу орындалмады.", false);
                    } catch (Exception e) {
                        finishBusy("Қате: " + e.getMessage(), false);
                    }
                }))
                .show();
    }

    private void finishBusy(String message, boolean ok) {
        busy = false;
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(ok ? 1000 : 0);
            statusText.setText(message);
            Toast.makeText(this, ok ? "Дайын" : "Қате", Toast.LENGTH_SHORT).show();
        });
    }

    private static void copy(InputStream in, OutputStream out, boolean close) throws Exception {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        if (close) { in.close(); out.close(); }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
