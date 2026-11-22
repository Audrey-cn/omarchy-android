package com.omarchy.deck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
    private WindowManager.LayoutParams oskLp;

    private String oskMode = "docked"; // docked | compact | floating
    private boolean oskVisible = false;
    private volatile boolean micStreaming = true;
    private volatile boolean running = true;

    private boolean modSuper = false, modCtrl = false, modAlt = false, modShift = false;
    private final Map<String, Button> modBtns = new HashMap<>();
    private TextView fabMicBtn;

    private final ExecutorService netExec = Executors.newSingleThreadExecutor();
    private Thread micThread;

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showFab();
        startMicStreamer();
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
                .setContentText("原生虚拟键盘悬浮球 + 手机麦克风直通 (16kHz)")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    private void startMicStreamer() {
        micThread = new Thread(() -> {
            int rate = 16000;
            int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 3200);
            byte[] buf = new byte[1600];

            while (running) {
                if (!micStreaming) {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                    continue;
                }
                AudioRecord rec = null;
                Socket sock = null;
                try {
                    rec = new AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        Thread.sleep(1000);
                        continue;
                    }
                    sock = new Socket("127.0.0.1", 18900);
                    OutputStream out = sock.getOutputStream();
                    rec.startRecording();
                    while (running && micStreaming) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                } finally {
                    if (rec != null) {
                        try { rec.stop(); } catch (Exception ignored) {}
                        try { rec.release(); } catch (Exception ignored) {}
                    }
                    if (sock != null) {
                        try { sock.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "OmarchyMicStreamer");
        micThread.start();
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

        fabMicBtn = makeFabIcon("🎤", "#9ece6a");
        fabMicBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendCmd("voice");
        });
        fabMicBtn.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            micStreaming = !micStreaming;
            fabMicBtn.setTextColor(Color.parseColor(micStreaming ? "#9ece6a" : "#f7768e"));
            return true;
        });

        TextView bPhone = makeFabIcon("📱", "#7aa2f7");
        bPhone.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendCmd("phone_toggle");
        });

        pill.addView(bKb);
        pill.addView(fabMicBtn);
        pill.addView(bPhone);

        // Allow dragging the floating pill anywhere on screen
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

    private void showOsk(String mode) {
        hideOsk();
        oskMode = mode;
        modBtns.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        String bg = "floating".equals(mode) ? "#D91a1b26" : "#F51a1b26";
        root.setBackground(makeRounded(bg, "#7aa2f7", "floating".equals(mode) ? 14 : 0, 2));
        root.setPadding(dp(6), dp(6), dp(6), dp(8));

        // Top toolbar
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
        bar.addView(makeBarBtn("🎤 语音", false, v -> sendCmd("voice")));
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
                {{"⇧","MOD:shift"},{"z","z"},{"x","x"},{"c","c"},{"v","v"},{"b","b"},{"n","n"},{"m","m"},{",","comma"},{".","period"},{"/","slash"},{"🎤","CMD:voice"}},
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

                boolean isSpecial = code.startsWith("MOD:") || code.startsWith("CMD:");
                b.setTextColor(Color.parseColor(isSpecial ? "#7aa2f7" : "#c0caf5"));
                b.setBackground(makeRounded(isSpecial ? "#283457" : "#24283b", isSpecial ? "#7aa2f7" : "#414868", 6, 1));

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
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(38), w);
                klp.setMargins(dp(2), dp(2), dp(2), dp(2));
                rowView.addView(b, klp);
            }
            root.addView(rowView);
        }

        int width = "floating".equals(mode)
                ? (int) (getResources().getDisplayMetrics().widthPixels * 0.88f)
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
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
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
        running = false;
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
