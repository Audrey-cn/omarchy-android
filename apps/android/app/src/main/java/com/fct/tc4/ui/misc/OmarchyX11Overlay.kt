package com.fct.tc4.ui.misc

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.text.TextUtils
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fct.tc4.R
import com.offsec.nhterm.backend.TerminalSession
import com.termux.x11.MainActivity as X11Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.InetSocketAddress
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
    // Readiness belongs to a terminal session, so Activity recreation is a warm
    // restore while a newly launched container always gets its own cold splash.
    private var readySession: TerminalSession? = null
    private const val DOUBAO_PKG = "com.bytedance.android.doubaoime"
    private const val DOUBAO_URL = "https://shurufa.doubao.com/"
    private const val ONBOARDING_COMPLETE = "onboarding_v2_done"
    private const val ONBOARDING_OPT_OUT = "onboarding_v2_opted_out"
    private const val ONBOARDING_RESUME_STEP = "onboarding_resume_step"
    private const val ONBOARDING_MANUAL_REPLAY = "onboarding_manual_replay"

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
                    Global.userRequestedManagementPage = false
                    val ctrl = controllers.getOrPut(activity) { Controller(activity) }
                    ctrl.attachIfNeeded()
                    ctrl.restoreOnboardingIfInProgress()
                    ctrl.onResume()
                    suppressLegacyX11Notification(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                controllers[activity]?.onPause()
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                controllers.remove(activity)?.detach()
            }
        })
    }

    /**
     * Suppresses upstream Termux:X11's redundant foreground notification ("Pull down to show options")
     * so only Omarchy's unified desktop session notification (id 2402) and installer notification (id 2401) appear.
     */
    private fun suppressLegacyX11Notification(context: Context) {
        val cancelOnce = Runnable {
            try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return@Runnable
                for (sbn in nm.activeNotifications) {
                    if (sbn.packageName == context.packageName && sbn.id != 2401 && sbn.id != 2402) {
                        nm.cancel(sbn.tag, sbn.id)
                    }
                }
            } catch (_: Throwable) {}
        }
        cancelOnce.run()
        Handler(Looper.getMainLooper()).postDelayed(cancelOnce, 350L)
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
        private val onNewline: () -> Unit,
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
            restoreSentinel()
            setOnEditorActionListener { _, actionId, event ->
                when {
                    isSendAction(actionId) -> {
                        if (event == null || event.action == KeyEvent.ACTION_DOWN) sendEditorAction()
                        true
                    }
                    event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) handleEnterKey(event)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun restoreSentinel() {
            // Never leave the hidden editor empty: Doubao disables Send for empty editors.
            // Skip redundant setText() when sentinel is already in place to avoid triggering
            // InputMethodManager.restartInput() and flickering Doubao's Send button on Backspace.
            val cur = text
            if (cur != null && cur.length == 1 && cur[0] == ImeBridgeHelper.SENTINEL[0] && !composing()) {
                if (selectionStart != 1 || selectionEnd != 1) setSelection(1)
                return
            }
            setText(ImeBridgeHelper.SENTINEL)
            setSelection(length())
        }

        private fun dispatch(events: List<ImeBridgeHelper.Event>) {
            events.forEach { event ->
                when (event) {
                    is ImeBridgeHelper.Event.Text -> onCommit(event.value)
                    ImeBridgeHelper.Event.Newline -> onNewline()
                    ImeBridgeHelper.Event.Return -> onEnter()
                }
            }
        }

        private fun commitToLinux(committed: CharSequence?) {
            val events = ImeBridgeHelper.splitCommit(committed)
            restoreSentinel()
            dispatch(events)
        }

        private fun sendEditorAction() {
            val events = ImeBridgeHelper.send(text)
            restoreSentinel()
            dispatch(events)
        }

        private fun isSendAction(action: Int): Boolean = action in setOf(
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE
        )

        private fun handleEnterKey(event: KeyEvent) {
            if (!event.isShiftPressed && event.flags and KeyEvent.FLAG_EDITOR_ACTION != 0) {
                sendEditorAction()
            } else {
                // Raw/Shift+Enter is a newline; only an explicit editor action submits.
                commitToLinux(text)
                onNewline()
            }
        }

        private fun composing(): Boolean = text?.let {
            BaseInputConnection.getComposingSpanStart(it) != -1
        } ?: false

        private fun restoreIfEmpty() {
            if (ImeBridgeHelper.shouldForwardBackspace(text, composing())) restoreSentinel()
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
                private var forwardedDelete = false

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    commitToLinux(text)
                    return true
                }

                // InputConnectionWrapper delegates the modern overload directly to its
                // target, so it must also be intercepted to avoid buffering invisible text.
                override fun commitText(
                    text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?
                ): Boolean = commitText(text, newCursorPosition)

                override fun finishComposingText(): Boolean {
                    val remaining = this@ImeBridgeEditText.text?.toString()
                    val result = super.finishComposingText()
                    commitToLinux(remaining)
                    return result
                }

                override fun performEditorAction(editorAction: Int): Boolean {
                    if (isSendAction(editorAction)) {
                        sendEditorAction()
                        return true
                    }
                    return super.performEditorAction(editorAction)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0 && ImeBridgeHelper.shouldForwardBackspace(
                            this@ImeBridgeEditText.text, composing())) {
                        repeat(beforeLength) { onBackspace() }
                        restoreSentinel()
                        return true
                    }
                    val result = super.deleteSurroundingText(beforeLength, afterLength)
                    restoreIfEmpty()
                    return result
                }

                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0 && ImeBridgeHelper.shouldForwardBackspace(
                            this@ImeBridgeEditText.text, composing())) {
                        repeat(beforeLength) { onBackspace() }
                        restoreSentinel()
                        return true
                    }
                    val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                    restoreIfEmpty()
                    return result
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (event.action == KeyEvent.ACTION_DOWN) handleEnterKey(event)
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (event.action == KeyEvent.ACTION_DOWN && ImeBridgeHelper.shouldForwardBackspace(
                                this@ImeBridgeEditText.text, composing())) {
                            forwardedDelete = true
                            onBackspace()
                            restoreSentinel()
                            return true
                        }
                        if (event.action == KeyEvent.ACTION_UP && forwardedDelete) {
                            forwardedDelete = false
                            return true
                        }
                    }
                    val result = super.sendKeyEvent(event)
                    restoreIfEmpty()
                    return result
                }
            }
        }
    }

    private class Controller(private val activity: X11Activity) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val netExec = Executors.newSingleThreadExecutor()
        private val prefs = activity.getSharedPreferences("omarchy_overlay_prefs", Context.MODE_PRIVATE)
        private val clipboardHistory = ClipboardHistoryStore(
            initialEntries = loadClipboardHistory(),
            persist = { entries ->
                prefs.edit().putString("clipboard_history", JSONArray(entries).toString()).apply()
            }
        )
        private var lastSyncedAndroidClipboard: String? = null
        private var clipboardCopyGeneration = 0
        private var resumed = false

        @Volatile private var attached = false
        private var overlayHost: FrameLayout? = null
        private var nightOverlay: View? = null
        private var edgeTabView: View? = null
        private var expandedDockView: View? = null
        private var quickMenuOverlay: View? = null
        private var clipboardHistoryOverlay: View? = null
        private var onboardingOverlay: View? = null
        private var onboardingStatus: View? = null
        private var onboardingProbeJob: Job? = null
        private var onboardingReadiness: OnboardingReadiness? = null
        private var desktopLoadingJob: Job? = null
        private var desktopLoadingView: View? = null
        private var desktopLoadingFadeView: View? = null
        private var desktopLoadingText: TextView? = null
        private var desktopLoadingAppearance = DesktopLoadingState.Appearance.HIDDEN
        private var breathingAnimator: ValueAnimator? = null
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
        private var oskCompactMode = prefs.getBoolean("osk_compact_mode", false)
        private var oskSymbolLayer = false
        private var activeWorkspace = 1

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
        private val modBtns = HashMap<String, TextView>()
        private val oskDynamicKeyViews = mutableListOf<Triple<TextView, String, String>>()

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
        // onResume can precede window focus, when Android denies clipboard reads.
        private val clipboardFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && resumed) syncAndroidClipboard()
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
            content.viewTreeObserver.addOnWindowFocusChangeListener(clipboardFocusListener)

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

            if (!restoreOnboardingIfInProgress() && onboardingProgress().shouldShowAutomatically) {
                waitForOnboardingDesktop(showWaitingStatus = false)
            }
        }

        fun detach() {
            attached = false
            onPause()
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
            try { content?.viewTreeObserver?.removeOnWindowFocusChangeListener(clipboardFocusListener) } catch (_: Exception) {}
            overlayHost?.let { content?.removeView(it) }
            overlayHost = null
            nightOverlay = null
            edgeTabView = null
            expandedDockView = null
            quickMenuOverlay = null
            clipboardHistoryOverlay = null
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

        fun onResume() {
            if (!attached) return
            onPause()
            resumed = true
            syncAndroidClipboard()
            val session = Global.terminalSession
            val loading = DesktopLoadingState(
                warmResume = session != null && readySession === session,
                startedAt = SystemClock.elapsedRealtime()
            )
            val readiness = OnboardingReadiness(SystemClock.elapsedRealtime())
            val probe = SwayDesktopProbe(File(activity.filesDir, "tmp"))
            if (!loading.warmResume) showDesktopLoading(DesktopLoadingState.Appearance.SPLASH)
            desktopLoadingJob = CoroutineScope(Dispatchers.Main).launch {
                while (attached && Global.x11SessionActive && Global.terminalSession === session) {
                    val viewport = onboardingViewport()
                    val desktop = if (!loading.warmResume && viewport != null) probe.sample(1_200) else null
                    val currentViewport = onboardingViewport()
                    val desktopReady = readiness.observe(
                        SystemClock.elapsedRealtime(), currentViewport, desktop.takeIf { viewport == currentViewport }
                    ) == OnboardingReadiness.State.READY
                    val appearance = loading.observe(SystemClock.elapsedRealtime(), currentViewport != null, desktopReady)
                    showDesktopLoading(appearance)
                    val status = s(when {
                        loading.warmResume -> R.string.omarchy_resume_desktop
                        Global.terminalSession?.isRunning != true -> R.string.omarchy_startup_container
                        currentViewport == null -> R.string.omarchy_startup_graphics
                        else -> R.string.omarchy_startup_desktop
                    })
                    desktopLoadingText?.let { if (it.text != status) it.text = status }
                    if (loading.ready) {
                        readySession = session
                        return@launch
                    }
                    if (!loading.warmResume && readiness.state == OnboardingReadiness.State.TIMED_OUT) {
                        // Expose desktop controls on a slow or failed startup.
                        // First-run onboarding also keeps its retry/skip status.
                        hideDesktopLoading()
                        return@launch
                    }
                    delay(if (loading.warmResume) 50 else 350)
                }
                hideDesktopLoading()
            }
        }

        fun onPause() {
            resumed = false
            clipboardCopyGeneration++
            desktopLoadingJob?.cancel()
            desktopLoadingJob = null
            hideDesktopLoading(animate = false)
        }

        private fun showDesktopLoading(appearance: DesktopLoadingState.Appearance) {
            if (appearance == desktopLoadingAppearance) return
            if (appearance == DesktopLoadingState.Appearance.HIDDEN) {
                hideDesktopLoading()
                return
            }
            hideDesktopLoading(animate = false)
            val host = overlayHost ?: return
            desktopLoadingAppearance = appearance
            val cold = appearance == DesktopLoadingState.Appearance.SPLASH
            val root = FrameLayout(activity).apply {
                // Cover any elevated status or controls retained across a pause.
                elevation = dp(24).toFloat()
                if (cold) {
                    setBackgroundColor(Color.parseColor("#1A1B26"))
                    isClickable = true
                }
            }
            val content = LinearLayout(activity).apply {
                orientation = if (cold) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                if (!cold) background = makeRounded("#DB1A1B26", "#667AA2F7", 24, 1)
            }
            if (cold) {
                val icon = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                content.addView(icon, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(22) })
                breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1_700
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        icon.alpha = 0.66f + fraction * 0.34f
                        icon.scaleX = 0.96f + fraction * 0.04f
                        icon.scaleY = icon.scaleX
                    }
                    start()
                }
            }
            val progress = ProgressBar(activity, null,
                if (cold) android.R.attr.progressBarStyleHorizontal else android.R.attr.progressBarStyleSmall
            ).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#7AA2F7"))
            }
            content.addView(progress, LinearLayout.LayoutParams(
                if (cold) dp(192) else dp(18), if (cold) dp(3) else dp(18)
            ).apply { if (cold) bottomMargin = dp(16) else marginEnd = dp(10) })
            desktopLoadingText = TextView(activity).apply {
                setText(if (cold) R.string.omarchy_startup_container else R.string.omarchy_resume_desktop)
                setTextColor(Color.parseColor("#C0CAF5"))
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { content.addView(it) }
            root.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                if (cold) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(dp(12), dp(24), dp(12), 0) })
            if (cold) {
                val attribution = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                }
                attribution.addView(AuthorAvatarView(activity), LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginEnd = dp(8)
                })
                attribution.addView(TextView(activity).apply {
                    setText(R.string.omarchy_startup_attribution)
                    typeface = Typeface.MONOSPACE
                    textSize = 10f
                    setTextColor(Color.parseColor("#9AA5CE"))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                root.addView(attribution, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(28) })
            }
            host.addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            desktopLoadingView = root
        }

        private fun hideDesktopLoading(animate: Boolean = true) {
            breathingAnimator?.cancel()
            breathingAnimator = null
            desktopLoadingFadeView?.let {
                it.animate().cancel()
                (it.parent as? ViewGroup)?.removeView(it)
            }
            desktopLoadingFadeView = null
            val view = desktopLoadingView
            desktopLoadingView = null
            desktopLoadingText = null
            desktopLoadingAppearance = DesktopLoadingState.Appearance.HIDDEN
            view?.animate()?.cancel()
            if (view != null) {
                if (animate) {
                    desktopLoadingFadeView = view
                    view.animate().alpha(0f).setDuration(180).withEndAction {
                        (view.parent as? ViewGroup)?.removeView(view)
                        if (desktopLoadingFadeView === view) desktopLoadingFadeView = null
                    }.start()
                }
                else (view.parent as? ViewGroup)?.removeView(view)
            }
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

        private fun writeBridgeLine(socket: Socket, jsonLine: String) {
            socket.connect(InetSocketAddress("127.0.0.1", 18901), 500)
            socket.soTimeout = 1_000
            socket.getOutputStream().apply {
                write((jsonLine + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }

        private fun sendJson(jsonLine: String, onSent: (() -> Unit)? = null) {
            if (!attached || netExec.isShutdown) return
            // detach() may shut the executor down between the guard and execute().
            try {
                netExec.execute {
                    if (!attached) return@execute
                    try {
                        Socket().use { socket -> writeBridgeLine(socket, jsonLine) }
                        if (onSent != null) {
                            mainHandler.post { if (attached) onSent() }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        private fun s(id: Int, vararg args: Any): String = activity.getString(id, *args)

        private fun sendCmd(cmd: String) = sendJson("""{"type":"cmd","cmd":"$cmd"}""")

        private fun sendKey(key: String) {
            sendKeyWithMods(key, modSuper, modCtrl, modAlt, modShift)
            if (modSuper || modCtrl || modAlt || modShift) {
                modSuper = false; modCtrl = false; modAlt = false; modShift = false
                refreshModButtons()
            }
        }

        private fun sendKeyWithMods(
            key: String, superKey: Boolean = false, ctrl: Boolean = false,
            alt: Boolean = false, shift: Boolean = false
        ) {
            sendJson(JSONObject().put("type", "key").put("key", key)
                .put("super", superKey).put("ctrl", ctrl).put("alt", alt).put("shift", shift).toString())
        }

        private fun sendText(text: String) {
            if (text.isEmpty()) return
            // The Linux text bridge uses wl-copy before pasting, changing its clipboard.
            lastSyncedAndroidClipboard = null
            sendJson(JSONObject().put("type", "text").put("text", text).toString()) {
                lastSyncedAndroidClipboard = null
            }
        }

        private fun loadClipboardHistory(): List<String> = try {
            val saved = JSONArray(prefs.getString("clipboard_history", "[]") ?: "[]")
            (0 until saved.length()).mapNotNull { saved.opt(it) as? String }
        } catch (_: Exception) { emptyList() }

        private fun androidClipboardText(): String = try {
            val clip = activity.getSystemService(ClipboardManager::class.java)?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(activity)?.toString().orEmpty()
            else ""
        } catch (_: Exception) { "" }

        private fun syncAndroidClipboard() {
            if (!attached || !resumed || !activity.hasWindowFocus()) return
            val text = androidClipboardText()
            if (text.isEmpty() || text == lastSyncedAndroidClipboard) return
            clipboardHistory.record(text)
            sendJson(JSONObject().put("type", "clipboard_sync").put("text", text).toString()) {
                lastSyncedAndroidClipboard = text
            }
        }

        private fun copyFromLinux() {
            if (!attached || netExec.isShutdown) return
            val request = ++clipboardCopyGeneration
            val previousAndroidText = androidClipboardText()
            try {
                netExec.execute {
                    if (!attached) return@execute
                    try {
                        Socket().use { writeBridgeLine(it, """{"type":"cmd","cmd":"copy"}""") }
                        Thread.sleep(180)
                        if (!attached) return@execute
                        val copied = Socket().use { socket ->
                            writeBridgeLine(socket, """{"type":"clipboard_get"}""")
                            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                            if (response == null) "" else JSONObject(response).optString("text", "")
                        }
                        if (copied.isNotEmpty()) mainHandler.post {
                            if (!attached || !resumed || !activity.hasWindowFocus() ||
                                request != clipboardCopyGeneration || androidClipboardText() != previousAndroidText
                            ) return@post
                            clipboardHistory.record(copied)
                            try {
                                activity.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Omarchy", copied))
                                lastSyncedAndroidClipboard = copied
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        private fun pasteFromAndroid() {
            val text = androidClipboardText()
            if (text.isNotEmpty()) {
                clipboardHistory.record(text)
                sendText(text)
            } else sendCmd("paste")
        }

        private fun configurePasteButton(button: Button) {
            button.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                collapseDock()
                showClipboardHistory()
                true
            }
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
            val paste = makeBarBtn(s(R.string.omarchy_overlay_paste), false) {
                collapseDock()
                pasteFromAndroid()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { setMargins(dp(3), 0, dp(3), 0) }
            }
            configurePasteButton(paste)
            pill.addView(paste)
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

        private fun hideClipboardHistory() {
            clipboardHistoryOverlay?.let { overlayHost?.removeView(it) }
            clipboardHistoryOverlay = null
        }

        private fun showClipboardHistory() {
            val host = overlayHost ?: return
            hideClipboardHistory()
            hideQuickMenu()

            val backdrop = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
                setOnClickListener { hideClipboardHistory() }
                elevation = dp(24).toFloat()
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = makeRounded("#FA1a1b26", "#7aa2f7", 16, 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
            }
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(activity).apply {
                text = s(R.string.omarchy_clipboard_history_title)
                textSize = 16f
                setTextColor(Color.parseColor("#c0caf5"))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val items = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val clear = makeBarBtn(s(R.string.omarchy_clipboard_history_clear), false) {}
            fun refreshItems() {
                items.removeAllViews()
                val entries = clipboardHistory.entries()
                clear.isEnabled = entries.isNotEmpty()
                clear.alpha = if (clear.isEnabled) 1f else 0.45f
                if (entries.isEmpty()) items.addView(TextView(activity).apply {
                    text = s(R.string.omarchy_clipboard_history_empty)
                    textSize = 13f
                    setTextColor(Color.parseColor("#9aa5ce"))
                    setPadding(dp(8), dp(24), dp(8), dp(24))
                })
                entries.forEach { entry ->
                    items.addView(TextView(activity).apply {
                        text = entry.take(240).replace('\n', ' ').replace('\r', ' ') +
                            if (entry.length > 240) "…" else ""
                        textSize = 13f
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Color.parseColor("#c0caf5"))
                        background = makeRounded("#CC24283b", "#414868", 8, 1)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(7) }
                        setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            clipboardHistory.record(entry)
                            sendText(entry)
                            hideClipboardHistory()
                        }
                    })
                }
            }
            clear.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                clipboardHistory.clear()
                refreshItems()
            }
            header.addView(clear, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
            ).apply { marginStart = dp(8) })
            header.addView(makeBarBtn("×", false) { hideClipboardHistory() }.apply {
                contentDescription = s(R.string.omarchy_clipboard_history_close)
                textSize = 20f
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
            card.addView(header)
            refreshItems()
            card.addView(ScrollView(activity).apply { addView(items) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            val availableHeight = (host.height - lastImeH).coerceAtLeast(dp(100))
            backdrop.addView(card, FrameLayout.LayoutParams(
                min(host.width - dp(24), dp(420)).coerceAtLeast(dp(100)),
                min(availableHeight - dp(24), dp(420)).coerceAtLeast(dp(76)),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(12) })
            host.addView(backdrop, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            clipboardHistoryOverlay = backdrop
        }

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
                Global.userRequestedManagementPage = true
                try {
                    val intent = Intent(activity, Class.forName("com.fct.tc4.ui.main.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    Global.userRequestedManagementPage = false
                }
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

            addMenuItem(s(R.string.omarchy_stop_desktop_menu), s(R.string.omarchy_stop_desktop_confirm), "#f7768e") {
                android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.omarchy_stop_desktop_menu)
                    .setMessage(R.string.omarchy_stop_desktop_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.omarchy_stop_desktop_menu) { _, _ ->
                        OmarchyDesktopShutdown.requestStop()
                    }.show()
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

        private fun waitForOnboardingDesktop() = waitForOnboardingDesktop(showWaitingStatus = true)

        private fun waitForOnboardingDesktop(showWaitingStatus: Boolean) {
            if (restoreOnboardingIfInProgress()) return
            // An explicitly replayed guide must also survive Activity recreation.
            prefs.edit().putBoolean(ONBOARDING_MANUAL_REPLAY, !onboardingProgress().shouldShowAutomatically).apply()
            cancelOnboardingProbe()
            hideOnboarding()
            selectedOnboardingScale = null
            onboardingScaleInitialized = false
            val readiness = OnboardingReadiness(SystemClock.elapsedRealtime())
            onboardingReadiness = readiness
            if (showWaitingStatus) showOnboardingStatus(timedOut = false)
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
                            readySession = Global.terminalSession
                            desktopLoadingJob?.cancel()
                            desktopLoadingJob = null
                            hideDesktopLoading()
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

        fun restoreOnboardingIfInProgress(): Boolean {
            if (!attached || overlayHost == null) return false
            val step = onboardingProgress().stepToResume(
                savedStep = prefs.getInt(ONBOARDING_RESUME_STEP, -1),
                visibleStep = currentOnboardingStep.takeIf { onboardingOverlay != null },
                manualReplay = prefs.getBoolean(ONBOARDING_MANUAL_REPLAY, false)
            ) ?: return false
            // The guide was already ready before Settings opened; don't restart its probe.
            // Rebuilding the exact step samples storage, battery and microphone live.
            cancelOnboardingProbe()
            showOnboardingStep(step)
            return true
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
            if (onboardingOverlay == null || currentOnboardingStep == null) return
            if (action == OnboardingExit.COMPLETE) onboardingReadiness?.finish()
            val before = onboardingProgress()
            val after = before.after(action)
            prefs.edit()
                .putBoolean(ONBOARDING_COMPLETE, after.completed)
                .putBoolean(ONBOARDING_OPT_OUT, after.optedOut)
                .putInt(ONBOARDING_RESUME_STEP, -1)
                .putBoolean(ONBOARDING_MANUAL_REPLAY, false)
                .apply()
            cancelOnboardingProbe()
            hideOnboarding()
        }

        private fun showOnboardingStep(step: Int) {
            if (step !in 0..3) return
            val host = overlayHost ?: return
            hideOnboarding()
            currentOnboardingStep = step
            prefs.edit().putInt(ONBOARDING_RESUME_STEP, step).apply()

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
                clipToPadding = false
                clipChildren = false
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
                clipToPadding = false
                clipChildren = false
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            if (step > 0) {
                navRow.addView(Button(activity).apply {
                    text = s(R.string.omarchy_onboarding_previous)
                    isAllCaps = false
                    textSize = if (compact) 12f else 13f
                    setTextColor(Color.parseColor("#c0caf5"))
                    background = makeRounded("#CC24283b", "#414868", 8, 1)
                    setOnClickListener { showOnboardingStep(step - 1) }
                }, LinearLayout.LayoutParams(dp(86), dp(if (compact) 40 else 44)).apply { marginEnd = dp(8) })
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
            }, LinearLayout.LayoutParams(0, dp(if (compact) 40 else 44), 1f))
            card.addView(navRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })

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
                onEnter = { sendKeyWithMods("Return") },
                onNewline = { sendKeyWithMods("Return", shift = true) },
                onDismiss = { hideIme() }
            )

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
            oskSymbolLayer = false
            modBtns.clear()
            oskDynamicKeyViews.clear()
            updateImeAccessory()
        }

        private fun refreshFullscreenButton() {
            val b = fullscreenBtn ?: return
            b.text = if (fullscreenActive) s(R.string.omarchy_overlay_exit_fullscreen) else s(R.string.omarchy_overlay_enter_fullscreen)
            b.setTextColor(Color.parseColor(if (fullscreenActive) "#1a1b26" else "#a9b1d6"))
            b.background = makeRounded(
                if (fullscreenActive) "#7aa2f7" else "#14FFFFFF",
                if (fullscreenActive) "#7aa2f7" else "#00000000",
                6, 1
            )
        }

        private fun bgWithAlpha(alpha: Float): Int {
            val a = max(36, min(242, (alpha * 255f).roundToInt()))
            return Color.argb(a, 0x1a, 0x1b, 0x26)
        }

        private fun styleOpacitySlider(sb: SeekBar) {
            val bgTrack = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#447aa2f7"))
                setSize(-1, dp(3))
            }
            val fgTrack = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#7aa2f7"))
                setSize(-1, dp(3))
            }
            val layers = LayerDrawable(arrayOf(bgTrack, ClipDrawable(fgTrack, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            val thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(11), dp(11))
                setColor(Color.parseColor("#c0caf5"))
                setStroke(dp(1), Color.parseColor("#7aa2f7"))
            }
            sb.progressDrawable = layers
            sb.thumb = thumb
            sb.splitTrack = false
            sb.setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        private data class OskKey(
            val label: String,
            val code: String,
            val weight: Float = 1f,
            val shiftLabel: String? = null,
            val subLabel: String? = null,
            val longPressCode: String? = null,
            val forceShift: Boolean = false,
            val tone: String = "normal" // normal | mod | ctrl | sym | accent
        )

        private fun showOsk() {
            if (oskVisible) return
            val host = overlayHost ?: return
            oskVisible = true
            oskSymbolLayer = false
            hideIme()
            // Keep the compact edge tab collapsed at edge instead of hiding it!
            collapseDock()
            modBtns.clear()
            oskDynamicKeyViews.clear()
            val screenW = activity.resources.displayMetrics.widthPixels
            val screenH = activity.resources.displayMetrics.heightPixels
            val width = if (screenW > screenH) {
                min(screenW - dp(16), (screenW * 0.56f).roundToInt())
            } else {
                min(screenW - dp(12), (screenW * 0.94f).roundToInt())
            }
            val compactBar = width < dp(460)

            val rootBg = GradientDrawable().apply {
                setColor(bgWithAlpha(oskAlpha))
                setStroke(dp(1), Color.parseColor("#887aa2f7"))
                cornerRadius = dp(14).toFloat()
            }

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rootBg
                setPadding(dp(7), dp(6), dp(7), dp(2))
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

            val contentHost = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            root.addView(contentHost, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val repeatableKeys = setOf("BackSpace", "Left", "Down", "Up", "Right")
            val keyRepeatInitialDelayMs = 350L
            val keyRepeatIntervalMs = 55L
            val keyH = dp((36f * 0.72f).roundToInt())

            lateinit var renderOskContent: () -> Unit

            fun triggerKey(key: OskKey) {
                when {
                    key.code.startsWith("MOD:") -> {
                        when (key.code.substring(4)) {
                            "super" -> modSuper = !modSuper
                            "ctrl" -> modCtrl = !modCtrl
                            "alt" -> modAlt = !modAlt
                            "shift" -> modShift = !modShift
                        }
                        refreshModButtons()
                    }
                    key.code == "MODE:sym" -> {
                        oskSymbolLayer = !oskSymbolLayer
                        modShift = false
                        renderOskContent()
                    }
                    key.code.startsWith("CMD:") -> sendCmd(key.code.substring(4))
                    key.forceShift -> {
                        sendKeyWithMods(key.code, modSuper, modCtrl, modAlt, shift = true)
                        if (modSuper || modCtrl || modAlt || modShift) {
                            modSuper = false; modCtrl = false; modAlt = false; modShift = false
                            refreshModButtons()
                        }
                    }
                    else -> sendKey(key.code)
                }
            }

            fun buildKeyRow(keys: List<OskKey>): LinearLayout {
                val rowView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (key in keys) {
                    val isRepeatable = key.code in repeatableKeys
                    val isMod = key.code.startsWith("MOD:")
                    val isSymToggleActive = key.code == "MODE:sym" && oskSymbolLayer
                    val (bgHex, strokeHex, textHex) = when {
                        isSymToggleActive -> Triple("#7aa2f7", "#7aa2f7", "#1a1b26")
                        key.tone == "mod" || isMod -> Triple("#CC283457", "#887aa2f7", "#7aa2f7")
                        key.tone == "accent" -> Triple("#387aa2f7", "#7aa2f7", "#7dcfff")
                        key.tone == "sym" -> Triple("#24bb9af7", "#66bb9af7", "#bb9af7")
                        key.tone == "ctrl" -> Triple("#CC1e2132", "#343a56", "#a9b1d6")
                        else -> Triple("#CC24283b", "#3b4261", "#c0caf5")
                    }

                    val cell = FrameLayout(activity).apply {
                        background = makeRounded(bgHex, strokeHex, 6, 1)
                        isClickable = true
                        isFocusable = false
                    }
                    val mainLabel = TextView(activity).apply {
                        text = if (modShift && key.shiftLabel != null) key.shiftLabel else key.label
                        textSize = if (oskCompactMode && keys.size >= 14) 10f else 11f
                        setTextColor(Color.parseColor(textHex))
                        if (key.tone == "accent" || isSymToggleActive) setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setSingleLine(true)
                    }
                    cell.addView(mainLabel, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    ))

                    if (key.subLabel != null) {
                        val sub = TextView(activity).apply {
                            text = key.subLabel
                            textSize = 7.5f
                            setTextColor(Color.parseColor("#6b7499"))
                            includeFontPadding = false
                            setPadding(0, dp(1), dp(3), 0)
                        }
                        cell.addView(sub, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ))
                    }

                    if (key.shiftLabel != null) {
                        oskDynamicKeyViews.add(Triple(mainLabel, key.label, key.shiftLabel))
                    }
                    if (isMod) {
                        modBtns[key.code.substring(4)] = mainLabel
                    }

                    if (isRepeatable) {
                        var repeating = false
                        val repeatTask = object : Runnable {
                            override fun run() {
                                if (!repeating || !cell.isAttachedToWindow) return
                                sendKey(key.code)
                                mainHandler.postDelayed(this, keyRepeatIntervalMs)
                            }
                        }
                        fun stopRepeat() {
                            repeating = false
                            mainHandler.removeCallbacks(repeatTask)
                            cell.isPressed = false
                        }
                        cell.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {}
                            override fun onViewDetachedFromWindow(v: View) { stopRepeat() }
                        })
                        cell.setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    stopRepeat()
                                    repeating = true
                                    v.isPressed = true
                                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    sendKey(key.code)
                                    mainHandler.postDelayed(repeatTask, keyRepeatInitialDelayMs)
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val inside = event.x >= 0f && event.x <= v.width &&
                                        event.y >= 0f && event.y <= v.height
                                    if (!inside && repeating) stopRepeat()
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    stopRepeat()
                                    true
                                }
                                else -> false
                            }
                        }
                    } else {
                        cell.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerKey(key)
                        }
                        if (key.longPressCode != null) {
                            cell.setOnLongClickListener {
                                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                sendKey(key.longPressCode)
                                true
                            }
                        }
                    }

                    val klp = LinearLayout.LayoutParams(0, keyH, key.weight).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                    rowView.addView(cell, klp)
                }
                return rowView
            }

            fun makeCloseWindowBarBtn(compact: Boolean): Button =
                makeBarBtn(s(R.string.omarchy_overlay_close_key), false) {
                    android.widget.Toast.makeText(
                        activity,
                        s(R.string.omarchy_overlay_close_hold_hint),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }.apply {
                    setTextColor(Color.parseColor("#f7768e"))
                    background = makeRounded("#1EF7768E", "#66F7768E", 6, 1)
                    setSingleLine(true)
                    setPadding(dp(4), 0, dp(4), 0)
                    if (compact) textSize = 10f
                    setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        sendCmd("kill")
                        true
                    }
                }

            renderOskContent = {
                contentHost.removeAllViews()
                modBtns.clear()
                oskDynamicKeyViews.clear()
                fullscreenBtn = null

                // Lightweight top toolbar (subtle pills, only active workspace highlighted)
                val bar = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(4))
                }

                for (ws in 1..5) {
                    val w = ws.toString()
                    val wb = makeBarBtn(w, ws == activeWorkspace) {
                        activeWorkspace = ws
                        fullscreenActive = false
                        sendCmd("ws_$w")
                        renderOskContent()
                    }
                    wb.layoutParams = LinearLayout.LayoutParams(
                        if (compactBar) 0 else dp(24), dp(25), if (compactBar) 0.55f else 0f
                    ).apply {
                        setMargins(dp(1), 0, dp(2), 0)
                    }
                    wb.contentDescription = s(R.string.omarchy_overlay_workspace, ws)
                    wb.setPadding(0, 0, 0, 0)
                    bar.addView(wb)
                }

                if (oskCompactMode) {
                    val bApps = makeBarBtn(s(R.string.omarchy_overlay_apps_key), false) { sendCmd("apps") }
                    val bTerm = makeBarBtn(s(R.string.omarchy_overlay_terminal_key), false) { sendCmd("terminal") }
                    val copy = makeBarBtn(s(R.string.omarchy_overlay_copy), false) { copyFromLinux() }
                    val paste = makeBarBtn(s(R.string.omarchy_overlay_paste), false) { pasteFromAndroid() }
                    configurePasteButton(paste)
                    val bIme = makeBarBtn(s(R.string.omarchy_overlay_ime_key), false) { sendCmd("ime") }.apply {
                        setTextColor(Color.parseColor("#7aa2f7"))
                        background = makeRounded("#247aa2f7", "#557aa2f7", 6, 1)
                    }
                    listOf(bApps, bTerm, copy, paste, bIme).forEach { btn ->
                        btn.setPadding(dp(3), 0, dp(3), 0)
                        btn.setSingleLine(true)
                        if (compactBar) btn.textSize = 10f
                        bar.addView(btn, LinearLayout.LayoutParams(
                            if (compactBar) 0 else dp(40), dp(25), if (compactBar) 0.95f else 0f
                        ).apply { setMargins(dp(2), 0, dp(1), 0) })
                    }
                } else {
                    val bFull = makeBarBtn(
                        if (fullscreenActive) s(R.string.omarchy_overlay_exit_fullscreen)
                        else s(R.string.omarchy_overlay_enter_fullscreen),
                        fullscreenActive
                    ) {
                        fullscreenActive = !fullscreenActive
                        refreshFullscreenButton()
                        sendCmd("fullscreen")
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            if (compactBar) 0 else dp(52), dp(25), if (compactBar) 1.25f else 0f
                        ).apply { setMargins(dp(3), 0, dp(2), 0) }
                        setPadding(dp(3), 0, dp(3), 0)
                        if (compactBar) textSize = 10f
                        setSingleLine(true)
                    }
                    fullscreenBtn = bFull
                    bar.addView(bFull)

                    val copy = makeBarBtn(s(R.string.omarchy_overlay_copy), false) { copyFromLinux() }
                    val paste = makeBarBtn(s(R.string.omarchy_overlay_paste), false) { pasteFromAndroid() }
                    configurePasteButton(paste)
                    listOf(copy, paste).forEach { btn ->
                        btn.setPadding(dp(2), 0, dp(2), 0)
                        btn.setSingleLine(true)
                        if (compactBar) btn.textSize = 10f
                        bar.addView(btn, LinearLayout.LayoutParams(
                            if (compactBar) 0 else dp(42), dp(25), if (compactBar) 1.05f else 0f
                        ).apply { setMargins(dp(2), 0, dp(1), 0) })
                    }
                }

                val dragSpacer = View(activity).apply { setOnTouchListener(dragTouchListener) }
                if (!compactBar) bar.addView(dragSpacer, LinearLayout.LayoutParams(0, dp(25), 1f))

                if (!oskCompactMode) {
                    val alphaBar = SeekBar(activity).apply {
                        contentDescription = s(R.string.omarchy_overlay_keyboard_opacity)
                        max = 75
                        progress = ((oskAlpha - 0.20f) * 100f).roundToInt()
                        layoutParams = LinearLayout.LayoutParams(
                            if (compactBar) 0 else dp(60), dp(22), if (compactBar) 1.3f else 0f
                        ).apply { setMargins(dp(2), 0, dp(4), 0) }
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
                }

                val bMode = makeBarBtn(
                    if (oskCompactMode) s(R.string.omarchy_overlay_osk_expand) else s(R.string.omarchy_overlay_osk_collapse),
                    false
                ) {
                    oskCompactMode = !oskCompactMode
                    prefs.edit().putBoolean("osk_compact_mode", oskCompactMode).apply()
                    renderOskContent()
                }.apply {
                    setTextColor(Color.parseColor("#9ece6a"))
                    background = makeRounded("#1F9ECE6A", "#669ECE6A", 6, 1)
                    setSingleLine(true)
                    setPadding(dp(4), 0, dp(4), 0)
                    if (compactBar) textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(
                        if (compactBar) 0 else dp(44), dp(25), if (compactBar) 1.0f else 0f
                    ).apply { setMargins(dp(2), 0, dp(2), 0) }
                }
                bar.addView(bMode)

                val bKill = makeCloseWindowBarBtn(compactBar).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        if (compactBar) 0 else dp(42), dp(25), if (compactBar) 0.95f else 0f
                    ).apply { setMargins(dp(1), 0, dp(2), 0) }
                }
                bar.addView(bKill)

                val bClose = makeBarBtn(s(R.string.omarchy_overlay_dismiss_symbol), false) { hideOsk() }.apply {
                    contentDescription = s(R.string.omarchy_overlay_keyboard_hide)
                    layoutParams = LinearLayout.LayoutParams(
                        if (compactBar) 0 else dp(26), dp(25), if (compactBar) 0.65f else 0f
                    ).apply { setMargins(dp(1), 0, 0, 0) }
                    setPadding(0, 0, 0, 0)
                }
                bar.addView(bClose)
                contentHost.addView(bar)

                // Subtle divider between lightweight toolbar and keycaps
                val divider = View(activity).apply {
                    setBackgroundColor(Color.parseColor("#33414868"))
                }
                contentHost.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { bottomMargin = dp(3) })

                if (oskCompactMode) {
                    // Ultra-slim 2-row Geek Command Strip (~18% screen height)
                    contentHost.addView(buildKeyRow(listOf(
                        OskKey(s(R.string.omarchy_key_esc), "Escape", 1.0f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_key_tab), "Tab", 1.0f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_key_ctrl), "MOD:ctrl", 1.1f, tone = "mod"),
                        OskKey(s(R.string.omarchy_key_alt), "MOD:alt", 0.95f, tone = "mod"),
                        OskKey(s(R.string.omarchy_key_super), "MOD:super", 1.15f, tone = "mod"),
                        OskKey("|", "backslash", 0.85f, forceShift = true, tone = "sym"),
                        OskKey("~", "grave", 0.85f, forceShift = true, tone = "sym"),
                        OskKey("-", "minus", 0.85f, tone = "sym"),
                        OskKey("/", "slash", 0.85f, tone = "sym"),
                        OskKey("←", "Left", 0.9f, tone = "ctrl"),
                        OskKey("↑", "Up", 0.9f, tone = "ctrl"),
                        OskKey("↓", "Down", 0.9f, tone = "ctrl"),
                        OskKey("→", "Right", 0.9f, tone = "ctrl"),
                        OskKey("⌫", "BackSpace", 1.05f, tone = "ctrl"),
                        OskKey("⏎", "Return", 1.1f, tone = "accent")
                    )))
                } else {
                    // Row 1: Control + Terminal ~ | + Intuitive Arrows (← ↑ ↓ →)
                    contentHost.addView(buildKeyRow(listOf(
                        OskKey(s(R.string.omarchy_key_esc), "Escape", 1.05f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_key_tab), "Tab", 1.05f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_key_super), "MOD:super", 1.25f, tone = "mod"),
                        OskKey(s(R.string.omarchy_key_ctrl), "MOD:ctrl", 1.1f, tone = "mod"),
                        OskKey(s(R.string.omarchy_key_alt), "MOD:alt", 1.0f, tone = "mod"),
                        OskKey("~", "grave", 0.9f, forceShift = true, tone = "sym"),
                        OskKey("|", "backslash", 0.9f, forceShift = true, tone = "sym"),
                        OskKey("←", "Left", 0.95f, tone = "ctrl"),
                        OskKey("↑", "Up", 0.95f, tone = "ctrl"),
                        OskKey("↓", "Down", 0.95f, tone = "ctrl"),
                        OskKey("→", "Right", 0.95f, tone = "ctrl")
                    )))

                    if (!oskSymbolLayer) {
                        // Row 2: QWERTY with superscript 1-0 (long-press outputs digit directly)
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("q", "q", 1f, "Q", "1", "1"),
                            OskKey("w", "w", 1f, "W", "2", "2"),
                            OskKey("e", "e", 1f, "E", "3", "3"),
                            OskKey("r", "r", 1f, "R", "4", "4"),
                            OskKey("t", "t", 1f, "T", "5", "5"),
                            OskKey("y", "y", 1f, "Y", "6", "6"),
                            OskKey("u", "u", 1f, "U", "7", "7"),
                            OskKey("i", "i", 1f, "I", "8", "8"),
                            OskKey("o", "o", 1f, "O", "9", "9"),
                            OskKey("p", "p", 1f, "P", "0", "0")
                        )))
                        // Row 3: ASDFGHJKL + Accent Enter
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("a", "a", 1f, "A"),
                            OskKey("s", "s", 1f, "S"),
                            OskKey("d", "d", 1f, "D"),
                            OskKey("f", "f", 1f, "F"),
                            OskKey("g", "g", 1f, "G"),
                            OskKey("h", "h", 1f, "H"),
                            OskKey("j", "j", 1f, "J"),
                            OskKey("k", "k", 1f, "K"),
                            OskKey("l", "l", 1f, "L"),
                            OskKey("⏎", "Return", 1.5f, tone = "accent")
                        )))
                        // Row 4: Shift + ZXCVBNM , . + Mobile-position BackSpace
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("⇧", "MOD:shift", 1.35f, tone = "mod"),
                            OskKey("z", "z", 1f, "Z"),
                            OskKey("x", "x", 1f, "X"),
                            OskKey("c", "c", 1f, "C"),
                            OskKey("v", "v", 1f, "V"),
                            OskKey("b", "b", 1f, "B"),
                            OskKey("n", "n", 1f, "N"),
                            OskKey("m", "m", 1f, "M"),
                            OskKey(",", "comma", 1f, "<", "<"),
                            OskKey(".", "period", 1f, ">", ">"),
                            OskKey("⌫", "BackSpace", 1.45f, tone = "ctrl")
                        )))
                    } else {
                        // Symbol & Number Layer (123/#)
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("1", "1", 1f, "!", "!"),
                            OskKey("2", "2", 1f, "@", "@"),
                            OskKey("3", "3", 1f, "#", "#"),
                            OskKey("4", "4", 1f, "$", "$"),
                            OskKey("5", "5", 1f, "%", "%"),
                            OskKey("6", "6", 1f, "^", "^"),
                            OskKey("7", "7", 1f, "&", "&"),
                            OskKey("8", "8", 1f, "*", "*"),
                            OskKey("9", "9", 1f, "(", "("),
                            OskKey("0", "0", 1f, ")", ")")
                        )))
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("`", "grave", 1f, "~", "~"),
                            OskKey("-", "minus", 1f, "_", "_"),
                            OskKey("=", "equal", 1f, "+", "+"),
                            OskKey("[", "bracketleft", 1f, "{", "{"),
                            OskKey("]", "bracketright", 1f, "}", "}"),
                            OskKey("\\", "backslash", 1f, "|", "|"),
                            OskKey(";", "semicolon", 1f, ":", ":"),
                            OskKey("'", "apostrophe", 1f, "\"", "\""),
                            OskKey("/", "slash", 1f, "?", "?"),
                            OskKey("⏎", "Return", 1.5f, tone = "accent")
                        )))
                        contentHost.addView(buildKeyRow(listOf(
                            OskKey("⇧", "MOD:shift", 1.35f, tone = "mod"),
                            OskKey("!", "1", 1f, forceShift = true),
                            OskKey("@", "2", 1f, forceShift = true),
                            OskKey("#", "3", 1f, forceShift = true),
                            OskKey("$", "4", 1f, forceShift = true),
                            OskKey("%", "5", 1f, forceShift = true),
                            OskKey("^", "6", 1f, forceShift = true),
                            OskKey("&", "7", 1f, forceShift = true),
                            OskKey("_", "minus", 1f, forceShift = true),
                            OskKey("+", "equal", 1f, forceShift = true),
                            OskKey("⌫", "BackSpace", 1.45f, tone = "ctrl")
                        )))
                    }

                    // Row 5: Bottom Action Row (123/# | Apps | Menu | Space | Terminal | IME)
                    contentHost.addView(buildKeyRow(listOf(
                        OskKey(if (oskSymbolLayer) "ABC" else s(R.string.omarchy_overlay_sym_key), "MODE:sym", 1.35f, tone = "mod"),
                        OskKey(s(R.string.omarchy_overlay_apps_key), "CMD:apps", 1.15f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_overlay_menu_key), "CMD:menu", 1.15f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_key_space), "space", 3.6f),
                        OskKey(s(R.string.omarchy_overlay_terminal_key), "CMD:terminal", 1.15f, tone = "ctrl"),
                        OskKey(s(R.string.omarchy_overlay_ime_key), "CMD:ime", 1.35f, tone = "mod")
                    )))
                }

                refreshModButtons()
            }

            renderOskContent()

            // Sleek iOS/Android-style bottom drag pill bar
            val grabArea = FrameLayout(activity).apply {
                setOnTouchListener(dragTouchListener)
            }
            val pillBar = View(activity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#88c0caf5"))
                    cornerRadius = dp(3).toFloat()
                }
            }
            grabArea.addView(pillBar, FrameLayout.LayoutParams(dp(52), dp(3), Gravity.CENTER))
            root.addView(grabArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)
            ))

            val olp = FrameLayout.LayoutParams(
                width,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(12) }

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
                setTextColor(Color.parseColor(if (active) "#1a1b26" else "#a9b1d6"))
                if (active) setTypeface(null, Typeface.BOLD)
                background = makeRounded(
                    if (active) "#7aa2f7" else "#14FFFFFF",
                    if (active) "#7aa2f7" else "#00000000",
                    6, 1
                )
                setPadding(dp(6), dp(1), dp(6), dp(1))
                minHeight = 0; minimumHeight = dp(25)
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
            oskDynamicKeyViews.forEach { (tv, base, shifted) ->
                tv.text = if (modShift) shifted else base
            }
        }

        private fun setModStyle(name: String, on: Boolean) {
            modBtns[name]?.let { label ->
                label.setTextColor(Color.parseColor(if (on) "#1a1b26" else "#7aa2f7"))
                (label.parent as? View)?.background = makeRounded(
                    if (on) "#7aa2f7" else "#CC283457",
                    if (on) "#7aa2f7" else "#887aa2f7",
                    6, 1
                )
            }
            accessoryModBtns[name]?.let { button ->
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
