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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
    private View imeBarView;
    private boolean imeBarVisible = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoSendTask;

    private boolean oskVisible = false;
    private float oskAlpha = 0.78f;
    private static final float FLOATING_SCALE = 0.70f;
    private int oskPosX = 0;
    private int oskPosY = 16;

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
                .setContentText("原生悬浮球 · 透明悬浮键盘 · 直呼豆包输入法")
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

    private void sendText(String text) {
        if (text == null || text.isEmpty()) return;
        String esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        sendJson("{\"type\":\"text\",\"text\":\"" + esc + "\"}");
    }

    private void hideImeBar() {
        if (autoSendTask != null) mainHandler.removeCallbacks(autoSendTask);
        if (imeBarView != null) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(imeBarView.getWindowToken(), 0);
                wm.removeView(imeBarView);
            } catch (Exception ignored) {}
            imeBarView = null;
        }
        imeBarVisible = false;
    }

    private void triggerAndroidIme() {
        if (imeBarVisible) {
            hideImeBar();
            return;
        }
        hideOsk();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(makeRounded("#E61a1b26", "#7aa2f7", 10, 2));
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));

        EditText edit = new EditText(this);
        edit.setHint("豆包语音/中文直输 (停顿 0.9 秒自动上屏 · 空框按退格删Linux文字)");
        edit.setHintTextColor(Color.parseColor("#565f89"));
        edit.setTextColor(Color.parseColor("#c0caf5"));
        edit.setTextSize(13);
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        edit.setBackground(makeRounded("#24283b", "#414868", 8, 1));
        edit.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        elp.rightMargin = dp(6);
        bar.addView(edit, elp);

        Runnable flushNow = () -> {
            if (autoSendTask != null) mainHandler.removeCallbacks(autoSendTask);
            String txt = edit.getText().toString();
            if (!txt.isEmpty()) {
                sendText(txt);
                edit.setText("");
            }
        };

        // Direct Backspace passthrough to Linux when box is empty!
        edit.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (edit.getText().length() == 0) {
                    sendKey("BackSpace");
                    return true;
                }
            }
            return false;
        });

        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                flushNow.run();
                return true;
            }
            return false;
        });

        // Auto-commit 0.9s after Doubao voice/typing finishes!
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (autoSendTask != null) mainHandler.removeCallbacks(autoSendTask);
                if (s != null && s.length() > 0) {
                    autoSendTask = flushNow;
                    mainHandler.postDelayed(autoSendTask, 900);
                }
            }
        });

        bar.addView(makeBarBtn("⌫", false, v -> sendKey("BackSpace")));
        bar.addView(makeBarBtn("⏎", false, v -> { flushNow.run(); sendKey("Return"); }));
        bar.addView(makeBarBtn("✕", false, v -> hideImeBar()));

        WindowManager.LayoutParams ilp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        ilp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ilp.y = dp(32);
        ilp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

        imeBarView = bar;
        wm.addView(imeBarView, ilp);
        imeBarVisible = true;

        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
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
            showOsk();
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

    private void hideOsk() {
        if (oskView != null) {
            try { wm.removeView(oskView); } catch (Exception ignored) {}
            oskView = null;
        }
        oskVisible = false;
        if (fabView != null) fabView.setVisibility(View.VISIBLE);
    }

    private int bgWithAlpha(float alpha) {
        int a = Math.max(36, Math.min(242, Math.round(alpha * 255f)));
        return Color.argb(a, 0x1a, 0x1b, 0x26);
    }

    private void showOsk() {
        if (oskVisible) return;
        if (fabView != null) fabView.setVisibility(View.GONE);
        modBtns.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(bgWithAlpha(oskAlpha));
        rootBg.setStroke(dp(2), Color.parseColor("#7aa2f7"));
        rootBg.setCornerRadius(dp(12));
        root.setBackground(rootBg);
        root.setPadding(dp(7), dp(6), dp(7), dp(7));

        // Clean, spacious top bar: [1][2][3][4][5]  [opacity slider]  [⠿ 拖动]  [✕]
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(5));

        for (int ws = 1; ws <= 5; ws++) {
            final String w = String.valueOf(ws);
            Button wb = makeBarBtn(w, false, v -> sendCmd("ws_" + w));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(30), dp(28));
            wlp.setMargins(dp(2), 0, dp(2), 0);
            wb.setLayoutParams(wlp);
            wb.setPadding(0, 0, 0, 0);
            bar.addView(wb);
        }

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(75);
        alphaBar.setProgress(Math.round((oskAlpha - 0.20f) * 100f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(90), dp(26));
        alp.setMargins(dp(4), 0, dp(6), 0);
        alphaBar.setLayoutParams(alp);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                oskAlpha = 0.20f + (progress / 100f);
                rootBg.setColor(bgWithAlpha(oskAlpha));
                root.setAlpha(Math.max(0.38f, oskAlpha + 0.14f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        bar.addView(alphaBar);

        TextView dragHandle = new TextView(this);
        dragHandle.setText("⠿ 拖动");
        dragHandle.setTextSize(11);
        dragHandle.setTextColor(Color.parseColor("#7aa2f7"));
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setBackground(makeRounded("#CC283457", "#7aa2f7", 6, 1));
        dragHandle.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
        dlp.setMargins(dp(2), 0, dp(4), 0);
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

        Button bClose = makeBarBtn("✕", false, v -> hideOsk());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(30), dp(28));
        clp.setMargins(dp(2), 0, 0, 0);
        bClose.setLayoutParams(clp);
        bClose.setPadding(0, 0, 0, 0);
        bar.addView(bClose);
        root.addView(bar);

        // Ergonomic weighted layout: {label, code, weight}
        String[][][] rows = new String[][][]{
            {{"Esc","Escape","1.1"},{"Tab","Tab","1.1"},{"Super","MOD:super","1.4"},{"Ctrl","MOD:ctrl","1.2"},{"Alt","MOD:alt","1.1"},{"←","Left","1.0"},{"↓","Down","1.0"},{"↑","Up","1.0"},{"→","Right","1.0"},{"⌫","BackSpace","1.4"}},
            {{"1","1","1"},{"2","2","1"},{"3","3","1"},{"4","4","1"},{"5","5","1"},{"6","6","1"},{"7","7","1"},{"8","8","1"},{"9","9","1"},{"0","0","1"},{"-","minus","1"},{"=","equal","1"}},
            {{"q","q","1"},{"w","w","1"},{"e","e","1"},{"r","r","1"},{"t","t","1"},{"y","y","1"},{"u","u","1"},{"i","i","1"},{"o","o","1"},{"p","p","1"},{"[","bracketleft","1"},{"]","bracketright","1"}},
            {{"a","a","1"},{"s","s","1"},{"d","d","1"},{"f","f","1"},{"g","g","1"},{"h","h","1"},{"j","j","1"},{"k","k","1"},{"l","l","1"},{";","semicolon","1"},{"'","apostrophe","1"},{"⏎","Return","1.5"}},
            {{"⇧","MOD:shift","1.4"},{"z","z","1"},{"x","x","1"},{"c","c","1"},{"v","v","1"},{"b","b","1"},{"n","n","1"},{"m","m","1"},{",","comma","1"},{".","period","1"},{"/","slash","1"},{"中","CMD:ime","1.2"}},
            {{"应用","CMD:apps","1.3"},{"菜单","CMD:menu","1.3"},{"Space","space","3.4"},{"终端","CMD:terminal","1.3"},{"关窗","CMD:kill","1.3"}}
        };

        int keyH = dp(Math.round(36 * FLOATING_SCALE));
        for (String[][] r : rows) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (String[] k : r) {
                String label = k[0], code = k[1];
                float weight = Float.parseFloat(k[2]);
                Button b = new Button(this);
                b.setText(label);
                b.setAllCaps(false);
                b.setTextSize(11);
                b.setPadding(0, 0, 0, 0);
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

                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, keyH, weight);
                klp.setMargins(dp(2), dp(2), dp(2), dp(2));
                rowView.addView(b, klp);
            }
            root.addView(rowView);
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - dp(16), Math.round(screenW * 0.80f * FLOATING_SCALE));
        oskLp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        oskLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        oskLp.x = oskPosX;
        oskLp.y = oskPosY;

        root.setAlpha(Math.max(0.38f, oskAlpha + 0.14f));
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
        hideImeBar();
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
