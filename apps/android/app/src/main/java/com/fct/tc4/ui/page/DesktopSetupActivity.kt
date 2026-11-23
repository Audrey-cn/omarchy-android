// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.page

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
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
        InitialDesktopState.load(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(8), 0, dp(12)); layout.addView(this) }
        text("安装 Omarchy 桌面", 26f)
        text("首次使用需下载桌面镜像。安装 APK 与已有系统相互独立，已有桌面不会被此流程覆盖。", 16f)
        versions = Spinner(this).also(layout::addView)
        details = text("正在读取可用镜像…", 14f)
        status = text("", 16f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; layout.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; layout.addView(this) }
        start = button("下载并安装桌面") {
            val state = InitialDesktopState.state.value
            if (initialDesktopAction(state.phase == SetupPhase.COMPLETE, "omarchy" in Global.installedContainers) == InitialDesktopAction.LAUNCH) openDesktop()
            else (state.image ?: selected())?.let { image ->
                pendingId = image.id
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startTransfer(image.id)
            }
        }
        pause = button("暂停下载") { startService(Intent(this, DesktopInstallService::class.java).setAction(DesktopInstallService.PAUSE)) }
        cancel = button("取消下载") { startService(Intent(this, DesktopInstallService::class.java).setAction(DesktopInstallService.CANCEL)) }
        local = button("从本地镜像安装") { pickLocal.launch(arrayOf("*/*")) }
        refresh = button("刷新可用镜像") { loadImages(true) }
        button("下载较慢？查看离线下载方式") {
            android.app.AlertDialog.Builder(this).setTitle("离线下载后导入")
                .setMessage("可以暂停当前任务，在浏览器或支持断点的下载工具中取得同版本 rootfs.tar.zst，再通过“从本地镜像安装”导入。导入仍会核对版本、大小和 SHA256。")
                .setPositiveButton("打开镜像发行页") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(InitialImageCatalog.releasePage(InitialDesktopState.state.value.image ?: selected()))))
                }.setNegativeButton("返回", null).show()
        }
        text("默认建议使用 Wi-Fi。镜像只用于首次安装；Ubuntu 内的软件通过系统自己的软件源维护。", 14f)
        setContentView(ScrollView(this).apply { addView(layout) })
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
                if (images.isEmpty()) status.text = "暂未找到兼容镜像，请刷新列表或更新 App。"
            } catch (_: Exception) { status.text = "镜像列表暂时不可用。可使用已缓存的版本，或稍后重试。" }
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
        start.text = when { ready -> "启动桌面"; action == InitialDesktopAction.RECOVER -> "恢复桌面安装记录"; state.phase == SetupPhase.PAUSED || state.phase == SetupPhase.FAILED -> "继续下载并安装"; else -> "下载并安装桌面" }
        start.isEnabled = ready || (!active && (state.image != null || images.isNotEmpty()) && !installed)
        versions.isEnabled = state.phase == SetupPhase.IDLE && !installed
        pause.visibility = if (state.phase == SetupPhase.DOWNLOADING || state.phase == SetupPhase.VERIFYING) View.VISIBLE else View.GONE
        cancel.visibility = if (state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.PAUSED, SetupPhase.FAILED, SetupPhase.VERIFYING)) View.VISIBLE else View.GONE
        local.isEnabled = !active && !installed && images.isNotEmpty()
        refresh.isEnabled = !active
        val image = if (active || state.phase != SetupPhase.IDLE) state.image ?: selected() else selected()
        details.text = image?.let { "下载 ${formatBytes(it.bytes)} · 预计安装空间至少 ${formatBytes(it.unpackedBytes + InitialInstallTransaction.HEADROOM_BYTES)}（另需下载缓存空间）" } ?: "暂无兼容镜像。"
        progress.isIndeterminate = state.phase in setOf(SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING)
        progress.progress = if (state.image != null) (state.downloaded * 1000 / state.image.bytes).toInt() else 0
        status.text = when {
            ready -> "桌面已安装。更新 App 不会重新导入系统镜像。"
            action == InitialDesktopAction.RECOVER -> "此前桌面已发布，请点击恢复安装记录后启动。"
            active || state.phase == SetupPhase.PAUSED || state.phase == SetupPhase.FAILED -> {
                val speed = if (state.bytesPerSecond > 0) " · ${formatBytes(state.bytesPerSecond)}/s" else ""
                val remaining = if (state.bytesPerSecond > 0 && state.image != null) " · 剩余约 ${(state.image.bytes - state.downloaded) / state.bytesPerSecond} 秒" else ""
                "${state.message}\n${formatBytes(state.downloaded)} / ${state.image?.let { formatBytes(it.bytes) } ?: "—"}$speed$remaining".trim()
            }
            existing -> "检测到已有桌面文件。首次安装不会覆盖它们，请返回应用管理。"
            images.isEmpty() -> "暂未找到兼容镜像，请刷新列表或更新 App。"
            else -> "选择初始镜像后点击下载，支持暂停和断点继续。"
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
