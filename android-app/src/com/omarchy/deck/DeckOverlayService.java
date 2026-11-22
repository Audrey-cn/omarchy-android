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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private View voiceBarView;

    private String oskMode = "docked"; // docked | compact | floating
    private boolean oskVisible = false;
    private boolean voiceVisible = false;

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
                .setContentText("原生悬浮球 · 虚拟键盘 · 安卓语音输入法穿透")
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

    private void sendText(String text) {
        if (text == null || text.isEmpty()) return;
        String esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        sendJson("{\"type\":\"text\",\"text\":\"" + esc + "\"}");
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

        TextView bMic = makeFabIcon("🎤", "#9ece6a");
        bMic.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleVoiceBar();
        });

        TextView bPhone = makeFabIcon("📱", "#7aa2f7");
        bPhone.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendCmd("phone_toggle");
        });

        pill.addView(bKb);
        pill.addView(bMic);
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

    private void toggleVoiceBar() {
        if (voiceVisible) {
            hideVoiceBar();
        } else {
            hideOsk();
            showVoiceBar();
        }
    }

    private void hideVoiceBar() {
        if (voiceBarView != null) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(voiceBarView.getWindowToken(), 0);
                wm.removeView(voiceBarView);
            } catch (Exception ignored) {}
            voiceBarView = null;
        }
        voiceVisible = false;
    }

    private void showVoiceBar() {
        hideVoiceBar();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(makeRounded("#F21a1b26", "#7aa2f7", 12, 2));
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));

        EditText edit = new EditText(this);
        edit.setHint("点击豆包输入法语音键说话，或直接打字…");
        edit.setHintTextColor(Color.parseColor("#565f89"));
        edit.setTextColor(Color.parseColor("#c0caf5"));
        edit.setTextSize(14);
        edit.setSingleLine(false);
        edit.setMaxLines(3);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        edit.setBackground(makeRounded("#24283b", "#414868", 8, 1));
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        elp.rightMargin = dp(8);
        bar.addView(edit, elp);

        Runnable doSend = () -> {
            String txt = edit.getText().toString();
            if (!txt.isEmpty()) {
                sendText(txt);
                edit.setText("");
                hideVoiceBar();
            }
        };

        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSend.run();
                return true;
            }
            return false;
        });

        Button bSend = makeBarBtn("发送上屏", true, v -> doSend.run());
        Button bClose = makeBarBtn("✕", false, v -> hideVoiceBar());
        bar.addView(bSend);
        bar.addView(bClose);

        // Focusable overlay so Android Doubao IME pops up immediately!
        WindowManager.LayoutParams vlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        vlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        vlp.y = dp(36);
        vlp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

        voiceBarView = bar;
        wm.addView(voiceBarView, vlp);
        voiceVisible = true;

        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    private void toggleOsk() {
        if (oskVisible) {
            hideOsk();
        } else {
            hideVoiceBar();
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

    private void showOsk(String mode) {
        hideOsk();
        oskMode = mode;
        modBtns.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        String bg = "floating".equals(mode) ? "#D91a1b26" : "#F51a1b26";
        root.setBackground(makeRounded(bg, "#7aa2f7", "floating".equals(mode) ? 14 : 0, 2));
        root.setPadding(dp(6), dp(6), dp(6), dp(8));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(4));

        bar.addView(makeBarBtn("全键盘", "docked".equals(mode), v -> showOsk("docked")));
        bar.addView(makeBarBtn("精简条", "compact".equals(mode), v -> showOsk("compact")));
        bar.addView(makeBarBtn("悬浮", "floating".equals(mode), v -> showOsk("floating")));

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(makeBarBtn("中/EN", false, v -> sendCmd("ime")));
        bar.addView(makeBarBtn("🎤 豆包语音", false, v -> toggleVoiceBar()));
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
                {{"⇧","MOD:shift"},{"z","z"},{"x","x"},{"c","c"},{"v","v"},{"b","b"},{"n","n"},{"m","m"},{",","comma"},{".","period"},{"/","slash"},{"🎤","VOICE"}},
                {{"应用","CMD:apps"},{"菜单","CMD:menu"},{"Space","space"},{"终端","CMD:terminal"},{"关窗","CMD:kill"}}
            };
        }

        for (String[][] r : rows) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (String[] k : r) {
                String label = k[0], code = k[1];
                Button b = new Button(this);
                b.setText(label);
                b.setAllCaps(false);
                b.setTextSize(12);
                b.setPadding(dp(2), dp(6), dp(2), dp(6));
                b.setMinHeight(0);
                b.setMinimumHeight(dp(36));
                b.setMinWidth(0);
                b.setMinimumWidth(0);

                boolean isSpecial = code.startsWith("MOD:") || code.startsWith("CMD:") || "VOICE".equals(code);
                b.setTextColor(Color.parseColor(isSpecial ? "#7aa2f7" : "#c0caf5"));
                b.setBackground(makeRounded(isSpecial ? "#283457" : "#24283b", isSpecial ? "#7aa2f7" : "#414868", 6, 1));

                if (code.startsWith("MOD:")) {
                    modBtns.put(code.substring(4), b);
                }

                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if ("VOICE".equals(code)) {
                        toggleVoiceBar();
                    } else if (code.startsWith("MOD:")) {
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
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(38), w);
                klp.setMargins(dp(2), dp(2), dp(2), dp(2));
                rowView.addView(b, klp);
            }
            root.addView(rowView);
        }

        int width = "floating".equals(mode)
                ? (int) (getResources().getDisplayMetrics().widthPixels * 0.88f)
                : WindowManager.LayoutParams.MATCH_PARENT;
        WindowManager.LayoutParams oskLp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        oskLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        oskLp.y = "floating".equals(mode) ? dp(14) : 0;

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
        b.setBackground(makeRounded(active ? "#7aa2f7" : "#24283b", "#7aa2f7", 6, 1));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(32));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMargins(dp(3), 0, dp(3), 0);
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
        b.setBackground(makeRounded(on ? "#7aa2f7" : "#283457", "#7aa2f7", 6, 1));
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
        hideVoiceBar();
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
