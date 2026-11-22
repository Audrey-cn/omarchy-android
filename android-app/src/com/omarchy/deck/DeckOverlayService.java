package com.omarchy.deck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeckOverlayService extends Service {
    private static final String CH_ID = "omarchy_deck_ch";
    private WindowManager wm;
    private View fabView;
    private WindowManager.LayoutParams fabLp;
    private View oskView;
    private WindowManager.LayoutParams oskLp;

    private String oskMode = "floating"; // docked | compact | floating
    private boolean oskVisible = false;
    private float oskAlpha = 0.82f;
    private static final float FLOATING_SCALE = 0.70f;
    private int oskPosX = 0;
    private int oskPosY = 14;

    private boolean modSuper = false, modCtrl = false, modAlt = false, modShift = false;
    private final Map<String, Button> modBtns = new HashMap<>();
    private final ExecutorService netExec = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showFab();
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Omarchy Deck", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Omarchy Deck 运行中")
                .setContentText("原生悬浮球 · 可缩放透明键盘 · 直呼豆包输入法")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        startForeground(1, n);
    }

    private void sendJson(String jsonLine) {
        netExec.execute(() -> {
            try (Socket s = new Socket("127.0.0.1", 18901)) {
                OutputStream os = s.getOutputStream();
                os.write((jsonLine + "\n").getBytes("UTF-8"));
                os.flush();
            } catch (Exception ignored) {}
        });
    }

    private void sendCmd(String cmd) {
        sendJson("{\"type\":\"cmd\",\"cmd\":\"" + cmd + "\"}");
    }

    private void sendKey(String key) {
        String j = "{\"type\":\"key\",\"key\":\"" + key + "\""
                + ",\"super\":" + modSuper
                + ",\"ctrl\":" + modCtrl
                + ",\"alt\":" + modAlt
                + ",\"shift\":" + modShift + "}";
        sendJson(j);
        if (modSuper || modCtrl || modAlt || modShift) {
            modSuper = modCtrl = modAlt = modShift = false;
            refreshModButtons();
        }
    }

    private void triggerAndroidIme() {
        hideOsk();
        // Ask host bridge to toggle Termux:X11 native IME (pops up Doubao IME directly with zero middle box!)
        sendCmd("toggle_android_ime");
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        } catch (Exception ignored) {}
    }

    private void showFab() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(makeRounded("#E61a1b26", "#7aa2f7", 24, 2));
        pill.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView bKb = makeFabIcon("⌨", "#c0caf5");
        bKb.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleOsk();
        });

        TextView bIme = makeFabIcon("🎤", "#9ece6a");
        bIme.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            triggerAndroidIme();
        });

        TextView bPhone = makeFabIcon("📱", "#7aa2f7");
        bPhone.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendCmd("phone_toggle");
        });

        pill.addView(bKb);
        pill.addView(bIme);
        pill.addView(bPhone);

        View.OnTouchListener drag = new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = fabLp.x; iy = fabLp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (tx - e.getRawX());
                        int dy = (int) (ty - e.getRawY());
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            moved = true;
                            fabLp.x = ix + dx;
                            fabLp.y = iy + dy;
                            wm.updateViewLayout(fabView, fabLp);
                        }
                        return moved;
                }
                return false;
            }
        };
        pill.setOnTouchListener(drag);

        fabLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.x = dp(14);
        fabLp.y = dp(24);
        fabView = pill;
        wm.addView(fabView, fabLp);
    }

    private TextView makeFabIcon(String text, String colorHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(17);
        tv.setTextColor(Color.parseColor(colorHex));
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        return tv;
    }

    private void toggleOsk() {
        if (oskVisible) {
            hideOsk();
        } else {
            showOsk(oskMode);
        }
    }

    private void hideOsk() {
        if (oskView != null) {
            try { wm.removeView(oskView); } catch (Exception ignored) {}
            oskView = null;
        }
        oskVisible = false;
    }

    private int bgWithAlpha(float alpha) {
        int a = Math.max(40, Math.min(245, Math.round(alpha * 255f)));
        return Color.argb(a, 0x1a, 0x1b, 0x26);
    }

    private void showOsk(String mode) {
        hideOsk();
        oskMode = mode;
        modBtns.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(bgWithAlpha(oskAlpha));
        rootBg.setStroke(dp(2), Color.parseColor("#7aa2f7"));
        rootBg.setCornerRadius(dp("floating".equals(mode) ? 12 : 0));
        root.setBackground(rootBg);
        root.setPadding(dp(6), dp(6), dp(6), dp(8));

        // Top toolbar (also acts as drag handle in floating mode!)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(4));

        bar.addView(makeBarBtn("全键盘", "docked".equals(mode), v -> showOsk("docked")));
        bar.addView(makeBarBtn("精简条", "compact".equals(mode), v -> showOsk("compact")));
        bar.addView(makeBarBtn("悬浮", "floating".equals(mode), v -> showOsk("floating")));

        // Workspace 1-5 square chunky buttons right after mode buttons!
        for (int ws = 1; ws <= 5; ws++) {
            final String w = String.valueOf(ws);
            Button wb = makeBarBtn(w, false, v -> sendCmd("ws_" + w));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(32), dp(30));
            wlp.setMargins(dp(2), 0, dp(2), 0);
            wb.setLayoutParams(wlp);
            wb.setPadding(0, 0, 0, 0);
            bar.addView(wb);
        }

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        // Opacity slider (25% - 95%)
        SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(70);
        alphaBar.setProgress(Math.round((oskAlpha - 0.25f) * 100f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(72), dp(26));
        alp.setMargins(dp(4), 0, dp(4), 0);
        alphaBar.setLayoutParams(alp);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                oskAlpha = 0.25f + (progress / 100f);
                rootBg.setColor(bgWithAlpha(oskAlpha));
                root.setAlpha(Math.max(0.42f, oskAlpha + 0.12f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        bar.addView(alphaBar);

        if ("floating".equals(mode)) {
            TextView dragHandle = new TextView(this);
            dragHandle.setText("⠿ 拖动");
            dragHandle.setTextSize(11);
            dragHandle.setTextColor(Color.parseColor("#7aa2f7"));
            dragHandle.setGravity(Gravity.CENTER);
            dragHandle.setBackground(makeRounded("#CC283457", "#7aa2f7", 6, 1));
            dragHandle.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            dlp.setMargins(dp(3), 0, dp(3), 0);
            dragHandle.setLayoutParams(dlp);
            dragHandle.setOnTouchListener(new View.OnTouchListener() {
                int ix, iy; float tx, ty;
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            ix = oskLp.x; iy = oskLp.y;
                            tx = e.getRawX(); ty = e.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            oskLp.x = ix + (int) (e.getRawX() - tx);
                            oskLp.y = iy - (int) (e.getRawY() - ty);
                            oskPosX = oskLp.x;
                            oskPosY = oskLp.y;
                            wm.updateViewLayout(oskView, oskLp);
                            return true;
                    }
                    return false;
                }
            });
            bar.addView(dragHandle);
        }

        bar.addView(makeBarBtn("🎤", false, v -> triggerAndroidIme()));
        bar.addView(makeBarBtn("✕", false, v -> hideOsk()));
        root.addView(bar);

        String[][][] rows;
        if ("compact".equals(mode)) {
            rows = new String[][][]{
                {{"Esc","Escape"},{"Tab","Tab"},{"Super","MOD:super"},{"Ctrl","MOD:ctrl"},{"Alt","MOD:alt"},{"Shift","MOD:shift"},{"←","Left"},{"↓","Down"},{"↑","Up"},{"→","Right"}},
                {{"应用","CMD:apps"},{"菜单","CMD:menu"},{"终端","CMD:terminal"},{"关窗","CMD:kill"},{"全屏","CMD:fullscreen"}}
            };
        } else {
            rows = new String[][][]{
                {{"Esc","Escape"},{"Tab","Tab"},{"Super","MOD:super"},{"Ctrl","MOD:ctrl"},{"Alt","MOD:alt"},{"←","Left"},{"↓","Down"},{"↑","Up"},{"→","Right"},{"⌫","BackSpace"}},
                {{"1","1"},{"2","2"},{"3","3"},{"4","4"},{"5","5"},{"6","6"},{"7","7"},{"8","8"},{"9","9"},{"0","0"},{"-","minus"},{"=","equal"}},
                {{"q","q"},{"w","w"},{"e","e"},{"r","r"},{"t","t"},{"y","y"},{"u","u"},{"i","i"},{"o","o"},{"p","p"},{"[","bracketleft"},{"]","bracketright"}},
                {{"a","a"},{"s","s"},{"d","d"},{"f","f"},{"g","g"},{"h","h"},{"j","j"},{"k","k"},{"l","l"},{";","semicolon"},{"'","apostrophe"},{"⏎","Return"}},
                {{"⇧","MOD:shift"},{"z","z"},{"x","x"},{"c","c"},{"v","v"},{"b","b"},{"n","n"},{"m","m"},{",","comma"},{".","period"},{"/","slash"},{"中/EN","CMD:ime"}},
                {{"应用","CMD:apps"},{"菜单","CMD:menu"},{"Space","space"},{"终端","CMD:terminal"},{"关窗","CMD:kill"}}
            };
        }

        int keyH = dp(Math.round(36 * ("floating".equals(mode) ? FLOATING_SCALE : 1.0f)));
        for (String[][] r : rows) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (String[] k : r) {
                String label = k[0], code = k[1];
                Button b = new Button(this);
                b.setText(label);
                b.setAllCaps(false);
                b.setTextSize(12);
                b.setPadding(dp(2), dp(4), dp(2), dp(4));
                b.setMinHeight(0);
                b.setMinimumHeight(keyH);
                b.setMinWidth(0);
                b.setMinimumWidth(0);

                boolean isSpecial = code.startsWith("MOD:") || code.startsWith("CMD:");
                b.setTextColor(Color.parseColor(isSpecial ? "#7aa2f7" : "#c0caf5"));
                b.setBackground(makeRounded(isSpecial ? "#CC283457" : "#CC24283b", isSpecial ? "#7aa2f7" : "#414868", 6, 1));

                if (code.startsWith("MOD:")) {
                    modBtns.put(code.substring(4), b);
                }

                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (code.startsWith("MOD:")) {
                        String m = code.substring(4);
                        if ("super".equals(m)) modSuper = !modSuper;
                        else if ("ctrl".equals(m)) modCtrl = !modCtrl;
                        else if ("alt".equals(m)) modAlt = !modAlt;
                        else if ("shift".equals(m)) modShift = !modShift;
                        refreshModButtons();
                    } else if (code.startsWith("CMD:")) {
                        sendCmd(code.substring(4));
                    } else {
                        sendKey(code);
                    }
                });

                float w = "space".equals(code) ? 2.6f : 1f;
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, keyH, w);
                klp.setMargins(dp(2), dp(2), dp(2), dp(2));
                rowView.addView(b, klp);
            }
            root.addView(rowView);
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int width = "floating".equals(mode)
                ? Math.min(screenW, Math.round(screenW * 0.80f * FLOATING_SCALE))
                : WindowManager.LayoutParams.MATCH_PARENT;
        oskLp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        oskLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        oskLp.x = "floating".equals(mode) ? oskPosX : 0;
        oskLp.y = "floating".equals(mode) ? oskPosY : 0;

        root.setAlpha(Math.max(0.42f, oskAlpha + 0.12f));
        oskView = root;
        wm.addView(oskView, oskLp);
        oskVisible = true;
    }

    private Button makeBarBtn(String label, boolean active, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(Color.parseColor(active ? "#1a1b26" : "#c0caf5"));
        b.setBackground(makeRounded(active ? "#7aa2f7" : "#CC24283b", "#7aa2f7", 6, 1));
        b.setPadding(dp(7), dp(2), dp(7), dp(2));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(28));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        return b;
    }

    private void refreshModButtons() {
        setModStyle("super", modSuper);
        setModStyle("ctrl", modCtrl);
        setModStyle("alt", modAlt);
        setModStyle("shift", modShift);
    }

    private void setModStyle(String name, boolean on) {
        Button b = modBtns.get(name);
        if (b == null) return;
        b.setTextColor(Color.parseColor(on ? "#1a1b26" : "#7aa2f7"));
        b.setBackground(makeRounded(on ? "#7aa2f7" : "#CC283457", "#7aa2f7", 6, 1));
    }

    private GradientDrawable makeRounded(String bgHex, String strokeHex, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bgHex));
        d.setStroke(dp(strokeDp), Color.parseColor(strokeHex));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        hideOsk();
        if (fabView != null) {
            try { wm.removeView(fabView); } catch (Exception ignored) {}
        }
        netExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
