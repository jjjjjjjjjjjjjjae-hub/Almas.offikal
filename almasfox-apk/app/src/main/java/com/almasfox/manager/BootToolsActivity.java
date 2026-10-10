package com.almasfox.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BootToolsActivity extends Activity {
    private static final int PICK_PATCHED_BOOT = 91;
    private static final int BG = Color.rgb(13, 15, 18);
    private static final int CARD = Color.rgb(29, 32, 37);
    private static final int GREEN = Color.rgb(80, 200, 120);
    private static final int MUTED = Color.rgb(180, 184, 191);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView rootText, slotText, statusText;
    private ProgressBar progress;
    private volatile boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        refreshInfo();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("ALMAS FOX", 30, Color.WHITE, true));
        TextView sub = text("boot.img → Magisk → patched image", 15, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(22));
        root.addView(sub);

        LinearLayout info = card();
        rootText = text("Root: тексерілуде…", 17, Color.WHITE, true);
        slotText = text("Белсенді слот: …", 15, MUTED, false);
        info.addView(rootText); info.addView(slotText); root.addView(info);

        Button export = button("1. BOOT.IMG ШЫҒАРУ");
        export.setOnClickListener(v -> exportBoot()); root.addView(export);
        Button magisk = button("2. MAGISK АШУ");
        magisk.setOnClickListener(v -> openMagisk()); root.addView(magisk);
        Button patched = button("3. PATCHED BOOT ТАҢДАУ");
        patched.setOnClickListener(v -> choosePatched()); root.addView(patched);
        Button bootloader = button("4. BOOTLOADER-ГЕ ҚАЙТА ЖҮКТЕУ");
        bootloader.setOnClickListener(v -> rebootBootloader()); root.addView(bootloader);
        Button backup = button("BACKUP / RECOVERY МӘЗІРІ");
        backup.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class))); root.addView(backup);

        LinearLayout state = card();
        state.addView(text("Күйі", 17, Color.WHITE, true));
        statusText = text("Дайын.", 14, MUTED, false); state.addView(statusText);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); state.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        root.addView(state);

        TextView note = text("Root жоқ болса Android APK boot бөлімін оқи алмайды. Бірінші root үшін stock boot.img ROM/firmware-дан алынады. Егер Magisk root бар болса, шыққан boot.img бұрыннан patched болуы мүмкін.", 13, MUTED, false);
        note.setPadding(dp(4), dp(8), dp(4), 0); root.addView(note);
        return scroll;
    }

    private void refreshInfo() {
        worker.execute(() -> {
            boolean r = checkRoot(); String s = getSlotSuffix();
            String shown = s.isEmpty() ? "анықталмады" : s.substring(1).toUpperCase(Locale.ROOT);
            runOnUiThread(() -> { rootText.setText(r ? "Root: рұқсат бар ✓" : "Root: рұқсат жоқ"); rootText.setTextColor(r ? GREEN : Color.rgb(255,105,105)); slotText.setText("Белсенді слот: " + shown); });
        });
    }

    private void exportBoot() {
        if (busy) return; busy = true; working("boot.img шығарылуда…");
        worker.execute(() -> {
            if (!checkRoot()) { finish("boot.img шығару үшін Root керек.", false); return; }
            String suffix = getSlotSuffix(); String block = "/dev/block/by-name/boot" + suffix;
            String slot = suffix.isEmpty() ? "noslot" : suffix.substring(1);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String name = "AlmasFox-boot-" + slot + "-" + stamp + ".img";
            Uri uri = null;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlmasFox");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("Файл жасалмады");
                Process p = new ProcessBuilder("su", "-c", "cat '" + block + "'").start();
                MessageDigest md = MessageDigest.getInstance("SHA-256"); long total = 0;
                try (InputStream in = new BufferedInputStream(p.getInputStream(), 262144); OutputStream raw = getContentResolver().openOutputStream(uri, "w"); OutputStream out = new BufferedOutputStream(raw, 262144)) {
                    byte[] buf = new byte[262144]; int n; while ((n = in.read(buf)) != -1) { out.write(buf,0,n); md.update(buf,0,n); total += n; } out.flush();
                }
                int code = p.waitFor(); if (code != 0 || total < 1048576) throw new IllegalStateException("Boot оқылмады");
                ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); getContentResolver().update(uri, done, null, null);
                String sha = hex(md.digest()); finish("Дайын ✓ Download/AlmasFox/" + name + "\nSHA-256: " + sha, true);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("boot.img дайын").setMessage("Magisk → Install → Select and Patch a File → осы boot.img файлын таңда.").setNegativeButton("Кейін", null).setPositiveButton("Magisk ашу", (d,w) -> openMagisk()).show());
            } catch (Exception e) {
                if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                finish("Қате: " + e.getMessage(), false);
            }
        });
    }

    private void openMagisk() {
        try { Intent i = getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk"); if (i == null) throw new Exception("Magisk табылмады немесе жасырылған"); startActivity(i); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void choosePatched() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_PATCHED_BOOT);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req,res,data);
        if (req == PICK_PATCHED_BOOT && res == RESULT_OK && data != null && data.getData() != null) verifyPatched(data.getData());
    }

    private void verifyPatched(Uri uri) {
        if (busy) return; busy = true; working("Patched boot тексерілуде…");
        worker.execute(() -> {
            Uri outUri = null;
            try {
                File temp = new File(getCacheDir(), "patched.img"); MessageDigest md = MessageDigest.getInstance("SHA-256"); long total = 0;
                try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri)); OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                    byte[] buf = new byte[262144]; int n; while ((n = in.read(buf)) != -1) { out.write(buf,0,n); md.update(buf,0,n); total += n; }
                }
                if (total < 1048576 || !isBootImage(temp)) throw new IllegalStateException("Дұрыс Android boot image емес");
                String sha = hex(md.digest()); String suffix = getSlotSuffix(); String target = suffix.isEmpty() ? "boot" : "boot" + suffix;
                ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "AlmasFox-patched-ready.img"); cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"); cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlmasFox"); cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv); if (outUri == null) throw new IllegalStateException("Файл жасалмады");
                try (InputStream in = new FileInputStream(temp); OutputStream out = getContentResolver().openOutputStream(outUri, "w")) { byte[] b = new byte[262144]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); }
                ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); getContentResolver().update(outUri, done, null, null); temp.delete();
                finish("Patched image дайын ✓\nDownload/AlmasFox/AlmasFox-patched-ready.img\nМақсат: " + target + "\nSHA-256: " + sha + "\n\nНоутбукта: fastboot flash " + target + " AlmasFox-patched-ready.img", true);
            } catch (Exception e) { if (outUri != null) try { getContentResolver().delete(outUri,null,null); } catch (Exception ignored) {} finish("Қате: " + e.getMessage(), false); }
        });
    }

    private void rebootBootloader() {
        if (!checkRoot()) { Toast.makeText(this,"Root керек",Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(this).setTitle("Bootloader режимі").setMessage("Телефон fastboot/bootloader режиміне қайта жүктеледі.").setNegativeButton("Бас тарту",null).setPositiveButton("Қайта жүктеу",(d,w)->worker.execute(()->{ try { new ProcessBuilder("su","-c","reboot bootloader").start().waitFor(); } catch(Exception ignored){} })).show();
    }

    private boolean isBootImage(File f) { try (InputStream in = new FileInputStream(f)) { byte[] m = new byte[8]; return in.read(m)==8 && "ANDROID!".equals(new String(m, StandardCharsets.US_ASCII)); } catch(Exception e){ return false; } }
    private String getSlotSuffix() { String s=shell("getprop ro.boot.slot_suffix").trim(); if("_a".equals(s)||"_b".equals(s)) return s; s=shell("getprop ro.boot.slot").trim(); return ("a".equals(s)||"b".equals(s)) ? "_"+s : ""; }
    private boolean checkRoot() { try { Process p=new ProcessBuilder("su","-c","id").start(); ByteArrayOutputStream o=new ByteArrayOutputStream(); copy(p.getInputStream(),o); return p.waitFor()==0 && o.toString().contains("uid=0"); } catch(Exception e){ return false; } }
    private String shell(String cmd) { try { Process p=new ProcessBuilder("sh","-c",cmd).start(); ByteArrayOutputStream o=new ByteArrayOutputStream(); copy(p.getInputStream(),o); p.waitFor(); return o.toString(); } catch(Exception e){ return ""; } }
    private void working(String s){ runOnUiThread(()->{ progress.setIndeterminate(true); statusText.setText(s); }); }
    private void finish(String s, boolean ok){ busy=false; runOnUiThread(()->{ progress.setIndeterminate(false); progress.setProgress(ok?1000:0); statusText.setText(s); Toast.makeText(this,ok?"Дайын":"Қате",Toast.LENGTH_SHORT).show(); refreshInfo(); }); }
    private static void copy(InputStream in, OutputStream out) throws Exception { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); }
    private static String hex(byte[] bytes){ StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
    private LinearLayout card(){ LinearLayout x=new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(16),dp(15),dp(16),dp(15)); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(18)); x.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(14)); x.setLayoutParams(p); return x; }
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setGravity(Gravity.CENTER); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(Color.rgb(43,47,54)); g.setCornerRadius(dp(16)); b.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58)); p.setMargins(0,0,0,dp(12)); b.setLayoutParams(p); return b; }
    private TextView text(String v,int sp,int c,boolean bold){ TextView t=new TextView(this); t.setText(v); t.setTextSize(sp); t.setTextColor(c); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy(){ worker.shutdownNow(); super.onDestroy(); }
}
