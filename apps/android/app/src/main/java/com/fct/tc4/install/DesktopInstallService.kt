// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.page.DesktopSetupActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DesktopInstallService : Service() {
    companion object {
        const val START = "com.omarchy.android.INITIAL_DESKTOP_START"
        const val PAUSE = "com.omarchy.android.INITIAL_DESKTOP_PAUSE"
        const val CANCEL = "com.omarchy.android.INITIAL_DESKTOP_CANCEL"
        const val IMAGE_ID = "image_id"
        const val IMPORT_URI = "import_uri"
        private const val CHANNEL = "initial-desktop"
        private const val NOTICE = 2401
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val pause = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    private val controlLock = Any()
    private var installing = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        InitialDesktopState.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "桌面安装", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in listOf(PAUSE, CANCEL)) {
            synchronized(controlLock) {
                if (installing) return START_NOT_STICKY
                pause.set(true)
                if (intent?.action == CANCEL) cancel.set(true)
            }
            if (!InitialDesktopState.running.get()) {
                if (cancel.get()) cancelCache()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(startId); return START_NOT_STICKY }
        if (!InitialDesktopState.running.compareAndSet(false, true)) return START_NOT_STICKY
        synchronized(controlLock) { pause.set(false); cancel.set(false); installing = false }
        val notice = notification(InitialDesktopState.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTICE, notice)
        val imageId = intent.getStringExtra(IMAGE_ID)
        val localUri = intent.getStringExtra(IMPORT_URI)?.let(Uri::parse)
        worker.execute {
            try { runInstall(imageId, localUri) }
            catch (error: Exception) {
                Log.e("InitialDesktop", "Initial installation stopped: ${error.javaClass.simpleName}")
                val message = when {
                    error.message?.contains("space", true) == true -> "存储空间不足。请释放空间后重试，已有数据未改动。"
                    error.message?.contains("existing", true) == true -> "检测到已有桌面，为保护数据已停止首次安装。"
                    else -> "下载或安装未完成。已保留可恢复的进度，请继续或重新选择离线镜像。"
                }
                publish(InitialDesktopState.state.value.copy(phase = SetupPhase.FAILED, message = message, bytesPerSecond = 0))
            } finally {
                if (cancel.get() && !installing) cancelCache()
                synchronized(controlLock) { installing = false }
                InitialDesktopState.running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun runInstall(id: String?, localUri: Uri?) {
        val previous = InitialDesktopState.state.value
        val spec = previous.image?.takeIf { it.id == id }
            ?: InitialImageRepository.available(this).firstOrNull { it.id == id }
            ?: throw IOException("Unknown initial image")
        val version = packageManager.getPackageInfo(packageName, 0).versionCode
        if (!InitialImageCatalog.compatible(spec, version)) throw IOException("Incompatible initial image")
        if ("omarchy" in Global.installedContainers) throw IOException("An existing desktop cannot be replaced")
        val root = InitialDesktopState.directory(this)
        val downloads = File(root, "downloads")
        val downloader = ResumableImageDownloader()
        val offset = if (localUri == null) downloader.partialBytes(spec, downloads) else 0L
        if (root.usableSpace < InitialInstallTransaction.additionalBytes(spec, offset)) throw IOException("Insufficient download/install space")
        val started = SystemClock.elapsedRealtime()
        var lastNotice = 0L
        val progress: (Long, Long) -> Unit = { count, _ ->
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            val speed = ((count - offset).coerceAtLeast(0) * 1000 / elapsed)
            val state = DesktopSetupState(if (localUri == null) SetupPhase.DOWNLOADING else SetupPhase.IMPORTING,
                spec, count, if (count == spec.bytes) "正在校验镜像完整性…" else "", speed)
            InitialDesktopState.update(this, state)
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotice > 1000) { notify(state); lastNotice = now }
        }
        publish(DesktopSetupState(if (localUri == null) SetupPhase.DOWNLOADING else SetupPhase.IMPORTING, spec, offset))
        val archive = if (localUri != null) importImage(spec, localUri, root, progress) else {
            var result: File? = null
            var attempts = 0
            while (true) {
                try { result = downloader.download(spec, downloads, { pause.get() }, progress); break }
                catch (e: IOException) {
                    val networkError = generateSequence<Throwable>(e) { it.cause }.any {
                        it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.net.UnknownHostException
                    }
                    if (!networkError || attempts++ >= 2 || pause.get()) throw e
                    publish(InitialDesktopState.state.value.copy(message = "连接中断，正在重试，已下载内容会保留。"))
                    repeat(30) { if (!pause.get()) Thread.sleep(100) }
                }
            }
            result
        }
        if (archive == null || pause.get()) {
            publish(InitialDesktopState.state.value.copy(phase = SetupPhase.PAUSED, message = "已暂停，重新打开应用后可继续。", bytesPerSecond = 0))
            return
        }
        publish(DesktopSetupState(SetupPhase.VERIFYING, spec, spec.bytes, "正在校验并准备安装…"))
        val begin = synchronized(controlLock) {
            if (pause.get()) false else { installing = true; true }
        }
        if (!begin) { publish(DesktopSetupState(SetupPhase.PAUSED, spec, spec.bytes)); return }
        publish(DesktopSetupState(SetupPhase.INSTALLING, spec, spec.bytes, "正在解压和配置桌面，请稍候…"))
        InitialDesktopInstaller(this).install(spec, archive)
        publish(DesktopSetupState(SetupPhase.COMPLETE, spec, spec.bytes, "桌面安装完成，可以启动。"))
        if (localUri == null) downloader.cancel(spec, downloads) else archive.delete()
    }
    private fun importImage(spec: ImageSpec, uri: Uri, root: File, progress: (Long, Long) -> Unit): File {
        if (uri.scheme != "content") throw IOException("Invalid local import")
        val archive = File(root, "local-${UUID.randomUUID()}.tar.zst")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(1024 * 1024); var bytes = 0L
                    while (true) {
                        if (pause.get()) throw IOException("Local import interrupted")
                        val n = input.read(buffer); if (n < 0) break
                        if (bytes + n > spec.bytes) throw IOException("Wrong local image length")
                        output.write(buffer, 0, n); bytes += n; progress(bytes, spec.bytes)
                    }
                    output.fd.sync()
                    if (bytes != spec.bytes) throw IOException("Wrong local image length")
                }
            } ?: throw IOException("Cannot open local image")
            return archive
        } catch (e: Exception) { archive.delete(); throw e }
    }
    private fun cancelCache() {
        val state = InitialDesktopState.state.value
        state.image?.let { ResumableImageDownloader().cancel(it, File(InitialDesktopState.directory(this), "downloads")) }
        publish(DesktopSetupState(message = "下载已取消，已有桌面数据未改动。"))
    }
    private fun publish(state: DesktopSetupState) { InitialDesktopState.update(this, state); notify(state) }
    private fun notify(state: DesktopSetupState) { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(state)) }
    private fun notification(state: DesktopSetupState): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, DesktopSetupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Omarchy 桌面安装").setContentIntent(open).setOnlyAlertOnce(true)
            .setContentText(state.message.ifEmpty { "下载进度已保存，可返回应用查看。" })
            .setOngoing(state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING))
        val total = state.image?.bytes ?: 0
        if (total > 0 && state.phase == SetupPhase.DOWNLOADING) builder.setProgress(1000, (state.downloaded * 1000 / total).toInt(), false)
        else builder.setProgress(0, 0, state.phase == SetupPhase.INSTALLING || state.phase == SetupPhase.VERIFYING)
        if (state.phase == SetupPhase.DOWNLOADING) {
            val pauseIntent = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "暂停", pauseIntent).build())
        }
        return builder.build()
    }
    override fun onDestroy() { pause.set(true); worker.shutdown(); super.onDestroy() }
}
