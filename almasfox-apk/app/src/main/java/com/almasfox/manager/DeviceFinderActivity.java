package com.almasfox.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceFinderActivity extends Activity {
    private static final String DB_URL = "https://raw.githubusercontent.com/jjjjjjjjjjjjjjae-hub/Almas.offikal/almasfox-apk/almasfox-apk/bootdb.json";
    private static final int BG = Color.rgb(13,15,18);
    private static final int CARD = Color.rgb(29,32,37);
    private static final int MUTED = Color.rgb(180,184,191);
    private static final int GREEN = Color.rgb(80,200,120);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private JSONObject match;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(28),dp(20),dp(32)); scroll.addView(root);

        root.addView(text("ALMAS FOX",30,Color.WHITE,true));
        TextView sub=text("Құрылғыны анықтау → сенімді boot/OTA дерқоры",15,MUTED,false);
        sub.setPadding(0,dp(4),0,dp(18)); root.addView(sub);

        LinearLayout info=card();
        info.addView(text("Модель: " + Build.MANUFACTURER + " " + Build.MODEL,16,Color.WHITE,true));
        info.addView(text("Device: " + Build.DEVICE,14,MUTED,false));
        info.addView(text("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",14,MUTED,false));
        info.addView(text("Build ID: " + Build.ID,14,MUTED,false));
        info.addView(text("Display: " + Build.DISPLAY,14,MUTED,false));
        if (Build.VERSION.SDK_INT >= 23) info.addView(text("Security patch: " + Build.VERSION.SECURITY_PATCH,14,MUTED,false));
        TextView fp=text("Fingerprint:\n" + Build.FINGERPRINT,12,MUTED,false); fp.setTextIsSelectable(true); info.addView(fp);
        root.addView(info);

        Button search=button("ДЕРҚОРДАН ДӘЛ СӘЙКЕСТІК ІЗДЕУ");
        search.setOnClickListener(v -> searchDb()); root.addView(search);
        Button report=button("ҚҰРЫЛҒЫ ЕСЕБІН САҚТАУ");
        report.setOnClickListener(v -> saveReport()); root.addView(report);
        Button magisk=button("MAGISK АШУ");
        magisk.setOnClickListener(v -> openMagiskWithMatch()); root.addView(magisk);
        Button back=button("BOOT / ROOT МӘЗІРІНЕ ҚАЙТУ");
        back.setOnClickListener(v -> finish()); root.addView(back);

        LinearLayout state=card(); state.addView(text("Күйі",17,Color.WHITE,true));
        status=text("Алдымен дерқордан іздеуді бас.",14,MUTED,false); state.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        state.addView(progress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6))); root.addView(state);

        TextView warn=text("Қауіпсіздік: тек модельге қарап boot таңдалмайды. Fingerprint толық сәйкес болмаса AlmasFox ешқандай boot/OTA ұсынбайды.",13,MUTED,false);
        warn.setPadding(dp(4),dp(8),dp(4),0); root.addView(warn);
        return scroll;
    }

    private void searchDb() {
        match=null; progress.setIndeterminate(true); status.setText("AlmasFox дерқоры тексерілуде…");
        worker.execute(() -> {
            try {
                HttpURLConnection c=(HttpURLConnection)new URL(DB_URL).openConnection();
                c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setRequestProperty("Accept","application/json");
                if(c.getResponseCode()!=200) throw new Exception("HTTP " + c.getResponseCode());
                StringBuilder s=new StringBuilder();
                try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))){ String line; while((line=r.readLine())!=null)s.append(line); }
                JSONArray a=new JSONObject(s.toString()).getJSONArray("entries"); JSONObject found=null;
                for(int i=0;i<a.length();i++){
                    JSONObject e=a.getJSONObject(i);
                    if(e.optBoolean("trusted",false) && Build.FINGERPRINT.equals(e.optString("fingerprint")) && Build.DEVICE.equals(e.optString("device"))){ found=e; break; }
                }
                match=found; JSONObject f=found;
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    if(f==null){
                        progress.setProgress(0);
                        status.setText("Дәл сәйкестік табылмады.\n\nБұл build үшін бөтен boot қолданылмайды. «Құрылғы есебін сақтау» арқылы есеп шығар.");
                    } else {
                        progress.setProgress(1000);
                        status.setText("Дәл сәйкестік ✓\nBuild: " + f.optString("build") + "\nТүрі: " + f.optString("image_type") + "\nКөзі: " + f.optString("source") + "\nПакет тексеруі: " + f.optString("hash_type").toUpperCase() + " " + f.optString("hash") + "\n\nMagisk 31+ remote OTA арқылы осы ресми пакеттен boot image шығара алады.");
                    }
                });
            } catch(Exception e){ runOnUiThread(() -> { progress.setIndeterminate(false); status.setText("Дерқор қатесі: " + e.getMessage()); }); }
        });
    }

    private void openMagiskWithMatch() {
        if(match==null){ Toast.makeText(this,"Алдымен дәл сәйкестік тап",Toast.LENGTH_LONG).show(); return; }
        String url=match.optString("url");
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("AlmasFox OTA URL",url));
        try {
            Intent i=getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk");
            if(i==null) throw new Exception("Magisk орнатылмаған");
            startActivity(i);
            Toast.makeText(this,"Ресми OTA URL көшірілді. Magisk-та remote OTA/image extraction таңда.",Toast.LENGTH_LONG).show();
        } catch(Exception e){
            new AlertDialog.Builder(this).setTitle("Magisk керек").setMessage("URL буферге көшірілді. Алдымен Magisk 31 немесе жаңасын орнат.\n\n"+url).setPositiveButton("Түсіндім",null).show();
        }
    }

    private void saveReport() {
        worker.execute(() -> {
            try {
                JSONObject j=new JSONObject();
                j.put("manufacturer",Build.MANUFACTURER); j.put("brand",Build.BRAND); j.put("model",Build.MODEL);
                j.put("device",Build.DEVICE); j.put("product",Build.PRODUCT); j.put("hardware",Build.HARDWARE);
                j.put("android",Build.VERSION.RELEASE); j.put("sdk",Build.VERSION.SDK_INT); j.put("build_id",Build.ID);
                j.put("display",Build.DISPLAY); j.put("incremental",Build.VERSION.INCREMENTAL); j.put("fingerprint",Build.FINGERPRINT);
                if(Build.VERSION.SDK_INT>=23) j.put("security_patch",Build.VERSION.SECURITY_PATCH);
                ContentValues cv=new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME,"AlmasFox-device-report.json");
                cv.put(MediaStore.MediaColumns.MIME_TYPE,"application/json"); cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS+"/AlmasFox");
                cv.put(MediaStore.MediaColumns.IS_PENDING,1); Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
                if(u==null) throw new Exception("Файл жасалмады");
                try(OutputStream o=getContentResolver().openOutputStream(u,"w")){ o.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
                ContentValues done=new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING,0); getContentResolver().update(u,done,null,null);
                runOnUiThread(() -> { status.setText("Есеп дайын ✓\nDownload/AlmasFox/AlmasFox-device-report.json"); Toast.makeText(this,"Құрылғы есебі сақталды",Toast.LENGTH_SHORT).show(); });
            } catch(Exception e){ runOnUiThread(() -> status.setText("Есеп қатесі: "+e.getMessage())); }
        });
    }

    private LinearLayout card(){ LinearLayout x=new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(16),dp(15),dp(16),dp(15)); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(18)); x.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(14)); x.setLayoutParams(p); return x; }
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setGravity(Gravity.CENTER); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(Color.rgb(43,47,54)); g.setCornerRadius(dp(16)); b.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58)); p.setMargins(0,0,0,dp(12)); b.setLayoutParams(p); return b; }
    private TextView text(String v,int sp,int c,boolean bold){ TextView t=new TextView(this); t.setText(v); t.setTextSize(sp); t.setTextColor(c); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy(){ worker.shutdownNow(); super.onDestroy(); }
}
