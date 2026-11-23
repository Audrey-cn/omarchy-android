package com.fct.tc4.ui.misc

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import android.os.SystemClock
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fct.tc4.R
import com.termux.x11.MainActivity as X11Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.math.sqrt

/**
 * Native in-process Omarchy overlay attached directly to com.termux.x11.MainActivity.
 * - Zero SYSTEM_ALERT_WINDOW permission required.
 * - Smooth animated edge-snapping floating handle (stays collapsed at edge even when OSK is open).
 * - Custom vector icons (Keyboard, Mic, Orientation Lock, Settings Gear) — zero raw emojis.
 * - Refined floating 0.70x keyboard: custom thin Tokyo Night opacity slider + iOS/Android-style
 *   bottom drag pill bar (zero "拖动" button clutter).
 * - Interactive 4-step arrow-guided onboarding (1.5x Monitor scale, Edge Handle,
 *   Super+K keybindings, Doubao IME & system permissions, skippable anytime).
 * - Hard-locked Portrait / Landscape orientation + Portrait soft-keyboard viewport shrink.
 */
object OmarchyX11Overlay {
    private val controllers = WeakHashMap<Activity, Controller>()
    private const val DOUBAO_PKG = "com.bytedance.android.doubaoime"
    private const val DOUBAO_URL = "https://shurufa.doubao.com/"
    private const val ONBOARDING_COMPLETE = "onboarding_v2_done"
    private const val ONBOARDING_OPT_OUT = "onboarding_v2_opted_out"

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
                if (activity is X11Activity) {
                    val ctrl = controllers.getOrPut(activity) { Controller(activity) }
                    ctrl.attachIfNeeded()
                    ctrl.refreshOnboardingIfVisible()
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

    /** Draws only a gentle curved arrow, independent of screen aspect ratio. */
    private class GuideArrowOverlay(context: Context) : View(context) {
        var targetX = 0f
        var targetY = 0f
        var fromX = 0f
        var fromY = 0f
        var showArrow = false
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#9ece6a")
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!showArrow) return
            // Leave a little air before the target and follow the curve tangent.
            val dx = targetX - fromX
            val dy = targetY - fromY
            val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val gap = 8f * resources.displayMetrics.density
            val endX = targetX - dx / distance * gap
            val endY = targetY - dy / distance * gap
            val ctrlX = (fromX + endX) * 0.5f
            val ctrlY = fromY + (endY - fromY) * 0.18f
            val path = Path().apply {
                moveTo(fromX, fromY)
                quadTo(ctrlX, ctrlY, endX, endY)
            }
            canvas.drawPath(path, arrowPaint)

            val angle = atan2((endY - ctrlY).toDouble(), (endX - ctrlX).toDouble())
            val headLen = 9f * resources.displayMetrics.density
            val a1 = angle - Math.toRadians(30.0)
            val a2 = angle + Math.toRadians(30.0)
            canvas.drawLine(
                endX, endY,
                endX - (headLen * cos(a1)).toFloat(),
                endY - (headLen * sin(a1)).toFloat(),
                arrowPaint
            )
            canvas.drawLine(
                endX, endY,
                endX - (headLen * cos(a2)).toFloat(),
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

    private class Controller(private val activity: X11Activity) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val netExec = Executors.newSingleThreadExecutor()
        private val prefs = activity.getSharedPreferences("omarchy_overlay_prefs", Context.MODE_PRIVATE)

        @Volatile private var attached = false
        private var overlayHost: FrameLayout? = null
        private var nightOverlay: View? = null
        private var edgeTabView: View? = null
        private var expandedDockView: View? = null
        private var quickMenuOverlay: View? = null
        private var onboardingOverlay: View? = null
        private var onboardingStatus: View? = null
        private var onboardingProbeJob: Job? = null
        private var onboardingReadiness: OnboardingReadiness? = null
        private var currentOnboardingStep: Int? = null
        private var selectedOnboardingScale: String? = null
        private var onboardingScaleInitialized = false
        private var oskView: View? = null
        private var imeAnchor: EditText? = null
        private var imeAccessoryView: View? = null
        private val accessoryModBtns = HashMap<String, Button>()
        private val startupTasks = mutableListOf<Runnable>()
        private var edgeLongPressTask: Runnable? = null
        private var edgeGlowAnimator: ObjectAnimator? = null
        private var edgeGlowView: View? = null
        private var permissionRefreshTask: Runnable? = null

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
        private var fullscreenActive = false
        private var fullscreenBtn: Button? = null
        private var lastTabTapAt = 0L
        private var pendingSingleTap: Runnable? = null
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
                updateImeAccessory()
                currentOnboardingStep?.let { showOnboardingStep(it) }
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
                val task = Runnable {
                    if (!attached) return@Runnable
                    val w = content.width
                    val h = content.height
                    if (w >= 300 && h >= 300) {
                        lastFullW = w
                        lastFullH = h
                        positionEdgeTab()
                        sendResize(w, max(320, h - lastImeH))
                    }
                }
                startupTasks.add(task)
                mainHandler.postDelayed(task, delay)
            }

            if (onboardingProgress().shouldShowAutomatically) {
                waitForOnboardingDesktop()
            }
        }

        fun detach() {
            attached = false
            cancelOnboardingProbe()
            mainHandler.removeCallbacks(queuePollTask)
            startupTasks.forEach { mainHandler.removeCallbacks(it) }
            startupTasks.clear()
            edgeLongPressTask?.let { mainHandler.removeCallbacks(it) }
            permissionRefreshTask?.let { mainHandler.removeCallbacks(it) }
            permissionRefreshTask = null
            edgeGlowAnimator?.cancel()
            edgeGlowAnimator = null
            edgeGlowView = null
            resizeTask?.let { mainHandler.removeCallbacks(it) }
            autoCollapseTask?.let { mainHandler.removeCallbacks(it) }
            pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
            pendingSingleTap = null
            fullscreenBtn = null
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
            currentOnboardingStep = null
            oskView = null
            imeAnchor = null
            imeAccessoryView = null
            accessoryModBtns.clear()
            modBtns.clear()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) content?.setOnApplyWindowInsetsListener(null)
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
            if (imeH == lastImeH) {
                updateImeAccessory()
                return
            }
            lastImeH = imeH

            val x11Root = content.getChildAt(0)
            if (x11Root != null && x11Root !== overlayHost) {
                val lp = x11Root.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.bottomMargin = imeH
                    x11Root.layoutParams = lp
                }
            }
            updateImeAccessory()
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
            if (!attached || netExec.isShutdown) return
            // detach() may shut the executor down between the guard and execute().
            try {
                netExec.execute {
                    if (!attached) return@execute
                    try {
                        Socket("127.0.0.1", 18901).use { socket ->
                            socket.getOutputStream().apply {
                                write((jsonLine + "\n").toByteArray(Charsets.UTF_8))
                                flush()
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        private fun s(id: Int, vararg args: Any): String = activity.getString(id, *args)

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

        // ==================== Edge handle: tap, double-tap, long-press and drag ====================

        private fun setEdgeGlow(touched: Boolean) {
            edgeGlowAnimator?.cancel()
            val glow = edgeGlowView ?: return
            glow.alpha = if (touched) 1f else 0.58f
            if (!touched && attached) {
                edgeGlowAnimator = ObjectAnimator.ofFloat(glow, "alpha", 0.58f, 0.70f, 0.58f).apply {
                    duration = 3200L
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }

        private fun updateEdgeGestureExclusion(tab: View) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tab.systemGestureExclusionRects = listOf(Rect(0, 0, tab.width, tab.height))
            }
        }

        private fun buildEdgeTab(host: FrameLayout) {
            val tab = FrameLayout(activity).apply {
                contentDescription = s(R.string.omarchy_overlay_handle)
                setOnClickListener { expandDock() }
                setOnLongClickListener {
                    triggerAndroidIme()
                    true
                }
            }
            val glow = object : View(activity) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#7AA2F7")
                    setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.parseColor("#7AA2F7"))
                }
                init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
                override fun onDraw(canvas: Canvas) {
                    val halfWidth = 1.75f * resources.displayMetrics.density
                    val halfHeight = dp(16).toFloat()
                    canvas.drawRoundRect(
                        width / 2f - halfWidth, height / 2f - halfHeight,
                        width / 2f + halfWidth, height / 2f + halfHeight,
                        halfWidth, halfWidth, paint
                    )
                }
            }
            tab.addView(glow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            edgeGlowView = glow
            setEdgeGlow(false)

            var downRawX = 0f; var downRawY = 0f
            var origX = 0f; var origY = 0f
            var moved = false
            var longPressed = false
            var doubleTap = false

            tab.setOnTouchListener { v, e ->
                val parentW = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
                val parentH = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        downRawX = e.rawX; downRawY = e.rawY
                        origX = v.x; origY = v.y
                        moved = false
                        longPressed = false
                        doubleTap = pendingSingleTap != null && SystemClock.elapsedRealtime() - lastTabTapAt <= 280L
                        pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
                        pendingSingleTap = null
                        setEdgeGlow(true)
                        edgeLongPressTask?.let { mainHandler.removeCallbacks(it) }
                        edgeLongPressTask = Runnable {
                            edgeLongPressTask = null
                            if (attached && !moved) {
                                longPressed = true
                                lastTabTapAt = 0L
                                v.performLongClick()
                            }
                        }.also { mainHandler.postDelayed(it, 350L) }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        // The movement threshold is deliberately in pixels, matching the touch contract.
                        if (dx * dx + dy * dy > 100f) {
                            moved = true
                            edgeLongPressTask?.let { mainHandler.removeCallbacks(it) }
                            edgeLongPressTask = null
                        }
                        if (moved && !longPressed) {
                            v.x = OverlayBounds.horizontal(origX + dx, parentW, v.width)
                            v.y = OverlayBounds.vertical(origY + dy, parentH, v.height, dp(40), dp(24))
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        edgeLongPressTask?.let { mainHandler.removeCallbacks(it) }
                        edgeLongPressTask = null
                        val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                        if (!cancelled && !moved && !longPressed) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (doubleTap) {
                                lastTabTapAt = 0L
                                if (oskVisible) hideOsk() else showOsk()
                            } else {
                                lastTabTapAt = SystemClock.elapsedRealtime()
                                pendingSingleTap = Runnable {
                                    pendingSingleTap = null
                                    if (attached) v.performClick()
                                }.also { mainHandler.postDelayed(it, 280L) }
                            }
                        } else {
                            lastTabTapAt = 0L
                            if (moved && !longPressed) {
                                dockedRight = v.x + v.width / 2f >= parentW / 2f
                                dockYFraction = (v.y / max(1, parentH).toFloat()).coerceIn(0.12f, 0.85f)
                                prefs.edit().putBoolean("dock_right", dockedRight)
                                    .putFloat("dock_y_frac", dockYFraction).apply()
                            }
                        }
                        val targetX = if (dockedRight) max(0f, (parentW - v.width).toFloat()) else 0f
                        v.animate().x(targetX).setInterpolator(OvershootInterpolator(0.75f))
                            .setDuration(220L).start()
                        setEdgeGlow(false)
                        true
                    }
                    else -> false
                }
            }
            tab.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateEdgeGestureExclusion(tab) }
            host.addView(tab, FrameLayout.LayoutParams(dp(12), dp(56)))
            edgeTabView = tab
            tab.post { if (attached) positionEdgeTab() }
        }

        private fun positionEdgeTab() {
            val host = overlayHost ?: return
            val tab = edgeTabView ?: return
            val w = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val h = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val tw = tab.width.takeIf { it > 0 } ?: dp(12)
            val th = tab.height.takeIf { it > 0 } ?: dp(56)
            tab.x = OverlayBounds.horizontal(if (dockedRight) w.toFloat() else 0f, w, tw)
            tab.y = OverlayBounds.vertical(h * dockYFraction, h, th, dp(40), dp(24))
            updateEdgeGestureExclusion(tab)
        }

        // ==================== Expanded Floating Dock (Refined Tokyo Night Glass) ====================

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
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#F4282e45"), Color.parseColor("#F01a1b26"))
                ).apply {
                    setStroke(dp(1), Color.parseColor("#AA7aa2f7"))
                    cornerRadius = dp(26).toFloat()
                }
                setPadding(dp(7), dp(6), dp(7), dp(6))
                elevation = dp(18).toFloat()
                alpha = 0f
                scaleX = 0.84f
                scaleY = 0.84f
            }

            fun addDockBtn(kind: VectorKind, colorHex: String, highlightBg: Boolean = true, onClick: () -> Unit) {
                val box = FrameLayout(activity).apply {
                    if (highlightBg) {
                        background = makeRounded("#2E414868", "#447aa2f7", 14, 1)
                    }
                    contentDescription = s(when (kind) {
                        VectorKind.KEYBOARD -> R.string.omarchy_overlay_keyboard_toggle
                        VectorKind.MIC -> R.string.omarchy_overlay_voice_ime
                        VectorKind.ORIENT_PORT -> R.string.omarchy_overlay_lock_portrait
                        VectorKind.ORIENT_LAND -> R.string.omarchy_overlay_lock_landscape
                        VectorKind.SETTINGS -> R.string.omarchy_overlay_control_title
                        else -> R.string.omarchy_overlay_dock_collapse
                    })
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(3), 0, dp(3), 0) }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        it.animate().scaleX(0.90f).scaleY(0.90f).setDuration(60L).withEndAction {
                            it.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                        }.start()
                        onClick()
                    }
                }
                box.addView(VectorIconView(activity, kind, Color.parseColor(colorHex), 20))
                pill.addView(box)
            }

            val collapseFirst = !dockedRight
            if (collapseFirst) {
                addDockBtn(VectorKind.CHEVRON_LEFT, "#7884b0", highlightBg = false) { collapseDock() }
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
                addDockBtn(VectorKind.CHEVRON_RIGHT, "#7884b0", highlightBg = false) { collapseDock() }
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
            dock.x = OverlayBounds.horizontal((if (dockedRight) w - dw - dp(10) else dp(10)).toFloat(), w, dw)
            dock.y = OverlayBounds.vertical(h * dockYFraction, h, dh, dp(40), dp(24))
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
                tab.animate().alpha(1f).setDuration(180L).start()
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
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#FA222638"), Color.parseColor("#F51a1b26"))
                ).apply {
                    setStroke(dp(1), Color.parseColor("#AA7aa2f7"))
                    cornerRadius = dp(18).toFloat()
                }
                setPadding(dp(18), dp(16), dp(18), dp(16))
                elevation = dp(18).toFloat()
                isClickable = true
            }

            card.addView(TextView(activity).apply {
                text = s(R.string.omarchy_overlay_control_eyebrow)
                textSize = 10.5f
                letterSpacing = 0.14f
                setTextColor(Color.parseColor("#7aa2f7"))
            })
            card.addView(TextView(activity).apply {
                text = s(R.string.omarchy_overlay_control_title)
                textSize = 17.5f
                setTextColor(Color.parseColor("#c0caf5"))
                setPadding(0, dp(2), 0, dp(12))
            })

            fun addMenuItem(title: String, subtitle: String, accentHex: String = "#c0caf5", onClick: () -> Unit) {
                val item = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = makeRounded("#D824283b", "#414868", 12, 1)
                    setPadding(dp(10), dp(10), dp(14), dp(10))
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
                val accentBar = View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(accentHex))
                        cornerRadius = dp(2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply { marginEnd = dp(10) }
                }
                item.addView(accentBar)
                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(activity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Color.parseColor(accentHex))
                })
                textCol.addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#9aa5ce"))
                    setPadding(0, dp(2), 0, 0)
                })
                item.addView(textCol)
                card.addView(item)
            }

            addMenuItem(s(R.string.omarchy_overlay_settings_title), s(R.string.omarchy_overlay_settings_desc)) {
                try {
                    val intent = Intent(activity, Class.forName("com.fct.tc4.ui.main.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {}
            }

            addMenuItem(s(R.string.omarchy_overlay_shortcuts_title), s(R.string.omarchy_overlay_shortcuts_desc), "#7aa2f7") {
                sendCmd("keybindings")
            }

            addMenuItem(s(R.string.omarchy_overlay_updates_title), s(R.string.omarchy_overlay_updates_desc), "#9ece6a") {
                val updater = File(activity.dataDir, "omarchy/usr/local/bin/omarchy-updates")
                if (updater.isFile || java.nio.file.Files.isSymbolicLink(updater.toPath())) sendCmd("updates")
                else android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.omarchy_overlay_update_missing_title)
                    .setMessage(R.string.omarchy_overlay_update_missing_desc)
                    .setPositiveButton(R.string.omarchy_overlay_release_notes) { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Audrey-cn/omarchy-android/releases"))) }
                    .setNegativeButton(R.string.tc4_btn_back, null).show()
            }

            addMenuItem(s(R.string.omarchy_overlay_floating_title), s(R.string.omarchy_overlay_floating_desc), "#7aa2f7") {
                sendJson("""{"type":"key","key":"t","super":true}""")
            }

            addMenuItem(s(R.string.omarchy_overlay_fullscreen_title), s(R.string.omarchy_overlay_fullscreen_desc), "#7aa2f7") {
                fullscreenActive = !fullscreenActive
                refreshFullscreenButton()
                sendCmd("fullscreen")
            }

            val doubaoReady = isDoubaoInstalled()
            addMenuItem(
                if (doubaoReady) s(R.string.omarchy_overlay_ime_installed_title) else s(R.string.omarchy_overlay_ime_install_title),
                if (doubaoReady) s(R.string.omarchy_overlay_ime_installed_desc)
                else s(R.string.omarchy_overlay_ime_install_desc),
                if (doubaoReady) "#9ece6a" else "#e0af68"
            ) {
                if (doubaoReady) openInputMethodPicker()
                else activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOUBAO_URL)))
            }

            addMenuItem(
                if (isLandscapeNow()) s(R.string.omarchy_overlay_lock_portrait) else s(R.string.omarchy_overlay_lock_landscape),
                s(R.string.omarchy_overlay_orientation_desc),
                "#7aa2f7"
            ) {
                toggleOrientationLock()
            }

            addMenuItem(s(R.string.omarchy_overlay_replay_title), s(R.string.omarchy_overlay_replay_desc), "#a9b1d6") {
                waitForOnboardingDesktop()
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

        private fun waitForOnboardingDesktop() {
            cancelOnboardingProbe()
            hideOnboarding()
            selectedOnboardingScale = null
            onboardingScaleInitialized = false
            val readiness = OnboardingReadiness(SystemClock.elapsedRealtime())
            onboardingReadiness = readiness
            showOnboardingStatus(timedOut = false)
            val desktopProbe = SwayDesktopProbe(File(activity.filesDir, "tmp"))
            onboardingProbeJob = CoroutineScope(Dispatchers.Main).launch {
                while (attached) {
                    val viewport = onboardingViewport()
                    val desktop = if (viewport != null) {
                        val remaining = readiness.remainingMillis(SystemClock.elapsedRealtime())
                        desktopProbe.sample(remaining.coerceAtMost(1_200))
                    } else {
                        null
                    }
                    val currentViewport = onboardingViewport()
                    val currentDesktop = desktop.takeIf { viewport == currentViewport }
                    when (readiness.observe(SystemClock.elapsedRealtime(), currentViewport, currentDesktop)) {
                        OnboardingReadiness.State.READY -> {
                            hideOnboardingStatus()
                            showOnboardingStep(0)
                            return@launch
                        }
                        OnboardingReadiness.State.TIMED_OUT -> {
                            showOnboardingStatus(timedOut = true)
                            return@launch
                        }
                        OnboardingReadiness.State.WAITING ->
                            delay(readiness.remainingMillis(SystemClock.elapsedRealtime()).coerceAtMost(450))
                        else -> return@launch
                    }
                }
            }
        }

        private fun onboardingViewport(): OnboardingViewport? {
            val host = overlayHost ?: return null
            if (!host.isAttachedToWindow || !host.isLaidOut || host.width <= 0 || host.height <= 0 ||
                !activity.hasWindowFocus() || X11Activity.getInstance() !== activity || !X11Activity.isConnected()
            ) return null
            val viewport = activity.lorieView ?: return null
            if (!viewport.isAttachedToWindow || !viewport.isLaidOut || !viewport.isShown ||
                !viewport.holder.surface.isValid || viewport.width <= 0 || viewport.height <= 0
            ) return null
            return OnboardingViewport(viewport.width, viewport.height)
        }

        private fun showOnboardingStatus(timedOut: Boolean) {
            val host = overlayHost ?: return
            hideOnboardingStatus()
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = makeRounded("#F51a1b26", "#7aa2f7", 12, 1)
                elevation = dp(12).toFloat()
            }
            card.addView(TextView(activity).apply {
                text = if (timedOut) s(R.string.omarchy_onboarding_wait_timeout) else s(R.string.omarchy_onboarding_waiting)
                setTextColor(Color.parseColor("#c0caf5"))
                textSize = 13f
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            if (timedOut) {
                val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
                actions.addView(
                    makeBarBtn(s(R.string.omarchy_onboarding_retry), true, ::waitForOnboardingDesktop),
                    LinearLayout.LayoutParams(dp(84), dp(44)).apply { marginEnd = dp(8) }
                )
                actions.addView(
                    makeBarBtn(s(R.string.omarchy_onboarding_skip_for_now), false, ::cancelOnboardingProbe),
                    LinearLayout.LayoutParams(dp(84), dp(44))
                )
                card.addView(actions)
            }
            host.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(dp(16), 0, dp(16), dp(18)) })
            onboardingStatus = card
        }

        private fun hideOnboardingStatus() {
            onboardingStatus?.let { overlayHost?.removeView(it) }
            onboardingStatus = null
        }

        private fun cancelOnboardingProbe() {
            onboardingProbeJob?.cancel()
            onboardingProbeJob = null
            onboardingReadiness?.cancel()
            onboardingReadiness = null
            hideOnboardingStatus()
        }

        private fun hideOnboarding() {
            permissionRefreshTask?.let { mainHandler.removeCallbacks(it) }
            permissionRefreshTask = null
            onboardingOverlay?.let { overlayHost?.removeView(it) }
            onboardingOverlay = null
            currentOnboardingStep = null
        }

        fun refreshOnboardingIfVisible() {
            val step = currentOnboardingStep ?: return
            if (onboardingOverlay != null && step == 3) showOnboardingStep(step)
        }

        private fun readSavedOnboardingScale(): String? {
            return try {
                val file = File(activity.dataDir, "omarchy/root/.config/omarchy/display-scale")
                if (!file.isFile || file.length() !in 1L..32L) return null
                // Bound the read as well as the initial size check in case the file changes.
                val bytes = ByteArray(33)
                val size = file.inputStream().use { input ->
                    var total = 0
                    while (total < bytes.size) {
                        val count = input.read(bytes, total, bytes.size - total)
                        if (count <= 0) break
                        total += count
                    }
                    total
                }
                if (size !in 1..32) return null
                val scale = String(bytes, 0, size, Charsets.US_ASCII).trim().toBigDecimalOrNull()
                    ?: return null
                listOf("1.0", "1.25", "1.5", "1.75").firstOrNull {
                    scale.compareTo(it.toBigDecimal()) == 0
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun onboardingProgress() = OnboardingProgress(
            completed = prefs.getBoolean(ONBOARDING_COMPLETE, false),
            optedOut = prefs.getBoolean(ONBOARDING_OPT_OUT, false)
        )

        private fun closeOnboarding(action: OnboardingExit) {
            val readiness = onboardingReadiness ?: return
            if (readiness.state != OnboardingReadiness.State.READY) return
            if (action == OnboardingExit.COMPLETE) readiness.finish()
            val before = onboardingProgress()
            val after = before.after(action)
            if (before != after) {
                val editor = prefs.edit()
                if (before.completed != after.completed) editor.putBoolean(ONBOARDING_COMPLETE, after.completed)
                if (before.optedOut != after.optedOut) editor.putBoolean(ONBOARDING_OPT_OUT, after.optedOut)
                editor.apply()
            }
            cancelOnboardingProbe()
            hideOnboarding()
        }

        private fun showOnboardingStep(step: Int) {
            val host = overlayHost ?: return
            hideOnboarding()
            currentOnboardingStep = step

            val w = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val h = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

            val landscape = isLandscapeNow()
            val compact = landscape
            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
                isClickable = true
            }
            val arrowView = GuideArrowOverlay(activity)
            root.addView(arrowView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = makeRounded("#F81a1b26", "#7aa2f7", 16, 1)
                setPadding(dp(18), dp(if (compact) 8 else 16), dp(18), dp(if (compact) 8 else 16))
                elevation = dp(20).toFloat()
            }
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(if (compact) 2 else 8))
            }
            val dots = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = s(R.string.omarchy_onboarding_step, step + 1, 4)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            repeat(4) { index ->
                dots.addView(View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (index == step) "#7AA2F7" else "#414868"))
                        cornerRadius = dp(4).toFloat()
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(if (index == step) 24 else 7), dp(7)).apply {
                    marginEnd = dp(6)
                })
            }
            header.addView(dots, LinearLayout.LayoutParams(0, dp(28), 1f))
            header.addView(TextView(activity).apply {
                text = s(R.string.omarchy_onboarding_skip)
                textSize = 12f
                gravity = Gravity.CENTER
                minHeight = dp(if (compact) 32 else 40)
                setTextColor(Color.parseColor("#9aa5ce"))
                setPadding(dp(12), 0, dp(4), 0)
                setOnClickListener { closeOnboarding(OnboardingExit.SKIP) }
            })
            card.addView(header)

            fun addTitle(id: Int) {
                card.addView(TextView(activity).apply {
                    text = s(id)
                    textSize = if (compact) 15f else 16.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#7aa2f7"))
                    setPadding(0, 0, 0, dp(if (compact) 4 else 8))
                })
            }
            fun addBody(id: Int) {
                card.addView(TextView(activity).apply {
                    text = s(id)
                    textSize = if (compact) 12f else 13f
                    setTextColor(Color.parseColor("#c0caf5"))
                    setPadding(0, 0, 0, dp(if (compact) 6 else 12))
                })
            }

            fun addGrid(items: List<View>) {
                items.chunked(2).forEach { cells ->
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    cells.forEachIndexed { index, cell ->
                        row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index == 0) marginEnd = dp(8)
                        })
                    }
                    card.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(if (compact) 5 else 8) })
                }
            }

            when (step) {
                0 -> {
                    addTitle(R.string.omarchy_onboarding_scale_title)
                    addBody(R.string.omarchy_onboarding_scale_desc)
                    if (!onboardingScaleInitialized) {
                        selectedOnboardingScale = readSavedOnboardingScale()
                        onboardingScaleInitialized = true
                    }
                    val scales = listOf(
                        "1.0" to R.string.omarchy_onboarding_scale_100,
                        "1.25" to R.string.omarchy_onboarding_scale_125,
                        "1.5" to R.string.omarchy_onboarding_scale_150,
                        "1.75" to R.string.omarchy_onboarding_scale_175
                    )
                    val scaleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    val scaleButtons = mutableListOf<Pair<String, Button>>()
                    fun refreshScaleButtons() {
                        scaleButtons.forEach { (value, button) ->
                            val active = value == selectedOnboardingScale
                            button.isSelected = active
                            button.setTextColor(Color.parseColor(if (active) "#1a1b26" else "#c0caf5"))
                            button.background = makeRounded(
                                if (active) "#7aa2f7" else "#CC24283b",
                                if (active) "#7aa2f7" else "#414868", 10, 1
                            )
                        }
                    }
                    scales.forEachIndexed { index, (value, label) ->
                        val button = makeBarBtn(s(label), value == selectedOnboardingScale) {
                            selectedOnboardingScale = value
                            refreshScaleButtons()
                            sendCmd("scale_$value")
                        }.apply {
                            textSize = 12f
                            setPadding(dp(2), 0, dp(2), 0)
                        }
                        scaleButtons.add(value to button)
                        scaleRow.addView(button, LinearLayout.LayoutParams(0, dp(if (compact) 36 else 44), 1f).apply {
                            if (index < scales.lastIndex) marginEnd = dp(6)
                        })
                    }
                    refreshScaleButtons()
                    card.addView(scaleRow, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(if (compact) 6 else 14) })
                    arrowView.showArrow = true
                    arrowView.targetX = w * 0.79f
                    arrowView.targetY = dp(16).toFloat()
                }
                1 -> {
                    addTitle(R.string.omarchy_onboarding_handle_title)
                    addBody(R.string.omarchy_onboarding_handle_desc)
                    addGrid(listOf(
                        R.string.omarchy_onboarding_tap to R.string.omarchy_onboarding_dock,
                        R.string.omarchy_onboarding_double_tap to R.string.omarchy_onboarding_keyboard,
                        R.string.omarchy_onboarding_long_press to R.string.omarchy_onboarding_voice_ime,
                        R.string.omarchy_onboarding_drag to R.string.omarchy_onboarding_snap
                    ).map { (gesture, action) ->
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = makeRounded("#CC24283b", "#414868", 12, 1)
                            setPadding(dp(10), dp(if (compact) 7 else 10), dp(10), dp(if (compact) 7 else 10))
                            minimumHeight = dp(if (compact) 38 else 48)
                            addView(TextView(activity).apply {
                                text = s(gesture)
                                textSize = 12f
                                setTypeface(typeface, Typeface.BOLD)
                                setTextColor(Color.parseColor("#7aa2f7"))
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(TextView(activity).apply {
                                text = s(action)
                                textSize = 11.5f
                                setTextColor(Color.parseColor("#c0caf5"))
                            })
                        }
                    })
                    arrowView.showArrow = true
                    arrowView.targetX = if (dockedRight) (w - dp(12)).toFloat() else dp(12).toFloat()
                    arrowView.targetY = edgeTabView?.let { it.y + it.height / 2f }
                        ?: OverlayBounds.vertical(h * dockYFraction, h, 0, dp(60), dp(60))
                }
                2 -> {
                    addTitle(R.string.omarchy_onboarding_shortcuts_title)
                    addBody(R.string.omarchy_onboarding_shortcuts_desc)
                    fun keycap(id: Int): TextView = TextView(activity).apply {
                        text = s(id)
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        setTextColor(Color.parseColor("#c0caf5"))
                        background = makeRounded("#CC283457", "#7aa2f7", 6, 1)
                        setPadding(dp(4), dp(if (compact) 3 else 5), dp(4), dp(if (compact) 3 else 5))
                    }
                    addGrid(listOf(
                        R.string.omarchy_key_k to R.string.omarchy_onboarding_shortcut_help,
                        R.string.omarchy_key_space to R.string.omarchy_onboarding_shortcut_apps,
                        R.string.omarchy_key_enter to R.string.omarchy_onboarding_shortcut_terminal,
                        R.string.omarchy_key_w to R.string.omarchy_onboarding_shortcut_close
                    ).map { (key, action) ->
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(4), dp(2), dp(4), dp(2))
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(keycap(R.string.omarchy_key_super))
                                addView(TextView(activity).apply {
                                    text = s(R.string.omarchy_onboarding_key_or)
                                    setTextColor(Color.parseColor("#9aa5ce"))
                                })
                                addView(keycap(R.string.omarchy_key_alt))
                                addView(TextView(activity).apply {
                                    text = s(R.string.omarchy_onboarding_key_plus)
                                    setTextColor(Color.parseColor("#9aa5ce"))
                                })
                                addView(keycap(key))
                            })
                            addView(TextView(activity).apply {
                                text = s(action)
                                textSize = 11.5f
                                setTextColor(Color.parseColor("#c0caf5"))
                                setPadding(0, dp(2), 0, 0)
                            })
                        }
                    })
                    card.addView(Button(activity).apply {
                        text = s(R.string.omarchy_onboarding_preview_shortcuts)
                        isAllCaps = false
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#7aa2f7"))
                        background = makeRounded("#CC283457", "#7aa2f7", 8, 1)
                        setOnClickListener {
                            sendCmd("keybindings")
                            closeOnboarding(OnboardingExit.PREVIEW)
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(if (compact) 36 else 44)
                    ).apply { bottomMargin = dp(if (compact) 5 else 10) })
                }
                else -> {
                    addTitle(R.string.omarchy_onboarding_permissions_title)
                    val doubaoReady = isDoubaoInstalled()
                    val imeBox = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = makeRounded(if (doubaoReady) "#229ece6a" else "#227aa2f7", if (doubaoReady) "#9ece6a" else "#7aa2f7", 10, 1)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(if (compact) 5 else 10) }
                    }
                    val imeCopy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    imeBox.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    imeBox.gravity = Gravity.CENTER_VERTICAL
                    imeCopy.addView(TextView(activity).apply {
                        text = s(if (doubaoReady) R.string.omarchy_onboarding_ime_installed else R.string.omarchy_onboarding_ime_install)
                        textSize = if (compact) 12f else 13f
                        setTextColor(Color.parseColor(if (doubaoReady) "#9ece6a" else "#7aa2f7"))
                    })
                    imeCopy.addView(TextView(activity).apply {
                        text = s(if (doubaoReady) R.string.omarchy_onboarding_ime_installed_desc else R.string.omarchy_onboarding_ime_install_desc)
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#c0caf5"))
                        setPadding(0, dp(2), 0, dp(6))
                    })
                    imeBox.addView(imeCopy, LinearLayout.LayoutParams(
                        if (compact) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, if (compact) 1f else 0f
                    ))
                    imeBox.addView(Button(activity).apply {
                        text = s(if (doubaoReady) R.string.omarchy_onboarding_ime_choose else R.string.omarchy_onboarding_ime_download)
                        isAllCaps = false
                        textSize = 12f
                        setTextColor(Color.parseColor("#1a1b26"))
                        background = makeRounded(if (doubaoReady) "#9ece6a" else "#7aa2f7", "#7aa2f7", 8, 1)
                        setOnClickListener {
                            if (doubaoReady) openInputMethodPicker()
                            else activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOUBAO_URL)))
                        }
                    }, LinearLayout.LayoutParams(if (compact) dp(160) else LinearLayout.LayoutParams.MATCH_PARENT, dp(if (compact) 36 else 44)))
                    card.addView(imeBox)

                    val permRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    fun permissionButton(granted: Boolean, grantedLabel: Int, grantLabel: Int, action: () -> Unit) {
                        permRow.addView(Button(activity).apply {
                            text = s(if (granted) grantedLabel else grantLabel)
                            isAllCaps = false
                            textSize = 11f
                            setPadding(dp(4), 0, dp(4), 0)
                            minWidth = 0; minimumWidth = 0
                            setTextColor(Color.parseColor(if (granted) "#9ece6a" else "#c0caf5"))
                            background = makeRounded("#CC24283b", if (granted) "#9ece6a" else "#414868", 8, 1)
                            setOnClickListener { action() }
                        }, LinearLayout.LayoutParams(0, dp(if (compact) 40 else 52), 1f).apply {
                            if (permRow.childCount < 2) marginEnd = dp(6)
                        })
                    }
                    val filesOk = hasAllFilesPermission()
                    val batOk = isBatteryUnrestricted()
                    val micOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    permissionButton(filesOk, R.string.omarchy_onboarding_storage_granted, R.string.omarchy_onboarding_storage_grant) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            })
                        }
                    }
                    permissionButton(batOk, R.string.omarchy_onboarding_battery_granted, R.string.omarchy_onboarding_battery_grant) {
                        activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        })
                    }
                    permissionButton(micOk, R.string.omarchy_onboarding_microphone_granted, R.string.omarchy_onboarding_microphone_grant) {
                        if (!micOk) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 8194)
                    }
                    card.addView(permRow)
                    // The embedded Termux activity owns the permission callback. Observe the grant
                    // while this step is visible so the checkmark updates immediately after it.
                    permissionRefreshTask = object : Runnable {
                        override fun run() {
                            if (!attached || currentOnboardingStep != 3) return
                            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted != micOk) showOnboardingStep(3)
                            else mainHandler.postDelayed(this, 400L)
                        }
                    }.also { mainHandler.postDelayed(it, 400L) }

                }
            }

            val navRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
            }
            if (step > 0) {
                navRow.addView(Button(activity).apply {
                    text = s(R.string.omarchy_onboarding_previous)
                    isAllCaps = false
                    textSize = if (compact) 12f else 13f
                    setTextColor(Color.parseColor("#c0caf5"))
                    background = makeRounded("#CC24283b", "#414868", 8, 1)
                    setOnClickListener { showOnboardingStep(step - 1) }
                }, LinearLayout.LayoutParams(dp(86), dp(if (compact) 36 else 44)).apply { marginEnd = dp(8) })
            }
            navRow.addView(Button(activity).apply {
                text = s(if (step < 3) R.string.omarchy_onboarding_next else R.string.omarchy_onboarding_finish)
                isAllCaps = false
                textSize = 13.5f
                setTextColor(Color.parseColor("#1a1b26"))
                background = makeRounded("#7aa2f7", "#7aa2f7", 8, 1)
                setOnClickListener {
                    if (step < 3) showOnboardingStep(step + 1) else closeOnboarding(OnboardingExit.COMPLETE)
                }
            }, LinearLayout.LayoutParams(0, dp(if (compact) 36 else 44), 1f))
            card.addView(navRow)

            val cardW = min(max(1, w - dp(if (compact) 64 else 32)), dp(if (compact) 600 else 390))
            // Measure the complete content without a height cap. If font size or device
            // density leaves less space, fit the whole card, including navigation, into
            // the viewport. No child or Finish button is clipped behind a scroll area.
            card.measure(
                View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val cardH = max(1, card.measuredHeight)
            val topSpace = dp(if (compact) 36 else 52)
            val bottomSpace = dp(12)
            val availableH = max(1, h - topSpace - bottomSpace)
            val fit = min(1f, availableH.toFloat() / cardH)
            card.scaleX = fit
            card.scaleY = fit
            card.translationY = (topSpace - bottomSpace) / 2f
            root.addView(card, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))
            host.addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            onboardingOverlay = root
            // Begin the quadratic arrow just outside the measured card so it never crosses its copy.
            card.post {
                if (onboardingOverlay === root && arrowView.showArrow) {
                    val left = card.x + card.width * (1f - fit) / 2f
                    val top = card.y + card.height * (1f - fit) / 2f
                    arrowView.fromX = if (step == 0) left + card.width * fit * 0.72f
                        else if (dockedRight) left + card.width * fit else left
                    arrowView.fromY = if (step == 0) top - dp(6) else top + card.height * fit * 0.5f
                    arrowView.invalidate()
                }
            }
        }

        // ==================== Invisible Doubao IME Bridge ====================

        private fun hideImeAccessory() {
            imeAccessoryView?.let { overlayHost?.removeView(it) }
            imeAccessoryView = null
            accessoryModBtns.clear()
        }

        private fun updateImeAccessory() {
            if (!attached || isLandscapeNow() || lastImeH == 0 || oskVisible) {
                hideImeAccessory()
                return
            }
            val host = overlayHost ?: return
            val existing = imeAccessoryView
            if (existing != null) {
                val lp = existing.layoutParams as FrameLayout.LayoutParams
                if (lp.bottomMargin != lastImeH) {
                    lp.bottomMargin = lastImeH
                    existing.layoutParams = lp
                }
                return
            }
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = makeRounded("#F21a1b26", "#414868", 8, 1)
                elevation = dp(10).toFloat()
            }
            val keys = listOf(
                s(R.string.omarchy_key_esc) to "Escape", s(R.string.omarchy_key_tab) to "Tab",
                s(R.string.omarchy_key_super) to "MOD:super", s(R.string.omarchy_key_ctrl) to "MOD:ctrl",
                s(R.string.omarchy_key_alt) to "MOD:alt", "↑" to "Up", "↓" to "Down",
                "←" to "Left", "→" to "Right", "⌨" to "OSK", "✕" to "HIDE"
            )
            keys.forEach { (label, code) ->
                val button = Button(activity).apply {
                    text = label
                    isAllCaps = false
                    textSize = if (label.length > 3) 9.5f else 11f
                    setPadding(0, 0, 0, 0)
                    minWidth = 0; minimumWidth = 0
                    minHeight = 0; minimumHeight = 0
                    setTextColor(Color.parseColor("#c0caf5"))
                    background = makeRounded("#CC24283b", "#414868", 5, 0)
                    if (code == "OSK") contentDescription = s(R.string.omarchy_overlay_keyboard_toggle)
                    if (code == "HIDE") contentDescription = s(R.string.omarchy_overlay_ime_hide)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        when (code) {
                            "MOD:super" -> { modSuper = !modSuper; refreshModButtons() }
                            "MOD:ctrl" -> { modCtrl = !modCtrl; refreshModButtons() }
                            "MOD:alt" -> { modAlt = !modAlt; refreshModButtons() }
                            "OSK" -> showOsk()
                            "HIDE" -> hideIme()
                            else -> sendKey(code)
                        }
                    }
                }
                if (code.startsWith("MOD:")) accessoryModBtns[code.substring(4)] = button
                bar.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            host.addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM
            ).apply { bottomMargin = lastImeH })
            imeAccessoryView = bar
            refreshModButtons()
            edgeTabView?.bringToFront()
        }

        private fun hideIme() {
            try {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(imeAnchor?.windowToken ?: overlayHost?.windowToken, 0)
                imeAnchor?.let { overlayHost?.removeView(it) }
            } catch (_: Exception) {}
            imeAnchor = null
            imeActive = false
            hideImeAccessory()
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
                if (!attached || imeAnchor !== edit || !imeActive) return@postDelayed
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 90L)
        }

        // ==================== Floating 0.70x Ergonomic Keyboard ====================

        private fun hideOsk() {
            oskView?.let { overlayHost?.removeView(it) }
            oskView = null
            fullscreenBtn = null
            oskVisible = false
            modBtns.clear()
            updateImeAccessory()
        }

        private fun refreshFullscreenButton() {
            val b = fullscreenBtn ?: return
            b.text = if (fullscreenActive) s(R.string.omarchy_overlay_exit_fullscreen) else s(R.string.omarchy_overlay_enter_fullscreen)
            b.setTextColor(Color.parseColor(if (fullscreenActive) "#1a1b26" else "#c0caf5"))
            b.background = makeRounded(if (fullscreenActive) "#7aa2f7" else "#CC24283b", "#7aa2f7", 6, 1)
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
            oskVisible = true
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

            // Clean top bar: [1][2][3][4][5] [⛶ 全屏]  [drag space]  [refined opacity slider]  [✕]
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(5))
            }

            for (ws in 1..5) {
                val w = ws.toString()
                val wb = makeBarBtn(w, false) {
                    fullscreenActive = false
                    refreshFullscreenButton()
                    sendCmd("ws_$w")
                }
                wb.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    setMargins(dp(1), 0, dp(2), 0)
                }
                wb.contentDescription = s(R.string.omarchy_overlay_workspace, ws)
                wb.setPadding(0, 0, 0, 0)
                bar.addView(wb)
            }

            val bFull = makeBarBtn(if (fullscreenActive) s(R.string.omarchy_overlay_exit_fullscreen) else s(R.string.omarchy_overlay_enter_fullscreen), fullscreenActive) {
                fullscreenActive = !fullscreenActive
                refreshFullscreenButton()
                sendCmd("fullscreen")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(28)).apply {
                    setMargins(dp(4), 0, dp(2), 0)
                }
                setPadding(dp(4), 0, dp(4), 0)
            }
            fullscreenBtn = bFull
            bar.addView(bFull)

            val dragSpacer = View(activity).apply { setOnTouchListener(dragTouchListener) }
            bar.addView(dragSpacer, LinearLayout.LayoutParams(0, dp(28), 1f))

            val alphaBar = SeekBar(activity).apply {
                contentDescription = s(R.string.omarchy_overlay_keyboard_opacity)
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

            val bClose = makeBarBtn(s(R.string.omarchy_overlay_dismiss_symbol), false) { hideOsk() }.apply {
                contentDescription = s(R.string.omarchy_overlay_keyboard_hide)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(28)).apply {
                    setMargins(dp(2), 0, 0, 0)
                }
                setPadding(0, 0, 0, 0)
            }
            bar.addView(bClose)
            root.addView(bar)

            val rows = arrayOf(
                arrayOf(Triple(s(R.string.omarchy_key_esc),"Escape",1.1f), Triple(s(R.string.omarchy_key_tab),"Tab",1.1f), Triple(s(R.string.omarchy_key_super),"MOD:super",1.4f), Triple(s(R.string.omarchy_key_ctrl),"MOD:ctrl",1.2f), Triple(s(R.string.omarchy_key_alt),"MOD:alt",1.1f), Triple("←","Left",1.0f), Triple("↓","Down",1.0f), Triple("↑","Up",1.0f), Triple("→","Right",1.0f), Triple("⌫","BackSpace",1.4f)),
                arrayOf(Triple("1","1",1f), Triple("2","2",1f), Triple("3","3",1f), Triple("4","4",1f), Triple("5","5",1f), Triple("6","6",1f), Triple("7","7",1f), Triple("8","8",1f), Triple("9","9",1f), Triple("0","0",1f), Triple("-","minus",1f), Triple("=","equal",1f)),
                arrayOf(Triple("q","q",1f), Triple("w","w",1f), Triple("e","e",1f), Triple("r","r",1f), Triple("t","t",1f), Triple("y","y",1f), Triple("u","u",1f), Triple("i","i",1f), Triple("o","o",1f), Triple("p","p",1f), Triple("[","bracketleft",1f), Triple("]","bracketright",1f)),
                arrayOf(Triple("a","a",1f), Triple("s","s",1f), Triple("d","d",1f), Triple("f","f",1f), Triple("g","g",1f), Triple("h","h",1f), Triple("j","j",1f), Triple("k","k",1f), Triple("l","l",1f), Triple(";","semicolon",1f), Triple("'","apostrophe",1f), Triple("⏎","Return",1.5f)),
                arrayOf(Triple("⇧","MOD:shift",1.4f), Triple("z","z",1f), Triple("x","x",1f), Triple("c","c",1f), Triple("v","v",1f), Triple("b","b",1f), Triple("n","n",1f), Triple("m","m",1f), Triple(",","comma",1f), Triple(".","period",1f), Triple("/","slash",1f), Triple(s(R.string.omarchy_overlay_ime_key),"CMD:ime",1.2f)),
                arrayOf(Triple(s(R.string.omarchy_overlay_apps_key),"CMD:apps",1.3f), Triple(s(R.string.omarchy_overlay_menu_key),"CMD:menu",1.3f), Triple(s(R.string.omarchy_key_space),"space",3.4f), Triple(s(R.string.omarchy_overlay_terminal_key),"CMD:terminal",1.3f), Triple(s(R.string.omarchy_overlay_close_key),"CMD:kill",1.3f))
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
            refreshModButtons()
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
            listOfNotNull(modBtns[name], accessoryModBtns[name]).forEach { button ->
                button.setTextColor(Color.parseColor(if (on) "#1a1b26" else "#7aa2f7"))
                button.background = makeRounded(if (on) "#7aa2f7" else "#CC283457", "#7aa2f7", 6, 1)
            }
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
