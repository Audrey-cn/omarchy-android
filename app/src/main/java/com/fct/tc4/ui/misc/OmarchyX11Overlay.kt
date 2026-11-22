package com.fct.tc4.ui.misc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.net.Socket
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native in-process Omarchy overlay attached directly to com.termux.x11.MainActivity.
 * - Zero SYSTEM_ALERT_WINDOW permission required.
 * - Draggable floating pill (⌨ | 🎤 | 📱) with auto-hide when floating keyboard opens.
 * - Ergonomic 0.70x floating keyboard (workspace 1-5, opacity slider, drag handle, close).
 * - Completely invisible 1x1px EditText anchor for Android Doubao voice/text IME passthrough.
 * - Automatic Sway output resize sync on startup & Portrait/Landscape orientation changes.
 */
object OmarchyX11Overlay {
    private val controllers = WeakHashMap<Activity, Controller>()

    fun register(app: Application) {
        // Ensure clean Termux:X11 defaults on first launch (disable ugly built-in EKBar)
        try {
            val prefs = app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("omarchy_defaults_v1", false)) {
                prefs.edit()
                    .putBoolean("showAdditionalKbd", false)
                    .putBoolean("additionalKbdVisible", false)
                    .putBoolean("fullscreen", true)
                    .putBoolean("hideCutout", true)
                    .putBoolean("keepScreenOn", true)
                    .putString("displayResolutionMode", "native")
                    .putBoolean("omarchy_defaults_v1", true)
                    .apply()
            }
        } catch (_: Exception) {}

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name == "com.termux.x11.MainActivity") {
                    val ctrl = controllers.getOrPut(activity) { Controller(activity) }
                    ctrl.attachIfNeeded()
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                controllers.remove(activity)?.detach()
            }
        })
    }

    private class Controller(private val activity: Activity) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val netExec = Executors.newSingleThreadExecutor()

        private var attached = false
        private var overlayHost: FrameLayout? = null
        private var fabView: View? = null
        private var oskView: View? = null
        private var imeAnchor: EditText? = null
        private var autoSendTask: Runnable? = null
        private var resizeTask: Runnable? = null

        private var oskVisible = false
        private var imeActive = false
        private var oskAlpha = 0.78f
        private var lastW = 0
        private var lastH = 0

        private var modSuper = false
        private var modCtrl = false
        private var modAlt = false
        private var modShift = false
        private val modBtns = HashMap<String, Button>()

        private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w >= 300 && h >= 300 && (w != lastW || h != lastH)) {
                lastW = w
                lastH = h
                scheduleResize(w, h)
                // Keep floating keyboard width adapted after rotation
                if (oskVisible) {
                    hideOsk()
                    showOsk()
                }
            }
        }

        fun attachIfNeeded() {
            if (attached) return
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            attached = true

            val host = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            content.addView(host)
            overlayHost = host

            buildFab(host)
            content.addOnLayoutChangeListener(layoutListener)

            // Sync initial screen size a few times while Sway + deck-bridge boot
            listOf(1200L, 3000L, 5500L).forEach { delay ->
                mainHandler.postDelayed({
                    val w = content.width
                    val h = content.height
                    if (w >= 300 && h >= 300) {
                        lastW = w
                        lastH = h
                        sendResize(w, h)
                    }
                }, delay)
            }
        }

        fun detach() {
            autoSendTask?.let { mainHandler.removeCallbacks(it) }
            resizeTask?.let { mainHandler.removeCallbacks(it) }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content?.removeOnLayoutChangeListener(layoutListener)
            overlayHost?.let { content?.removeView(it) }
            overlayHost = null
            fabView = null
            oskView = null
            imeAnchor = null
            attached = false
            netExec.shutdownNow()
        }

        private fun scheduleResize(w: Int, h: Int) {
            resizeTask?.let { mainHandler.removeCallbacks(it) }
            resizeTask = Runnable { sendResize(w, h) }.also {
                mainHandler.postDelayed(it, 280L)
            }
        }

        private fun sendResize(w: Int, h: Int) {
            sendJson("""{"type":"resize","w":$w,"h":$h}""")
        }

        private fun sendJson(jsonLine: String) {
            netExec.execute {
                try {
                    Socket("127.0.0.1", 18901).use { s ->
                        val os = s.getOutputStream()
                        os.write((jsonLine + "\n").toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        private fun sendCmd(cmd: String) {
            sendJson("""{"type":"cmd","cmd":"$cmd"}""")
        }

        private fun sendKey(key: String) {
            val j = """{"type":"key","key":"$key","super":$modSuper,"ctrl":$modCtrl,"alt":$modAlt,"shift":$modShift}"""
            sendJson(j)
            if (modSuper || modCtrl || modAlt || modShift) {
                modSuper = false; modCtrl = false; modAlt = false; modShift = false
                refreshModButtons()
            }
        }

        private fun sendText(text: String) {
            if (text.isEmpty()) return
            val esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            sendJson("""{"type":"text","text":"$esc"}""")
        }

        private fun toggleOrientation() {
            val isLandscape = activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        private fun buildFab(host: FrameLayout) {
            val pill = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRounded("#E61a1b26", "#7aa2f7", 24, 2)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                elevation = dp(10).toFloat()
            }

            val bKb = makeFabIcon("⌨", "#c0caf5").apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showOsk()
                }
            }
            val bIme = makeFabIcon("🎤", "#9ece6a").apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    triggerAndroidIme()
                }
            }
            val bPhone = makeFabIcon("📱", "#7aa2f7").apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleOrientation()
                }
            }

            pill.addView(bKb)
            pill.addView(bIme)
            pill.addView(bPhone)

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(14)
                bottomMargin = dp(24)
            }

            var startX = 0f; var startY = 0f
            var origTx = 0f; var origTy = 0f
            var moved = false
            pill.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY
                        origTx = v.translationX; origTy = v.translationY
                        moved = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (abs(dx) > 12 || abs(dy) > 12) {
                            moved = true
                            v.translationX = origTx + dx
                            v.translationY = origTy + dy
                        }
                        moved
                    }
                    else -> false
                }
            }

            host.addView(pill, lp)
            fabView = pill
        }

        private fun makeFabIcon(text: String, colorHex: String): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = 17f
                setTextColor(Color.parseColor(colorHex))
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }

        private fun hideIme() {
            autoSendTask?.let { mainHandler.removeCallbacks(it) }
            imeAnchor?.let { ed ->
                try {
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(ed.windowToken, 0)
                    overlayHost?.removeView(ed)
                } catch (_: Exception) {}
            }
            imeAnchor = null
            imeActive = false
        }

        private fun triggerAndroidIme() {
            if (imeActive) {
                hideIme()
                return
            }
            hideOsk()
            val host = overlayHost ?: return

            // Completely invisible 1x1px anchor so ONLY Android Doubao IME pops up (ZERO extra bar!)
            val edit = EditText(activity).apply {
                alpha = 0f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.TRANSPARENT)
                isCursorVisible = false
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEND
                isFocusable = true
                isFocusableInTouchMode = true
            }

            val flushNow = Runnable {
                autoSendTask?.let { mainHandler.removeCallbacks(it) }
                val ed = edit.text ?: return@Runnable
                if (ed.isEmpty()) return@Runnable
                if (BaseInputConnection.getComposingSpanStart(ed) != -1) return@Runnable
                val txt = ed.toString()
                if (txt.isNotEmpty()) {
                    sendText(txt)
                    edit.setText("")
                }
            }

            edit.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (edit.text.isNullOrEmpty()) {
                        sendKey("BackSpace")
                        return@setOnKeyListener true
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hideIme()
                    return@setOnKeyListener true
                }
                false
            }

            edit.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    flushNow.run()
                    sendKey("Return")
                    true
                } else false
            }

            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    autoSendTask?.let { mainHandler.removeCallbacks(it) }
                    if (!s.isNullOrEmpty()) {
                        val composing = BaseInputConnection.getComposingSpanStart(s) != -1
                        autoSendTask = flushNow
                        mainHandler.postDelayed(flushNow, if (composing) 900L else 180L)
                    }
                }
            })

            host.addView(edit, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.START))
            imeAnchor = edit
            imeActive = true

            edit.requestFocus()
            edit.postDelayed({
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 90L)
        }

        private fun hideOsk() {
            oskView?.let { overlayHost?.removeView(it) }
            oskView = null
            oskVisible = false
            fabView?.visibility = View.VISIBLE
        }

        private fun bgWithAlpha(alpha: Float): Int {
            val a = max(36, min(242, (alpha * 255f).roundToInt()))
            return Color.argb(a, 0x1a, 0x1b, 0x26)
        }

        private fun showOsk() {
            if (oskVisible) return
            val host = overlayHost ?: return
            hideIme()
            fabView?.visibility = View.GONE
            modBtns.clear()

            val rootBg = GradientDrawable().apply {
                setColor(bgWithAlpha(oskAlpha))
                setStroke(dp(2), Color.parseColor("#7aa2f7"))
                cornerRadius = dp(12).toFloat()
            }

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rootBg
                setPadding(dp(7), dp(6), dp(7), dp(7))
                elevation = dp(12).toFloat()
                alpha = max(0.38f, oskAlpha + 0.14f)
            }

            // Top bar: [1][2][3][4][5]  [opacity]  [⠿ 拖动]  [✕]
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(5))
            }

            for (ws in 1..5) {
                val w = ws.toString()
                val wb = makeBarBtn(w, false) { sendCmd("ws_$w") }
                wb.layoutParams = LinearLayout.LayoutParams(dp(30), dp(28)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                wb.setPadding(0, 0, 0, 0)
                bar.addView(wb)
            }

            bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

            val alphaBar = SeekBar(activity).apply {
                max = 75
                progress = ((oskAlpha - 0.20f) * 100f).roundToInt()
                layoutParams = LinearLayout.LayoutParams(dp(86), dp(26)).apply {
                    setMargins(dp(4), 0, dp(6), 0)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        oskAlpha = 0.20f + (progress / 100f)
                        rootBg.setColor(bgWithAlpha(oskAlpha))
                        root.alpha = max(0.38f, oskAlpha + 0.14f)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            bar.addView(alphaBar)

            val dragHandle = TextView(activity).apply {
                text = "⠿ 拖动"
                textSize = 11f
                setTextColor(Color.parseColor("#7aa2f7"))
                gravity = Gravity.CENTER
                background = makeRounded("#CC283457", "#7aa2f7", 6, 1)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)
                ).apply { setMargins(dp(2), 0, dp(4), 0) }
            }
            var dxStart = 0f; var dyStart = 0f
            var oTx = 0f; var oTy = 0f
            dragHandle.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dxStart = e.rawX; dyStart = e.rawY
                        oTx = root.translationX; oTy = root.translationY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        root.translationX = oTx + (e.rawX - dxStart)
                        root.translationY = oTy + (e.rawY - dyStart)
                        true
                    }
                    else -> false
                }
            }
            bar.addView(dragHandle)

            val bClose = makeBarBtn("✕", false) { hideOsk() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(28)).apply {
                    setMargins(dp(2), 0, 0, 0)
                }
                setPadding(0, 0, 0, 0)
            }
            bar.addView(bClose)
            root.addView(bar)

            val rows = arrayOf(
                arrayOf(Triple("Esc","Escape",1.1f), Triple("Tab","Tab",1.1f), Triple("Super","MOD:super",1.4f), Triple("Ctrl","MOD:ctrl",1.2f), Triple("Alt","MOD:alt",1.1f), Triple("←","Left",1.0f), Triple("↓","Down",1.0f), Triple("↑","Up",1.0f), Triple("→","Right",1.0f), Triple("⌫","BackSpace",1.4f)),
                arrayOf(Triple("1","1",1f), Triple("2","2",1f), Triple("3","3",1f), Triple("4","4",1f), Triple("5","5",1f), Triple("6","6",1f), Triple("7","7",1f), Triple("8","8",1f), Triple("9","9",1f), Triple("0","0",1f), Triple("-","minus",1f), Triple("=","equal",1f)),
                arrayOf(Triple("q","q",1f), Triple("w","w",1f), Triple("e","e",1f), Triple("r","r",1f), Triple("t","t",1f), Triple("y","y",1f), Triple("u","u",1f), Triple("i","i",1f), Triple("o","o",1f), Triple("p","p",1f), Triple("[","bracketleft",1f), Triple("]","bracketright",1f)),
                arrayOf(Triple("a","a",1f), Triple("s","s",1f), Triple("d","d",1f), Triple("f","f",1f), Triple("g","g",1f), Triple("h","h",1f), Triple("j","j",1f), Triple("k","k",1f), Triple("l","l",1f), Triple(";","semicolon",1f), Triple("'","apostrophe",1f), Triple("⏎","Return",1.5f)),
                arrayOf(Triple("⇧","MOD:shift",1.4f), Triple("z","z",1f), Triple("x","x",1f), Triple("c","c",1f), Triple("v","v",1f), Triple("b","b",1f), Triple("n","n",1f), Triple("m","m",1f), Triple(",","comma",1f), Triple(".","period",1f), Triple("/","slash",1f), Triple("中","CMD:ime",1.2f)),
                arrayOf(Triple("应用","CMD:apps",1.3f), Triple("菜单","CMD:menu",1.3f), Triple("Space","space",3.4f), Triple("终端","CMD:terminal",1.3f), Triple("关窗","CMD:kill",1.3f))
            )

            val keyH = dp((36f * 0.70f).roundToInt())
            for (r in rows) {
                val rowView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for ((label, code, weight) in r) {
                    val isSpecial = code.startsWith("MOD:") || code.startsWith("CMD:")
                    val b = Button(activity).apply {
                        text = label
                        isAllCaps = false
                        textSize = 11f
                        setPadding(0, 0, 0, 0)
                        minHeight = 0; minimumHeight = keyH
                        minWidth = 0; minimumWidth = 0
                        setTextColor(Color.parseColor(if (isSpecial) "#7aa2f7" else "#c0caf5"))
                        background = makeRounded(
                            if (isSpecial) "#CC283457" else "#CC24283b",
                            if (isSpecial) "#7aa2f7" else "#414868",
                            6, 1
                        )
                        setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            when {
                                code.startsWith("MOD:") -> {
                                    when (code.substring(4)) {
                                        "super" -> modSuper = !modSuper
                                        "ctrl" -> modCtrl = !modCtrl
                                        "alt" -> modAlt = !modAlt
                                        "shift" -> modShift = !modShift
                                    }
                                    refreshModButtons()
                                }
                                code.startsWith("CMD:") -> sendCmd(code.substring(4))
                                else -> sendKey(code)
                            }
                        }
                    }
                    if (code.startsWith("MOD:")) modBtns[code.substring(4)] = b
                    val klp = LinearLayout.LayoutParams(0, keyH, weight).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                    rowView.addView(b, klp)
                }
                root.addView(rowView)
            }

            val screenW = activity.resources.displayMetrics.widthPixels
            val screenH = activity.resources.displayMetrics.heightPixels
            val width = if (screenW > screenH) {
                min(screenW - dp(16), (screenW * 0.56f).roundToInt())
            } else {
                min(screenW - dp(12), (screenW * 0.94f).roundToInt())
            }

            val olp = FrameLayout.LayoutParams(
                width,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(14) }

            host.addView(root, olp)
            oskView = root
            oskVisible = true
        }

        private fun makeBarBtn(label: String, active: Boolean, onClick: () -> Unit): Button =
            Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                setTextColor(Color.parseColor(if (active) "#1a1b26" else "#c0caf5"))
                background = makeRounded(if (active) "#7aa2f7" else "#CC24283b", "#7aa2f7", 6, 1)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                minHeight = 0; minimumHeight = dp(28)
                minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }

        private fun refreshModButtons() {
            setModStyle("super", modSuper)
            setModStyle("ctrl", modCtrl)
            setModStyle("alt", modAlt)
            setModStyle("shift", modShift)
        }

        private fun setModStyle(name: String, on: Boolean) {
            val b = modBtns[name] ?: return
            b.setTextColor(Color.parseColor(if (on) "#1a1b26" else "#7aa2f7"))
            b.background = makeRounded(if (on) "#7aa2f7" else "#CC283457", "#7aa2f7", 6, 1)
        }

        private fun makeRounded(bgHex: String, strokeHex: String, radiusDp: Int, strokeDp: Int): GradientDrawable =
            GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                setStroke(dp(strokeDp), Color.parseColor(strokeHex))
                cornerRadius = dp(radiusDp).toFloat()
            }

        private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).roundToInt()
    }
}
