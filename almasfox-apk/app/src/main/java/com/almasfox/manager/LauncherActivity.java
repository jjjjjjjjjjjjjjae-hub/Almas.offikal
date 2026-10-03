package com.almasfox.manager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LauncherActivity extends Activity {
    private static final int BG=Color.rgb(13,15,18), MUTED=Color.rgb(180,184,191);

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_VERTICAL); root.setPadding(dp(22),dp(32),dp(22),dp(32)); root.setBackgroundColor(BG);
        TextView t=text("ALMAS FOX",32,Color.WHITE,true); root.addView(t);
        TextView s=text("Қауіпсіз Root және Recovery көмекшісі • v0.6",15,MUTED,false); s.setPadding(0,dp(5),0,dp(28)); root.addView(s);
        Button verify=button("BOOT.IMG ТЕКСЕРУ → USB/PC → MAGISK"); verify.setOnClickListener(v->startActivity(new Intent(this,BootVerifyActivity.class))); root.addView(verify);
        Button first=button("BOOT ДЕРҚОРЫНАН ІЗДЕУ"); first.setOnClickListener(v->startActivity(new Intent(this,DeviceFinderActivity.class))); root.addView(first);
        Button tools=button("ROOT БАР — BOOT ҚҰРАЛДАРЫ"); tools.setOnClickListener(v->startActivity(new Intent(this,BootToolsActivity.class))); root.addView(tools);
        Button backup=button("BACKUP / RECOVERY ҚҰРАЛДАРЫ"); backup.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class))); root.addView(backup);
        TextView n=text("v0.6 реті: boot.img ноутбуктан таңдалады → USB data кабель және ADB RSA рұқсаты тексеріледі → boot телефонға жіберіледі → Magisk patch → содан кейін ғана Fastboot тест. Locked bootloader автоматты ашылмайды.",13,MUTED,false); n.setPadding(dp(4),dp(16),dp(4),0); root.addView(n);
        setContentView(root);
    }
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(); g.setColor(Color.rgb(43,47,54)); g.setCornerRadius(dp(16)); b.setBackground(g); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(60)); p.setMargins(0,0,0,dp(14)); b.setLayoutParams(p); return b; }
    private TextView text(String v,int sp,int c,boolean bold){ TextView t=new TextView(this); t.setText(v); t.setTextSize(sp); t.setTextColor(c); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
