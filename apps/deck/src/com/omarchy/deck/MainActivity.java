package com.omarchy.deck;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 101;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#1a1b26"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(42), pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Omarchy Deck");
        title.setTextColor(Color.parseColor("#7aa2f7"));
        title.setTextSize(26);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("原生触屏悬浮球 · 3 形态全功能虚拟键盘 · 手机麦克风直通豆包说");
        sub.setTextColor(Color.parseColor("#a9b1d6"));
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, dp(18));
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#c0caf5"));
        statusView.setTextSize(13);
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackground(makeCard("#24283b", "#414868", 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(16);
        root.addView(statusView, slp);

        root.addView(makeBtn("1. 授予悬浮窗权限 (叠在 Termux:X11 上方)", "#24283b", "#7aa2f7", v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                Toast.makeText(this, "已获得悬浮窗权限 ✓", Toast.LENGTH_SHORT).show();
            }
        }));

        root.addView(makeBtn("2. 授予麦克风权限 (直通豆包说语音输入)", "#24283b", "#7aa2f7", v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else {
                Toast.makeText(this, "已获得麦克风权限 ✓", Toast.LENGTH_SHORT).show();
            }
        }));

        root.addView(makeBtn("▶  启动悬浮球 + 麦克风桥接，并进入桌面", "#7aa2f7", "#1a1b26", v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先点击第 1 步授予悬浮窗权限", Toast.LENGTH_LONG).show();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            Intent svc = new Intent(this, DeckOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Intent x11 = getPackageManager().getLaunchIntentForPackage("com.termux.x11");
            if (x11 != null) {
                startActivity(x11);
            } else {
                Toast.makeText(this, "悬浮球与麦克风服务已启动！请切换到 Termux:X11", Toast.LENGTH_SHORT).show();
            }
        }));

        root.addView(makeBtn("■  停止悬浮球与麦克风服务", "#2f2336", "#f7768e", v -> {
            stopService(new Intent(this, DeckOverlayService.class));
            Toast.makeText(this, "Omarchy Deck 服务已停止", Toast.LENGTH_SHORT).show();
            refreshStatus();
        }));

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        statusView.setText(
                "悬浮窗权限: " + (ov ? "✓ 已授权" : "✗ 未授权") + "\n" +
                "麦克风权限: " + (mic ? "✓ 已授权 (16kHz PCM -> 127.0.0.1:18900)" : "✗ 未授权") + "\n" +
                "键盘指令通道: 127.0.0.1:18901 (Wayland wtype / Sway)"
        );
    }

    private Button makeBtn(String label, String bgHex, String fgHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setBackground(makeCard(bgHex, "#7aa2f7", 10));
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable makeCard(String bgHex, String strokeHex, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bgHex));
        d.setStroke(dp(1), Color.parseColor(strokeHex));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
