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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BootVerifyActivity extends Activity {
    private static final int PICK_BOOT = 120;
    private static final String DB_URL = "https://raw.githubusercontent.com/jjjjjjjjjjjjjjae-hub/Almas.offikal/almasfox-apk/almasfox-apk/bootdb.json";
    private static final int BG=Color.rgb(13,15,18), CARD=Color.rgb(29,32,37), MUTED=Color.rgb(180,184,191), GREEN=Color.rgb(80,200,120), RED=Color.rgb(255,105,105);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private String lastReport="";
    private boolean strongVerified=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); setContentView(buildUi());
    }

    private android.view.View buildUi(){
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(28),dp(20),dp(32)); scroll.addView(root);
        root.addView(text("ALMAS FOX",30,Color.WHITE,true));
        TextView sub=text("boot.img тексеру → Magisk → USB/PC",15,MUTED,false); sub.setPadding(0,dp(4),0,dp(18)); root.addView(sub);

        LinearLayout info=card();
        info.addView(text("Құрылғы: "+Build.MANUFACTURER+" "+Build.MODEL,16,Color.WHITE,true));
        info.addView(text("Device: "+Build.DEVICE,14,MUTED,false));
        info.addView(text("Android: "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT,14,MUTED,false));
        info.addView(text("Build ID: "+Build.ID,14,MUTED,false));
        info.addView(text("Display: "+Build.DISPLAY,13,MUTED,false));
        if(Build.VERSION.SDK_INT>=23) info.addView(text("Security patch: "+Build.VERSION.SECURITY_PATCH,13,MUTED,false));
        boolean spoofed=!Build.FINGERPRINT.toLowerCase(Locale.ROOT).contains(Build.DEVICE.toLowerCase(Locale.ROOT));
        info.addView(text("Fingerprint: "+(spoofed?"spoof болуы мүмкін":"құрылғымен сәйкес"),13,spoofed?Color.rgb(255,190,90):GREEN,false));
        root.addView(info);

        Button pick=button("BOOT.IMG ТАҢДАУ ЖӘНЕ ТЕКСЕРУ"); pick.setOnClickListener(v->pickBoot()); root.addView(pick);
        Button report=button("ТЕКСЕРУ ЕСЕБІН САҚТАУ"); report.setOnClickListener(v->saveReport()); root.addView(report);
        Button magisk=button("MAGISK АШУ"); magisk.setOnClickListener(v->openMagisk()); root.addView(magisk);
        Button pc=button("USB / ALMASFOX PC КЕЗЕҢІ"); pc.setOnClickListener(v->showPcStep()); root.addView(pc);

        LinearLayout state=card(); state.addView(text("Күйі",17,Color.WHITE,true));
        status=text("boot.img файлын таңда.",14,MUTED,false); state.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); state.addView(progress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6))); root.addView(state);

        TextView warn=text("Авто-flash тек күшті тексерістен өткен image үшін рұқсат етіледі. Bootloader құлыптаулы болса AlmasFox оны ашпайды, өйткені unlock деректерді өшіруі мүмкін.",13,MUTED,false); warn.setPadding(dp(4),dp(10),dp(4),0); root.addView(warn);
        return scroll;
    }

    private void pickBoot(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i,PICK_BOOT); }

    @Override protected void onActivityResult(int req,int res,Intent data){ super.onActivityResult(req,res,data); if(req==PICK_BOOT&&res==RESULT_OK&&data!=null&&data.getData()!=null) verify(data.getData()); }

    private void verify(Uri uri){
        strongVerified=false; progress.setIndeterminate(true); status.setText("boot.img тексерілуде…");
        worker.execute(()->{
            File temp=new File(getCacheDir(),"boot-selected.img");
            try{
                MessageDigest md=MessageDigest.getInstance("SHA-256"); long size=0;
                try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(temp)){
                    byte[] buf=new byte[262144]; int n; while((n=in.read(buf))!=-1){ out.write(buf,0,n); md.update(buf,0,n); size+=n; }
                }
                byte[] head=new byte[4096]; int got; try(InputStream in=new FileInputStream(temp)){ got=in.read(head); }
                boolean magic=got>=48&&new String(head,0,8,StandardCharsets.US_ASCII).equals("ANDROID!");
                int hv=magic?le32(head,40):-1; boolean headerOk=hv>=0&&hv<=4;
                long osRaw=0; if(magic){ osRaw=(hv>=3?le32(head,16):le32(head,44))&0xffffffffL; }
                String bootOs=decodeOs(osRaw);
                boolean sizeOk=size>=4L*1024*1024&&size<=70L*1024*1024;
                boolean deviceOk="laurel_sprout".equals(Build.DEVICE);
                String sha=hex(md.digest());

                JSONObject dbMatch=findDbMatch();
                String expectedHash=dbMatch==null?"":dbMatch.optString("boot_sha256","");
                boolean hashExact=!expectedHash.isEmpty()&&expectedHash.equalsIgnoreCase(sha);
                boolean buildMatch=dbMatch!=null;
                strongVerified=magic&&headerOk&&sizeOk&&deviceOk&&hashExact;

                JSONObject r=new JSONObject();
                r.put("device",Build.DEVICE); r.put("model",Build.MODEL); r.put("android",Build.VERSION.RELEASE); r.put("build_id",Build.ID); r.put("display",Build.DISPLAY); r.put("fingerprint",Build.FINGERPRINT);
                if(Build.VERSION.SDK_INT>=23) r.put("security_patch",Build.VERSION.SECURITY_PATCH);
                r.put("boot_sha256",sha); r.put("boot_size",size); r.put("magic_android",magic); r.put("header_version",hv); r.put("boot_os",bootOs); r.put("device_ok",deviceOk); r.put("size_ok",sizeOk); r.put("database_build_match",buildMatch); r.put("database_hash_exact",hashExact); r.put("auto_flash_allowed",strongVerified);
                lastReport=r.toString(2);

                String msg="SHA-256: "+sha+"\nКөлемі: "+(size/1024/1024)+" МБ\nHeader: "+(magic?"ANDROID!":"ҚАТЕ")+" / v"+hv+"\nBoot OS: "+bootOs+"\nҚұрылғы: "+(deviceOk?"laurel_sprout ✓":"сәйкес емес")+"\n";
                if(strongVerified) msg+="\nКҮШТІ СӘЙКЕСТІК ✓\nДерқордағы exact boot SHA-256 сәйкес. AlmasFox PC auto-flash кезеңіне өткізе алады.";
                else if(buildMatch) msg+="\nBUILD СӘЙКЕС, БІРАҚ BOOT HASH РАСТАЛМАҒАН.\nАвтоматты flash бұғатталады. PC exact ROM/boot көзін тексеруі керек.";
                else msg+="\nДәл build жазбасы табылмады. Автоматты flash бұғатталады.";
                String finalMsg=msg; runOnUiThread(()->{ progress.setIndeterminate(false); progress.setProgress(strongVerified?1000:350); status.setText(finalMsg); status.setTextColor(strongVerified?GREEN:MUTED); });
            }catch(Exception e){ runOnUiThread(()->{ progress.setIndeterminate(false); progress.setProgress(0); status.setTextColor(RED); status.setText("Тексеру қатесі: "+e.getMessage()); }); }
        });
    }

    private JSONObject findDbMatch(){
        try{
            HttpURLConnection c=(HttpURLConnection)new URL(DB_URL).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000); if(c.getResponseCode()!=200)return null;
            StringBuilder s=new StringBuilder(); try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){ String line; while((line=r.readLine())!=null)s.append(line); }
            JSONArray a=new JSONObject(s.toString()).getJSONArray("entries");
            for(int i=0;i<a.length();i++){
                JSONObject e=a.getJSONObject(i); if(!e.optBoolean("trusted",false))continue; if(!Build.DEVICE.equals(e.optString("device")))continue;
                boolean displayOk=e.optString("display","").isEmpty()||Build.DISPLAY.equals(e.optString("display"));
                boolean buildOk=e.optString("build_id","").isEmpty()||Build.ID.equals(e.optString("build_id"));
                boolean patchOk=e.optString("security_patch","").isEmpty()||Build.VERSION.SECURITY_PATCH.equals(e.optString("security_patch"));
                if(displayOk&&buildOk&&patchOk)return e;
            }
        }catch(Exception ignored){}
        return null;
    }

    private void saveReport(){
        if(lastReport.isEmpty()){ Toast.makeText(this,"Алдымен boot.img тексер",Toast.LENGTH_LONG).show(); return; }
        worker.execute(()->{ try{
            ContentValues cv=new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME,"AlmasFox-boot-check.json"); cv.put(MediaStore.MediaColumns.MIME_TYPE,"application/json"); cv.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/AlmasFox"); cv.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv); if(u==null)throw new Exception("Файл жасалмады"); try(OutputStream o=getContentResolver().openOutputStream(u,"w")){ o.write(lastReport.getBytes(StandardCharsets.UTF_8)); }
            ContentValues done=new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING,0); getContentResolver().update(u,done,null,null);
            runOnUiThread(()->Toast.makeText(this,"Download/AlmasFox/AlmasFox-boot-check.json",Toast.LENGTH_LONG).show());
        }catch(Exception e){ runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show()); } });
    }

    private void openMagisk(){ try{ Intent i=getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk"); if(i==null)throw new Exception("Magisk орнатылмаған"); startActivity(i); }catch(Exception e){ Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); } }

    private void showPcStep(){ new AlertDialog.Builder(this).setTitle("AlmasFox PC").setMessage("USB debugging қосып, телефонды USB кабельмен ноутбукке жалға. PC қолданбасы құрылғы/build-ті қайта тексереді, boot.img-ті телефонға жібереді, Magisk patch нәтижесін күтеді және bootloader unlocked болса ғана flash кезеңін жалғастырады. /data форматтау командасы қолданылмайды.").setPositiveButton("Түсіндім",null).show(); }

    private static int le32(byte[] b,int o){ return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24); }
    private static String decodeOs(long v){ if(v==0)return "0 (header-де көрсетілмеген)"; long ver=v>>11; int a=(int)((ver>>14)&127), b=(int)((ver>>7)&127), c=(int)(ver&127); int y=2000+(int)((v>>4)&127), m=(int)(v&15); return a+"."+b+"."+c+" / "+y+"-"+String.format(Locale.US,"%02d",m); }
    private static String hex(byte[] x){ StringBuilder s=new StringBuilder(); for(byte b:x)s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
    private LinearLayout card(){ LinearLayout x=new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(16),dp(15),dp(16),dp(15)); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(18)); x.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(14)); x.setLayoutParams(p); return x; }
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setGravity(Gravity.CENTER); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(Color.rgb(43,47,54)); g.setCornerRadius(dp(16)); b.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58)); p.setMargins(0,0,0,dp(12)); b.setLayoutParams(p); return b; }
    private TextView text(String v,int sp,int c,boolean bold){ TextView t=new TextView(this); t.setText(v); t.setTextSize(sp); t.setTextColor(c); t.setTextIsSelectable(true); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy(){ worker.shutdownNow(); super.onDestroy(); }
}
