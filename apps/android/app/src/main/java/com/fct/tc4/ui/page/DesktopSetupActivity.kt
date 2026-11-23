// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.page

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fct.tc4.R
import com.fct.tc4.install.*
import com.fct.tc4.ui.main.MainActivity
import com.fct.tc4.ui.misc.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DesktopSetupActivity : AppCompatActivity() {
    private lateinit var versions: Spinner
    private lateinit var details: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var cancel: Button
    private lateinit var local: Button
    private lateinit var refresh: Button
    private var images = emptyList<ImageSpec>()
    private var pendingId: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTransfer(pendingId) }
    private val pickLocal = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val image = selected()
        if (uri != null && image != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            startTransfer(image.id, uri.toString())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        InitialDesktopState.load(this)
        window.statusBarColor = Color.parseColor("#1A1B26")
        window.navigationBarColor = Color.parseColor("#1A1B26")

        val bgDark = Color.parseColor("#1A1B26")
        val cardBg = Color.parseColor("#24283B")
        val cardStroke = Color.parseColor("#414868")
        val primaryBlue = Color.parseColor("#7AA2F7")
        val onPrimaryDark = Color.parseColor("#16161E")
        val textPrimary = Color.parseColor("#C0CAF5")
        val textMuted = Color.parseColor("#9AA5CE")
        val btnSecondaryBg = Color.parseColor("#2E334D")
        val btnDangerBg = Color.parseColor("#3B2430")
        val btnDangerText = Color.parseColor("#F7768E")

        fun roundedBg(fill: Int, stroke: Int = 0, radiusDp: Int = 14) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgDark)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        fun addCard(block: LinearLayout.() -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(cardBg, cardStroke, 16)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                block()
            }
            layout.addView(card)
        }

        fun styledButton(
            parent: LinearLayout,
            label: String,
            primary: Boolean = false,
            danger: Boolean = false,
            action: () -> Unit
        ) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (primary) 15f else 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(when {
                primary -> onPrimaryDark
                danger -> btnDangerText
                else -> textPrimary
            })
            background = roundedBg(
                fill = when {
                    primary -> primaryBlue
                    danger -> btnDangerBg
                    else -> btnSecondaryBg
                },
                stroke = if (primary) 0 else cardStroke,
                radiusDp = 12
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(8) }
            setOnClickListener { action() }
            parent.addView(this)
        }

        // Header
        layout.addView(TextView(this).apply {
            text = getString(R.string.omarchy_setup_brand)
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(primaryBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        layout.addView(TextView(this).apply {
            text = getString(R.string.omarchy_setup_title)
            textSize = 26f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        layout.addView(TextView(this).apply {
            text = getString(R.string.omarchy_setup_intro)
            textSize = 13.5f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(16))
        })

        // Card 1: Image selection & download progress
        addCard {
            addView(TextView(this@DesktopSetupActivity).apply {
                text = getString(R.string.omarchy_setup_image_version)
                textSize = 13f
                setTextColor(primaryBlue)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            versions = Spinner(this@DesktopSetupActivity).apply {
                background = roundedBg(bgDark, cardStroke, 10)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }.also(::addView)
            details = TextView(this@DesktopSetupActivity).apply {
                text = getString(R.string.omarchy_setup_loading_images)
                textSize = 12.5f
                setTextColor(textMuted)
                setPadding(0, dp(10), 0, dp(8))
            }.also(::addView)
            progress = ProgressBar(this@DesktopSetupActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(primaryBlue)
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B4261"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8)
                ).apply { topMargin = dp(4); bottomMargin = dp(10) }
            }.also(::addView)
            status = TextView(this@DesktopSetupActivity).apply {
                text = ""
                textSize = 13.5f
                setTextColor(textPrimary)
                setPadding(0, 0, 0, dp(6))
            }.also(::addView)

            start = styledButton(this, getString(R.string.omarchy_setup_download_install), primary = true) {
                val state = InitialDesktopState.state.value
                if (initialDesktopAction(state.phase == SetupPhase.COMPLETE, "omarchy" in Global.installedContainers) == InitialDesktopAction.LAUNCH) openDesktop()
                else (state.image ?: selected())?.let { image ->
                    pendingId = image.id
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@DesktopSetupActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startTransfer(image.id)
                }
            }
            pause = styledButton(this, getString(R.string.omarchy_setup_pause_download)) {
                startService(Intent(this@DesktopSetupActivity, DesktopInstallService::class.java).setAction(DesktopInstallService.PAUSE))
            }
            cancel = styledButton(this, getString(R.string.omarchy_setup_cancel_download), danger = true) {
                startService(Intent(this@DesktopSetupActivity, DesktopInstallService::class.java).setAction(DesktopInstallService.CANCEL))
            }
        }

        // Card 2: Offline import & mirror tools
        addCard {
            addView(TextView(this@DesktopSetupActivity).apply {
                text = getString(R.string.omarchy_setup_offline_title)
                textSize = 13f
                setTextColor(Color.parseColor("#9ECE6A"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            addView(TextView(this@DesktopSetupActivity).apply {
                text = getString(R.string.omarchy_setup_offline_summary)
                textSize = 12.5f
                setTextColor(textMuted)
                setPadding(0, 0, 0, dp(4))
            })
            local = styledButton(this, getString(R.string.omarchy_setup_import_local)) { pickLocal.launch(arrayOf("*/*")) }
            refresh = styledButton(this, getString(R.string.omarchy_setup_refresh_images)) { loadImages(true) }
            styledButton(this, getString(R.string.omarchy_setup_slow_download)) {
                android.app.AlertDialog.Builder(this@DesktopSetupActivity).setTitle(R.string.omarchy_setup_offline_dialog_title)
                    .setMessage(R.string.omarchy_setup_offline_dialog_message)
                    .setPositiveButton(R.string.omarchy_setup_open_releases) { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(InitialImageCatalog.releasePage(InitialDesktopState.state.value.image ?: selected()))))
                    }.setNegativeButton(R.string.tc4_btn_back, null).show()
            }
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.omarchy_setup_download_tip)
            textSize = 12f
            setTextColor(textMuted)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgDark)
            isFillViewport = true
            addView(layout)
        })
        versions.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { render(InitialDesktopState.state.value) }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { InitialDesktopState.state.collect { render(it) } } }
        loadImages(false)
    }
    private fun loadImages(network: Boolean) {
        refresh.isEnabled = false
        lifecycleScope.launch {
            try {
                val available = withContext(Dispatchers.IO) { if (network) InitialImageRepository.refresh(this@DesktopSetupActivity) else InitialImageRepository.available(this@DesktopSetupActivity) }
                val code = packageManager.getPackageInfo(packageName, 0).versionCode
                val pending = InitialDesktopState.state.value.image
                images = (listOfNotNull(pending) + available).distinctBy { it.id }.filter { InitialImageCatalog.compatible(it, code) }
                versions.adapter = ArrayAdapter(this@DesktopSetupActivity, android.R.layout.simple_spinner_dropdown_item, images.map { it.name })
                if (images.isEmpty()) status.setText(R.string.omarchy_setup_no_images)
            } catch (_: Exception) { status.setText(R.string.omarchy_setup_catalog_unavailable) }
            finally { refresh.isEnabled = !InitialDesktopState.running.get(); render(InitialDesktopState.state.value) }
        }
    }
    private fun selected() = images.getOrNull(versions.selectedItemPosition)
    private fun startTransfer(id: String?, uri: String? = null) {
        if (id == null) return
        ContextCompat.startForegroundService(this, Intent(this, DesktopInstallService::class.java).setAction(DesktopInstallService.START)
            .putExtra(DesktopInstallService.IMAGE_ID, id).apply { if (uri != null) putExtra(DesktopInstallService.IMPORT_URI, uri) })
    }
    private fun render(state: DesktopSetupState) {
        if (!::start.isInitialized) return
        val installed = "omarchy" in Global.installedContainers
        val existing = File(dataDir, "omarchy").exists()
        val active = state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING)
        val action = initialDesktopAction(state.phase == SetupPhase.COMPLETE, installed)
        val ready = action == InitialDesktopAction.LAUNCH
        start.setText(when { ready -> R.string.omarchy_setup_launch; action == InitialDesktopAction.RECOVER -> R.string.omarchy_setup_recover; state.phase == SetupPhase.PAUSED || state.phase == SetupPhase.FAILED -> R.string.omarchy_setup_resume; else -> R.string.omarchy_setup_download_install })
        start.isEnabled = ready || (!active && (state.image != null || images.isNotEmpty()) && !installed)
        versions.isEnabled = state.phase == SetupPhase.IDLE && !installed
        pause.visibility = if (state.phase == SetupPhase.DOWNLOADING || state.phase == SetupPhase.VERIFYING) View.VISIBLE else View.GONE
        cancel.visibility = if (state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.PAUSED, SetupPhase.FAILED, SetupPhase.VERIFYING)) View.VISIBLE else View.GONE
        local.isEnabled = !active && !installed && images.isNotEmpty()
        refresh.isEnabled = !active
        val image = if (active || state.phase != SetupPhase.IDLE) state.image ?: selected() else selected()
        details.text = image?.let { getString(R.string.omarchy_setup_image_size, formatBytes(it.bytes), formatBytes(it.unpackedBytes + InitialInstallTransaction.HEADROOM_BYTES)) } ?: getString(R.string.omarchy_setup_no_compatible_image)
        progress.isIndeterminate = state.phase in setOf(SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING)
        progress.progress = if (state.image != null) (state.downloaded * 1000 / state.image.bytes).toInt() else 0
        status.text = when {
            ready -> getString(R.string.omarchy_setup_installed)
            action == InitialDesktopAction.RECOVER -> getString(R.string.omarchy_setup_recover_hint)
            active || state.phase == SetupPhase.PAUSED || state.phase == SetupPhase.FAILED -> {
                val speed = if (state.bytesPerSecond > 0) getString(R.string.omarchy_setup_speed, formatBytes(state.bytesPerSecond)) else ""
                val remaining = if (state.bytesPerSecond > 0 && state.image != null) getString(R.string.omarchy_setup_remaining, (state.image.bytes - state.downloaded) / state.bytesPerSecond) else ""
                getString(R.string.omarchy_setup_progress, state.message, formatBytes(state.downloaded), state.image?.let { formatBytes(it.bytes) } ?: "—", speed, remaining).trim()
            }
            existing -> getString(R.string.omarchy_setup_existing_files)
            images.isEmpty() -> getString(R.string.omarchy_setup_no_images)
            else -> getString(R.string.omarchy_setup_select_hint)
        }
    }
    private fun openDesktop() {
        Global.autoLaunch = "omarchy"
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHORTCUT)
            .putExtra("shortcut_code", "omarchy").putExtra("shortcut_command", ""))
        finish()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
