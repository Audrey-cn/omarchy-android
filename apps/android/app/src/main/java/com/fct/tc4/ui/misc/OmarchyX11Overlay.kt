package com.fct.tc4.ui.misc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native in-process Omarchy overlay attached directly to com.termux.x11.MainActivity.
 * - Zero SYSTEM_ALERT_WINDOW permission required.
 * - Smooth animated edge-snapping floating handle (stays collapsed at edge even when OSK is open).
 * - Custom vector icons (Keyboard, Mic, Orientation Lock, Settings Gear) — zero raw emojis.
 * - Refined floating 0.70x keyboard: custom thin Tokyo Night opacity slider + iOS/Android-style
 *   bottom drag pill bar (zero "拖动" button clutter).
 * - Interactive 4-step arrow-guided spotlight onboarding (1.5x Monitor scale, Edge Handle,
 *   Super+K keybindings, Doubao IME & system permissions, skippable anytime).
 * - Hard-locked Portrait / Landscape orientation + Portrait soft-keyboard viewport shrink.
 */
object OmarchyX11Overlay {
    private val controllers = WeakHashMap<Activity, Controller>()
    private const val DOUBAO_PKG = "com.bytedance.android.doubaoime"
    private const val DOUBAO_URL = "https://shurufa.doubao.com/"

    fun register(app: Application) {
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

    private enum class VectorKind {
        KEYBOARD, MIC, ORIENT_LAND, ORIENT_PORT, SETTINGS, CHEVRON_LEFT, CHEVRON_RIGHT
    }

    private class VectorIconView(
        context: Context,
        var kind: VectorKind,
        var strokeColor: Int,
        private val sizeDp: Int = 22
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val px = (sizeDp * resources.displayMetrics.density).roundToInt()
            setMeasuredDimension(px, px)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val u = min(w, h) / 24f
            paint.color = strokeColor
            paint.strokeWidth = 1.9f * u
            fillPaint.color = strokeColor

            when (kind) {
                VectorKind.KEYBOARD -> {
                    canvas.drawRoundRect(RectF(2.5f * u, 5.5f * u, 21.5f * u, 18.5f * u), 2.5f * u, 2.5f * u, paint)
                    val cols = floatArrayOf(6f, 9.5f, 13f, 16.5f)
                    for (cx in cols) {
                        canvas.drawCircle(cx * u, 9.5f * u, 0.9f * u, fillPaint)
                        canvas.drawCircle(cx * u, 12.5f * u, 0.9f * u, fillPaint)
                    }
                    canvas.drawLine(7.5f * u, 15.5f * u, 16.5f * u, 15.5f * u, paint)
                }
                VectorKind.MIC -> {
                    canvas.drawRoundRect(RectF(9f * u, 3f * u, 15f * u, 13f * u), 3f * u, 3f * u, paint)
                    canvas.drawArc(RectF(6f * u, 8.5f * u, 18f * u, 16.5f * u), 0f, 180f, false, paint)
                    canvas.drawLine(12f * u, 16.5f * u, 12f * u, 20.5f * u, paint)
                    canvas.drawLine(9f * u, 20.5f * u, 15f * u, 20.5f * u, paint)
                }
                VectorKind.ORIENT_LAND -> {
                    canvas.drawRoundRect(RectF(3f * u, 6.5f * u, 21f * u, 17.5f * u), 2.2f * u, 2.2f * u, paint)
                    canvas.drawLine(18f * u, 10.5f * u, 18f * u, 13.5f * u, paint)
                }
                VectorKind.ORIENT_PORT -> {
                    canvas.drawRoundRect(RectF(6.5f * u, 3f * u, 17.5f * u, 21f * u), 2.2f * u, 2.2f * u, paint)
                    canvas.drawLine(10.5f * u, 18f * u, 13.5f * u, 18f * u, paint)
                }
                VectorKind.SETTINGS -> {
                    canvas.drawCircle(12f * u, 12f * u, 3.2f * u, paint)
                    canvas.drawCircle(12f * u, 12f * u, 6.6f * u, paint)
                    for (i in 0 until 6) {
                        val rad = Math.toRadians(i * 60.0)
                        val x1 = 12f * u + (6.6f * u) * cos(rad).toFloat()
                        val y1 = 12f * u + (6.6f * u) * sin(rad).toFloat()
                        val x2 = 12f * u + (9.2f * u) * cos(rad).toFloat()
                        val y2 = 12f * u + (9.2f * u) * sin(rad).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
                VectorKind.CHEVRON_LEFT -> {
                    val p = Path().apply {
                        moveTo(14.5f * u, 6.5f * u)
                        lineTo(9f * u, 12f * u)
                        lineTo(14.5f * u, 17.5f * u)
                    }
                    canvas.drawPath(p, paint)
                }
                VectorKind.CHEVRON_RIGHT -> {
                    val p = Path().apply {
                        moveTo(9.5f * u, 6.5f * u)
                        lineTo(15f * u, 12f * u)
                        lineTo(9.5f * u, 17.5f * u)
                    }
                    canvas.drawPath(p, paint)
                }
            }
        }
    }

    /** Draws a glowing spotlight ring + curved directional arrow for interactive onboarding. */
    private class GuideArrowOverlay(context: Context) : View(context) {
        var targetX = 0f
        var targetY = 0f
        var fromX = 0f
        var fromY = 0f
        var showArrow = false
        var ringRadius = 26f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#7aa2f7")
            strokeWidth = 6f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#557aa2f7")
            strokeWidth = 18f
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#9ece6a")
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!showArrow) return
            canvas.drawCircle(targetX, targetY, ringRadius, glowPaint)
            canvas.drawCircle(targetX, targetY, ringRadius, ringPaint)

            // Curved arrow from card toward target ring
            val ctrlX = (fromX + targetX) * 0.5f
            val ctrlY = min(fromY, targetY) - 40f
            val path = Path().apply {
                moveTo(fromX, fromY)
                quadTo(ctrlX, ctrlY, targetX, targetY + if (targetY < fromY) ringRadius + 10f else -(ringRadius + 10f))
            }
            canvas.drawPath(path, arrowPaint)

            val endY = targetY + if (targetY < fromY) ringRadius + 10f else -(ringRadius + 10f)
            val angle = atan2((endY - ctrlY).toDouble(), (targetX - ctrlX).toDouble())
            val headLen = 24f
            val a1 = angle - Math.toRadians(30.0)
            val a2 = angle + Math.toRadians(30.0)
            canvas.drawLine(
                targetX, endY,
                targetX - (headLen * cos(a1)).toFloat(),
                endY - (headLen * sin(a1)).toFloat(),
                arrowPaint
            )
            canvas.drawLine(
                targetX, endY,
                targetX - (headLen * cos(a2)).toFloat(),
                endY - (headLen * sin(a2)).toFloat(),
                arrowPaint
            )
        }
    }

    private class ImeBridgeEditText(
        context: Context,
        private val onCommit: (String) -> Unit,
        private val onBackspace: () -> Unit,
        private val onEnter: () -> Unit,
        private val onDismiss: () -> Unit
    ) : EditText(context) {

        init {
            alpha = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                onDismiss()
                return false
            }
            return super.onKeyPreIme(keyCode, event)
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val base = super.onCreateInputConnection(outAttrs) ?: return null
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : InputConnectionWrapper(base, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val s = text?.toString() ?: ""
                    if (s.isNotEmpty()) {
                        onCommit(s)
                        post { setText("") }
                    }
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    return super.setComposingText(text, newCursorPosition)
                }

                override fun finishComposingText(): Boolean {
                    val ed = this@ImeBridgeEditText.text
                    val remaining = ed?.toString() ?: ""
                    val res = super.finishComposingText()
                    if (remaining.isNotEmpty()) {
                        onCommit(remaining)
                        post { setText("") }
                    }
                    return res
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    val ed = this@ImeBridgeEditText.text
                    val composing = ed != null && BaseInputConnection.getComposingSpanStart(ed) != -1
                    if (!composing && (ed == null || ed.isEmpty()) && beforeLength > 0) {
                        repeat(beforeLength) { onBackspace() }
                        return true
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                            val ed = this@ImeBridgeEditText.text
                            val composing = ed != null && BaseInputConnection.getComposingSpanStart(ed) != -1
                            if (!composing && (ed == null || ed.isEmpty())) {
                                onBackspace()
                                return true
                            }
                        } else if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                            val ed = this@ImeBridgeEditText.text
                            val remaining = ed?.toString() ?: ""
                            if (remaining.isNotEmpty()) {
                                onCommit(remaining)
                                post { setText("") }
                            }
                            onEnter()
                            return true
                        }
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }

    private class Controller(private val activity: Activity) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val netExec = Executors.newSingleThreadExecutor()
        private val prefs = activity.getSharedPreferences("omarchy_overlay_prefs", Context.MODE_PRIVATE)

        private var attached = false
        private var overlayHost: FrameLayout? = null
        private var nightOverlay: View? = null
        private var edgeTabView: View? = null
        private var expandedDockView: View? = null
        private var quickMenuOverlay: View? = null
        private var onboardingOverlay: View? = null
        private var oskView: View? = null
        private var imeAnchor: EditText? = null

        private var resizeTask: Runnable? = null
        private var autoCollapseTask: Runnable? = null

        private var dockExpanded = false
        private var dockedRight = true
        private var dockYFraction = 0.65f
        private var oskVisible = false
        private var imeActive = false
        private var oskAlpha = 0.78f

        private var lastFullW = 0
        private var lastFullH = 0
        private var lastImeH = 0

        private var modSuper = false
        private var modCtrl = false
        private var modAlt = false
        private var modShift = false
        private val modBtns = HashMap<String, Button>()

        private val queuePollTask = object : Runnable {
            override fun run() {
                if (!attached) return
                pollQueue()
                mainHandler.postDelayed(this, 300L)
            }
        }

        private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            updateViewportForIme()
        }

        private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w >= 300 && h >= 300 && (w != lastFullW || h != lastFullH)) {
                lastFullW = w
                lastFullH = h
                positionEdgeTab()
                if (dockExpanded) positionExpandedDock()
                scheduleResize(w, max(320, h - lastImeH))
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

            val savedOrient = prefs.getString("locked_orientation", "landscape") ?: "landscape"
            applyLockedOrientation(savedOrient)

            dockedRight = prefs.getBoolean("dock_right", true)
            dockYFraction = prefs.getFloat("dock_y_frac", 0.65f).coerceIn(0.15f, 0.85f)

            val host = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            content.addView(host)
            overlayHost = host

            val night = View(activity).apply {
                setBackgroundColor(Color.argb(0x3C, 0xFF, 0x95, 0x00))
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            host.addView(
                night,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            nightOverlay = night

            buildEdgeTab(host)
            content.addOnLayoutChangeListener(layoutListener)
            content.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                content.setOnApplyWindowInsetsListener { v, insets ->
                    val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                    applyImeInset(imeBottom)
                    v.onApplyWindowInsets(insets)
                }
            }

            mainHandler.postDelayed(queuePollTask, 500L)

            listOf(1200L, 3000L, 5500L).forEach { delay ->
                mainHandler.postDelayed({
                    val w = content.width
                    val h = content.height
                    if (w >= 300 && h >= 300) {
                        lastFullW = w
                        lastFullH = h
                        positionEdgeTab()
                        sendResize(w, max(320, h - lastImeH))
                    }
                }, delay)
            }

            if (!prefs.getBoolean("onboarding_v2_done", false)) {
                mainHandler.postDelayed({ showOnboardingStep(0) }, 2200L)
            }
        }

        fun detach() {
            mainHandler.removeCallbacks(queuePollTask)
            resizeTask?.let { mainHandler.removeCallbacks(it) }
            autoCollapseTask?.let { mainHandler.removeCallbacks(it) }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content?.removeOnLayoutChangeListener(layoutListener)
            try { content?.viewTreeObserver?.removeOnGlobalLayoutListener(globalLayoutListener) } catch (_: Exception) {}
            overlayHost?.let { content?.removeView(it) }
            overlayHost = null
            nightOverlay = null
            edgeTabView = null
            expandedDockView = null
            quickMenuOverlay = null
            onboardingOverlay = null
            oskView = null
            imeAnchor = null
            attached = false
            netExec.shutdownNow()
        }

        private fun updateViewportForIme() {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val rect = Rect()
            content.getWindowVisibleDisplayFrame(rect)
            val fullH = content.height
            if (fullH < 300) return
            val diff = fullH - rect.bottom
            val imeH = if (diff > dp(120)) diff else 0
            applyImeInset(imeH)
        }

        private fun applyImeInset(rawImeH: Int) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val fullW = content.width
            val fullH = content.height
            if (fullW < 300 || fullH < 300) return
            val imeH = if (rawImeH > dp(100)) rawImeH else 0
            if (imeH == lastImeH) return
            lastImeH = imeH

            val x11Root = content.getChildAt(0)
            if (x11Root != null && x11Root !== overlayHost) {
                val lp = x11Root.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.bottomMargin = imeH
                    x11Root.layoutParams = lp
                }
            }
            scheduleResize(fullW, max(320, fullH - imeH))
        }

        private fun pollQueue() {
            try {
                val qdir = File(activity.filesDir, "tmp/omarchy-browser-queue")
                val files = qdir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
                for (f in files.sortedBy { it.name }) {
                    try {
                        val txt = f.readText(Charsets.UTF_8)
                        f.delete()
                        val obj = JSONObject(txt)
                        when (obj.optString("action")) {
                            "open_url" -> {
                                val url = obj.optString("url")
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            }
                            "night_mode" -> {
                                val on = obj.optBoolean("enabled", false)
                                nightOverlay?.visibility = if (on) View.VISIBLE else View.GONE
                            }
                            "keep_awake" -> {
                                val on = obj.optBoolean("enabled", true)
                                if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    } catch (_: Exception) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        private fun scheduleResize(w: Int, h: Int) {
            resizeTask?.let { mainHandler.removeCallbacks(it) }
            resizeTask = Runnable { sendResize(w, h) }.also {
                mainHandler.postDelayed(it, 200L)
            }
        }

        private fun sendResize(w: Int, h: Int) = sendJson("""{"type":"resize","w":$w,"h":$h}""")

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

        private fun sendCmd(cmd: String) = sendJson("""{"type":"cmd","cmd":"$cmd"}""")

        private fun sendKey(key: String) {
            sendJson("""{"type":"key","key":"$key","super":$modSuper,"ctrl":$modCtrl,"alt":$modAlt,"shift":$modShift}""")
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

        private fun isLandscapeNow(): Boolean =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        private fun applyLockedOrientation(mode: String) {
            val x11Prefs = activity.getSharedPreferences("${activity.packageName}_preferences", Context.MODE_PRIVATE)
            x11Prefs.edit().putString("forceOrientation", mode).apply()
            prefs.edit().putString("locked_orientation", mode).apply()
            activity.requestedOrientation = if (mode == "portrait") {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        private fun toggleOrientationLock() {
            val nextMode = if (isLandscapeNow()) "portrait" else "landscape"
            applyLockedOrientation(nextMode)
            collapseDock()
        }

        private fun isDoubaoInstalled(): Boolean = try {
            activity.packageManager.getPackageInfo(DOUBAO_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }

        private fun hasAllFilesPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

        private fun isBatteryUnrestricted(): Boolean {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return pm.isIgnoringBatteryOptimizations(activity.packageName)
        }

        private fun openInputMethodPicker() {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        // ==================== Edge-Snapping Collapsed Tab (Smooth Spring) ====================

        private fun makeEdgeTabBg(rightEdge: Boolean): GradientDrawable {
            val r = dp(16).toFloat()
            return GradientDrawable().apply {
                setColor(Color.parseColor("#B81a1b26"))
                setStroke(dp(1), Color.parseColor("#997aa2f7"))
                cornerRadii = if (rightEdge) {
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                } else {
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
            }
        }

        private fun buildEdgeTab(host: FrameLayout) {
            val tab = FrameLayout(activity).apply {
                background = makeEdgeTabBg(dockedRight)
                elevation = dp(14).toFloat()
                alpha = 0.72f
            }
            val chevron = VectorIconView(
                activity,
                if (dockedRight) VectorKind.CHEVRON_LEFT else VectorKind.CHEVRON_RIGHT,
                Color.parseColor("#7aa2f7"),
                16
            )
            tab.addView(chevron, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))

            var downRawX = 0f; var downRawY = 0f
            var origX = 0f; var origY = 0f
            var moved = false

            tab.setOnTouchListener { v, e ->
                val parentW = host.width.coerceAtLeast(activity.resources.displayMetrics.widthPixels)
                val parentH = host.height.coerceAtLeast(activity.resources.displayMetrics.heightPixels)
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        origX = v.x; origY = v.y
                        moved = false
                        v.animate().scaleX(1.08f).scaleY(1.08f).alpha(1f).setDuration(100L).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            moved = true
                            v.x = (origX + dx).coerceIn(0f, (parentW - v.width).toFloat())
                            v.y = (origY + dy).coerceIn(dp(40).toFloat(), (parentH - v.height - dp(24)).toFloat())
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            v.scaleX = 1f; v.scaleY = 1f
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            expandDock()
                        } else {
                            dockedRight = (v.x + v.width / 2f) >= parentW / 2f
                            dockYFraction = (v.y / parentH.toFloat()).coerceIn(0.12f, 0.85f)
                            prefs.edit()
                                .putBoolean("dock_right", dockedRight)
                                .putFloat("dock_y_frac", dockYFraction)
                                .apply()
                            v.background = makeEdgeTabBg(dockedRight)
                            chevron.kind = if (dockedRight) VectorKind.CHEVRON_LEFT else VectorKind.CHEVRON_RIGHT
                            chevron.invalidate()
                            val targetX = if (dockedRight) (parentW - v.width).toFloat() else 0f
                            v.animate()
                                .x(targetX)
                                .scaleX(1f).scaleY(1f)
                                .alpha(0.72f)
                                .setInterpolator(OvershootInterpolator(0.75f))
                                .setDuration(240L)
                                .start()
                        }
                        true
                    }
                    else -> false
                }
            }

            host.addView(tab, FrameLayout.LayoutParams(dp(22), dp(52)))
            edgeTabView = tab
            tab.post { positionEdgeTab() }
        }

        private fun positionEdgeTab() {
            val host = overlayHost ?: return
            val tab = edgeTabView ?: return
            val w = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val h = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val tw = tab.width.takeIf { it > 0 } ?: dp(22)
            val th = tab.height.takeIf { it > 0 } ?: dp(52)
            tab.x = if (dockedRight) (w - tw).toFloat() else 0f
            tab.y = (h * dockYFraction).coerceIn(dp(40).toFloat(), (h - th - dp(24)).toFloat())
        }

        // ==================== Expanded Floating Dock (Smooth Slide/Scale) ====================

        private fun expandDock() {
            val host = overlayHost ?: return
            if (dockExpanded) return
            dockExpanded = true
            edgeTabView?.animate()?.alpha(0f)?.setDuration(120L)?.withEndAction {
                if (dockExpanded) edgeTabView?.visibility = View.GONE
            }?.start()

            val pill = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRounded("#EB1a1b26", "#7aa2f7", 24, 2)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                elevation = dp(16).toFloat()
                alpha = 0f
                scaleX = 0.84f
                scaleY = 0.84f
            }

            fun addDockBtn(kind: VectorKind, colorHex: String, onClick: () -> Unit) {
                val box = FrameLayout(activity).apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onClick()
                    }
                }
                box.addView(VectorIconView(activity, kind, Color.parseColor(colorHex), 21))
                pill.addView(box)
            }

            val collapseFirst = !dockedRight
            if (collapseFirst) {
                addDockBtn(VectorKind.CHEVRON_LEFT, "#565f89") { collapseDock() }
            }
            addDockBtn(VectorKind.KEYBOARD, "#c0caf5") {
                collapseDock()
                if (oskVisible) hideOsk() else showOsk()
            }
            addDockBtn(VectorKind.MIC, "#9ece6a") {
                collapseDock()
                triggerAndroidIme()
            }
            addDockBtn(
                if (isLandscapeNow()) VectorKind.ORIENT_PORT else VectorKind.ORIENT_LAND,
                "#7aa2f7"
            ) {
                toggleOrientationLock()
            }
            addDockBtn(VectorKind.SETTINGS, "#bb9af7") {
                collapseDock()
                showQuickMenu()
            }
            if (!collapseFirst) {
                addDockBtn(VectorKind.CHEVRON_RIGHT, "#565f89") { collapseDock() }
            }

            host.addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            expandedDockView = pill
            pill.post {
                positionExpandedDock()
                pill.pivotX = if (dockedRight) pill.width.toFloat() else 0f
                pill.pivotY = pill.height / 2f
                pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .setDuration(220L)
                    .start()
            }

            autoCollapseTask?.let { mainHandler.removeCallbacks(it) }
            autoCollapseTask = Runnable { collapseDock() }.also {
                mainHandler.postDelayed(it, 6000L)
            }
        }

        private fun positionExpandedDock() {
            val host = overlayHost ?: return
            val dock = expandedDockView ?: return
            val w = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val h = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val dw = dock.width.takeIf { it > 0 } ?: dp(220)
            val dh = dock.height.takeIf { it > 0 } ?: dp(46)
            dock.x = if (dockedRight) (w - dw - dp(10)).toFloat().coerceAtLeast(dp(8).toFloat()) else dp(10).toFloat()
            dock.y = (h * dockYFraction).coerceIn(dp(40).toFloat(), (h - dh - dp(24)).toFloat())
        }

        private fun collapseDock() {
            autoCollapseTask?.let { mainHandler.removeCallbacks(it) }
            val dock = expandedDockView
            expandedDockView = null
            dockExpanded = false
            if (dock != null) {
                dock.animate()
                    .alpha(0f)
                    .scaleX(0.84f)
                    .scaleY(0.84f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(160L)
                    .withEndAction { overlayHost?.removeView(dock) }
                    .start()
            }
            // Always keep the compact edge tab available at edge (even when OSK is open!)
            edgeTabView?.let { tab ->
                tab.visibility = View.VISIBLE
                positionEdgeTab()
                tab.animate().alpha(0.72f).setDuration(180L).start()
            }
        }

        // ==================== Unified Control Center (⚙) ====================

        private fun hideQuickMenu() {
            quickMenuOverlay?.let { overlayHost?.removeView(it) }
            quickMenuOverlay = null
        }

        private fun showQuickMenu() {
            val host = overlayHost ?: return
            hideQuickMenu()
            hideIme()

            val backdrop = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
                setOnClickListener { hideQuickMenu() }
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = makeRounded("#F51a1b26", "#7aa2f7", 16, 2)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                elevation = dp(18).toFloat()
                isClickable = true
            }

            card.addView(TextView(activity).apply {
                text = "Omarchy 控制中心"
                textSize = 17f
                setTextColor(Color.parseColor("#7aa2f7"))
                setPadding(0, 0, 0, dp(12))
            })

            fun addMenuItem(title: String, subtitle: String, accentHex: String = "#c0caf5", onClick: () -> Unit) {
                val item = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = makeRounded("#CC24283b", "#414868", 10, 1)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        hideQuickMenu()
                        onClick()
                    }
                }
                item.addView(TextView(activity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Color.parseColor(accentHex))
                })
                item.addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#9aa5ce"))
                    setPadding(0, dp(2), 0, 0)
                })
                card.addView(item)
            }

            addMenuItem("Omarchy 统一设置与系统管理", "集中管理 X11 触控设置、存储权限、电池优化、音频与终端日志") {
                try {
                    val intent = Intent(activity, Class.forName("com.fct.tc4.ui.main.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {}
            }

            addMenuItem("快捷键一览（Super + K）", "查看 Omarchy 完整桌面快捷键与窗口平铺操作", "#7aa2f7") {
                sendCmd("keybindings")
            }

            val doubaoReady = isDoubaoInstalled()
            addMenuItem(
                if (doubaoReady) "切换系统输入法（已安装豆包输入法）" else "推荐安装豆包输入法（语音 + 中文）",
                if (doubaoReady) "一键调起输入法选择器，设为豆包后点手柄话筒直通桌面"
                else "搭配豆包输入法可实现离线高精度中文语音直入 Linux 应用",
                if (doubaoReady) "#9ece6a" else "#e0af68"
            ) {
                if (doubaoReady) openInputMethodPicker()
                else activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOUBAO_URL)))
            }

            addMenuItem(
                if (isLandscapeNow()) "切换并锁定为竖屏模式" else "切换并锁定为横屏模式",
                "竖屏调出输入法时自动缩放桌面，不再遮挡终端与输入框",
                "#7aa2f7"
            ) {
                toggleOrientationLock()
            }

            addMenuItem("查看新手引导", "重播箭头指引教程（缩放、悬浮手柄、Super+K、豆包输入法）", "#a9b1d6") {
                showOnboardingStep(0)
            }

            val scroll = ScrollView(activity).apply { addView(card) }
            val sw = activity.resources.displayMetrics.widthPixels
            val cardW = min(sw - dp(32), dp(380))
            backdrop.addView(scroll, FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            host.addView(backdrop, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            quickMenuOverlay = backdrop
        }

        // ==================== Interactive 4-Step Arrow-Guided Onboarding ====================

        private fun hideOnboarding() {
            onboardingOverlay?.let { overlayHost?.removeView(it) }
            onboardingOverlay = null
        }

        private fun finishOnboarding() {
            prefs.edit().putBoolean("onboarding_v2_done", true).apply()
            hideOnboarding()
        }

        private fun showOnboardingStep(step: Int) {
            val host = overlayHost ?: return
            hideOnboarding()

            val w = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val h = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
                isClickable = true
            }

            val arrowView = GuideArrowOverlay(activity)
            root.addView(arrowView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = makeRounded("#F81a1b26", "#7aa2f7", 16, 2)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                elevation = dp(20).toFloat()
            }

            // Header row: step badge + Skip button
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            header.addView(TextView(activity).apply {
                text = "新手引导  ${step + 1} / 4"
                textSize = 12f
                setTextColor(Color.parseColor("#9ece6a"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(activity).apply {
                text = "跳过引导"
                textSize = 12f
                setTextColor(Color.parseColor("#9aa5ce"))
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener { finishOnboarding() }
            })
            card.addView(header)

            when (step) {
                0 -> {
                    // Step 1: Point to top-right Monitor icon (default 1.5x scale)
                    card.addView(TextView(activity).apply {
                        text = "① 屏幕缩放与显示（默认 1.5x）"
                        textSize = 16.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        setPadding(0, 0, 0, dp(6))
                    })
                    card.addView(TextView(activity).apply {
                        text = "已针对手机和平板预设 1.5 倍清晰缩放。\n随时点击右上角状态栏的「显示器图标」，即可按个人习惯自由调节 0.85x ~ 2.0x 缩放比例。"
                        textSize = 13f
                        setTextColor(Color.parseColor("#c0caf5"))
                        setPadding(0, 0, 0, dp(14))
                    })
                    arrowView.showArrow = true
                    arrowView.targetX = w - dp(48).toFloat()
                    arrowView.targetY = dp(16).toFloat()
                    arrowView.fromX = w * 0.62f
                    arrowView.fromY = h * 0.36f
                    arrowView.ringRadius = dp(22).toFloat()
                }
                1 -> {
                    // Step 2: Point to Edge Floating Handle
                    card.addView(TextView(activity).apply {
                        text = "② 贴边悬浮手柄（免权限）"
                        textSize = 16.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        setPadding(0, 0, 0, dp(6))
                    })
                    card.addView(TextView(activity).apply {
                        text = "屏幕边缘的小拉条平时自动贴边收起，几乎不占画面。\n• 轻点展开：透明悬浮键盘 / 语音输入 / 横竖屏锁定 / 控制中心\n• 按住拖动：可自由调整高度并自动吸附左/右边缘\n• 键盘底部小白条按住即可随意拖动键盘位置"
                        textSize = 13f
                        setTextColor(Color.parseColor("#c0caf5"))
                        setPadding(0, 0, 0, dp(14))
                    })
                    arrowView.showArrow = true
                    arrowView.targetX = if (dockedRight) (w - dp(12)).toFloat() else dp(12).toFloat()
                    arrowView.targetY = (h * dockYFraction).coerceIn(dp(60).toFloat(), (h - dp(60)).toFloat())
                    arrowView.fromX = w * 0.68f
                    arrowView.fromY = h * 0.56f
                    arrowView.ringRadius = dp(28).toFloat()
                }
                2 -> {
                    // Step 3: Super + K Shortcut Overview
                    card.addView(TextView(activity).apply {
                        text = "③ 掌握核心快捷键（Super + K）"
                        textSize = 16.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        setPadding(0, 0, 0, dp(6))
                    })
                    card.addView(TextView(activity).apply {
                        text = "Omarchy 以高效键盘流为核心，记住这几个最常用组合：\n• Super + K ：打开完整快捷键速查表\n• Super + Space ：打开 6 列应用网格\n• Super + Enter ：打开 Alacritty 终端\n• Super + W ：关闭当前窗口"
                        textSize = 13f
                        setTextColor(Color.parseColor("#c0caf5"))
                        setPadding(0, 0, 0, dp(10))
                    })
                    val previewBtn = Button(activity).apply {
                        text = "立即预览 Super + K 快捷键表"
                        isAllCaps = false
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        background = makeRounded("#CC283457", "#7aa2f7", 8, 1)
                        setOnClickListener {
                            sendCmd("keybindings")
                            finishOnboarding()
                        }
                    }
                    card.addView(previewBtn, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
                    ).apply { bottomMargin = dp(10) })
                }
                else -> {
                    // Step 4: Doubao IME + Essential Permissions
                    card.addView(TextView(activity).apply {
                        text = "④ 语音输入与推荐权限"
                        textSize = 16.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        setPadding(0, 0, 0, dp(8))
                    })

                    val doubaoReady = isDoubaoInstalled()
                    val imeBox = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = makeRounded(if (doubaoReady) "#229ece6a" else "#227aa2f7", if (doubaoReady) "#9ece6a" else "#7aa2f7", 10, 1)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    }
                    imeBox.addView(TextView(activity).apply {
                        text = if (doubaoReady) "✓ 已检测到豆包输入法" else "推荐安装：豆包输入法"
                        textSize = 13f
                        setTextColor(Color.parseColor(if (doubaoReady) "#9ece6a" else "#7aa2f7"))
                    })
                    imeBox.addView(TextView(activity).apply {
                        text = if (doubaoReady) "设为默认输入法后，点击手柄话筒即可语音/中文精准上屏。"
                        else "离线高精度中文语音与顺滑选词，直通 Linux 终端与应用。"
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#c0caf5"))
                        setPadding(0, dp(2), 0, dp(6))
                    })
                    imeBox.addView(Button(activity).apply {
                        text = if (doubaoReady) "切换为默认输入法" else "前往下载豆包输入法"
                        isAllCaps = false
                        textSize = 12f
                        setTextColor(Color.parseColor("#1a1b26"))
                        background = makeRounded(if (doubaoReady) "#9ece6a" else "#7aa2f7", "#7aa2f7", 8, 1)
                        setOnClickListener {
                            if (doubaoReady) openInputMethodPicker()
                            else activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOUBAO_URL)))
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
                    card.addView(imeBox)

                    // Quick permission buttons row (Storage + Battery)
                    val permRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(12) }
                    }
                    val filesOk = hasAllFilesPermission()
                    permRow.addView(Button(activity).apply {
                        text = if (filesOk) "✓ 存储权限已开" else "开启文件存储权限"
                        isAllCaps = false
                        textSize = 11.5f
                        setTextColor(Color.parseColor(if (filesOk) "#9ece6a" else "#c0caf5"))
                        background = makeRounded("#CC24283b", if (filesOk) "#9ece6a" else "#414868", 8, 1)
                        setOnClickListener {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${activity.packageName}")
                                })
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })

                    val batOk = isBatteryUnrestricted()
                    permRow.addView(Button(activity).apply {
                        text = if (batOk) "✓ 后台常驻已开" else "关闭电池优化"
                        isAllCaps = false
                        textSize = 11.5f
                        setTextColor(Color.parseColor(if (batOk) "#9ece6a" else "#c0caf5"))
                        background = makeRounded("#CC24283b", if (batOk) "#9ece6a" else "#414868", 8, 1)
                        setOnClickListener {
                            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            })
                        }
                    }, LinearLayout.LayoutParams(0, dp(36), 1f))
                    card.addView(permRow)
                }
            }

            // Footer navigation buttons
            val navRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (step > 0) {
                navRow.addView(Button(activity).apply {
                    text = "上一步"
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.parseColor("#c0caf5"))
                    background = makeRounded("#CC24283b", "#414868", 8, 1)
                    setOnClickListener { showOnboardingStep(step - 1) }
                }, LinearLayout.LayoutParams(dp(86), dp(38)).apply { marginEnd = dp(8) })
            }
            navRow.addView(Button(activity).apply {
                text = if (step < 3) "下一步" else "开始使用 Omarchy"
                isAllCaps = false
                textSize = 13.5f
                setTextColor(Color.parseColor("#1a1b26"))
                background = makeRounded("#7aa2f7", "#7aa2f7", 8, 1)
                setOnClickListener {
                    if (step < 3) showOnboardingStep(step + 1) else finishOnboarding()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(navRow)

            val scroll = ScrollView(activity).apply { addView(card) }
            val cardW = min(w - dp(32), dp(390))
            root.addView(scroll, FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            host.addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            onboardingOverlay = root
        }

        // ==================== Invisible Doubao IME Bridge ====================

        private fun hideIme() {
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

            val edit = ImeBridgeEditText(
                context = activity,
                onCommit = { committed -> sendText(committed) },
                onBackspace = { sendKey("BackSpace") },
                onEnter = { sendKey("Return") },
                onDismiss = { hideIme() }
            )

            edit.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    val rem = edit.text?.toString() ?: ""
                    if (rem.isNotEmpty()) {
                        sendText(rem)
                        edit.setText("")
                    }
                    sendKey("Return")
                    true
                } else false
            }

            host.addView(edit, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.START))
            imeAnchor = edit
            imeActive = true

            edit.requestFocus()
            edit.postDelayed({
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 90L)
        }

        // ==================== Floating 0.70x Ergonomic Keyboard ====================

        private fun hideOsk() {
            oskView?.let { overlayHost?.removeView(it) }
            oskView = null
            oskVisible = false
        }

        private fun bgWithAlpha(alpha: Float): Int {
            val a = max(36, min(242, (alpha * 255f).roundToInt()))
            return Color.argb(a, 0x1a, 0x1b, 0x26)
        }

        private fun styleOpacitySlider(sb: SeekBar) {
            val bgTrack = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#55414868"))
                setSize(-1, dp(4))
            }
            val fgTrack = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#7aa2f7"))
                setSize(-1, dp(4))
            }
            val layers = LayerDrawable(arrayOf(bgTrack, ClipDrawable(fgTrack, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            val thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(14), dp(14))
                setColor(Color.parseColor("#c0caf5"))
                setStroke(dp(2), Color.parseColor("#7aa2f7"))
            }
            sb.progressDrawable = layers
            sb.thumb = thumb
            sb.splitTrack = false
            sb.setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        private fun showOsk() {
            if (oskVisible) return
            val host = overlayHost ?: return
            hideIme()
            // Keep the compact edge tab collapsed at edge instead of hiding it!
            collapseDock()
            modBtns.clear()

            val rootBg = GradientDrawable().apply {
                setColor(bgWithAlpha(oskAlpha))
                setStroke(dp(2), Color.parseColor("#7aa2f7"))
                cornerRadius = dp(14).toFloat()
            }

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rootBg
                setPadding(dp(7), dp(6), dp(7), dp(3))
                elevation = dp(12).toFloat()
                alpha = max(0.38f, oskAlpha + 0.14f)
            }

            // Drag helper attached to both bottom pill bar AND top bar empty area
            var dxStart = 0f; var dyStart = 0f
            var oTx = 0f; var oTy = 0f
            val dragTouchListener = View.OnTouchListener { _, e ->
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

            // Clean top bar: [1][2][3][4][5]  [drag space]  [refined opacity slider]  [✕]
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

            val dragSpacer = View(activity).apply { setOnTouchListener(dragTouchListener) }
            bar.addView(dragSpacer, LinearLayout.LayoutParams(0, dp(28), 1f))

            val alphaBar = SeekBar(activity).apply {
                max = 75
                progress = ((oskAlpha - 0.20f) * 100f).roundToInt()
                layoutParams = LinearLayout.LayoutParams(dp(100), dp(24)).apply {
                    setMargins(dp(4), 0, dp(6), 0)
                }
                styleOpacitySlider(this)
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

            // Sleek iOS/Android-style bottom drag pill bar
            val grabArea = FrameLayout(activity).apply {
                setOnTouchListener(dragTouchListener)
            }
            val pillBar = View(activity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#AAc0caf5"))
                    cornerRadius = dp(3).toFloat()
                }
            }
            grabArea.addView(pillBar, FrameLayout.LayoutParams(dp(56), dp(4), Gravity.CENTER))
            root.addView(grabArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20)
            ))

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
            // Keep edge tab on top of keyboard
            edgeTabView?.bringToFront()
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
