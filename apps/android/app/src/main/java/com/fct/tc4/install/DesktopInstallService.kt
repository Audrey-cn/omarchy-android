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
import java.io.IOException
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
    private var latestStartId = 0
    private val workQueue = InitialDesktopWorkQueue(worker, InitialDesktopState.running, controlLock) {
        installing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        InitialDesktopState.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "桌面安装", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(controlLock) {
        // Keep the new id and acceptance atomic with worker idle/stopSelf finalization.
        latestStartId = startId
        if (intent?.action in listOf(PAUSE, CANCEL)) {
            if (installing) return@synchronized START_NOT_STICKY
            pause.set(true)
            if (intent?.action == CANCEL) cancel.set(true)
            if (!InitialDesktopState.running.get()) {
                if (intent?.action == CANCEL) {
                    workQueue.cancelIdle({ showForeground(cancellingState()) }) { performCancellation() }
                } else stopSelf(startId)
            }
            START_NOT_STICKY
        } else if (intent?.action != START) {
            stopSelf(startId)
            START_NOT_STICKY
        } else {
            val imageId = intent.getStringExtra(IMAGE_ID)
            val localUri = intent.getStringExtra(IMPORT_URI)?.let(Uri::parse)
            val requestedState = InitialDesktopState.state.value
            workQueue.start({
                pause.set(false)
                cancel.set(false)
                installing = false
                showForeground(InitialDesktopState.state.value)
            }) { performInstall(imageId, localUri, requestedState) }
            START_NOT_STICKY
        }
    }
    private fun showForeground(state: DesktopSetupState) {
        val notice = notification(state)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTICE, notice)
    }
    private fun cancellingState() = InitialDesktopState.state.value.copy(
        phase = SetupPhase.VERIFYING, message = "正在清理已取消的安装缓存…", bytesPerSecond = 0)
    private fun performCancellation() {
        try { publish(cancellingState()) }
        catch (error: Exception) { Log.e("InitialDesktop", "Cancellation progress could not be saved: ${error.javaClass.simpleName}") }
        // In particular, a full disk must not prevent the cleanup that frees that disk.
        try { cancelCache() }
        catch (error: Exception) { Log.e("InitialDesktop", "Cancellation state could not be saved: ${error.javaClass.simpleName}") }
    }
    private fun performInstall(imageId: String?, localUri: Uri?, requestedState: DesktopSetupState) {
        try { runInstall(imageId, localUri, requestedState) }
        catch (error: Exception) {
            Log.e("InitialDesktop", "Initial installation stopped: ${error.javaClass.simpleName}")
            val message = when {
                error.message?.contains("space", true) == true -> "存储空间不足。请释放空间后重试，已有数据未改动。"
                error.message?.contains("existing", true) == true -> "检测到已有桌面，为保护数据已停止首次安装。"
                else -> "下载或安装未完成。已保留可恢复的进度，请继续或重新选择离线镜像。"
            }
            try {
                if (!cancel.get()) publish(InitialDesktopState.state.value.copy(
                    phase = if (pause.get() && !installing) SetupPhase.PAUSED else SetupPhase.FAILED,
                    message = if (pause.get() && !installing) "已暂停，重新打开应用后可继续。" else message, bytesPerSecond = 0))
            } catch (persistenceError: Exception) {
                Log.e("InitialDesktop", "Installation state could not be saved: ${persistenceError.javaClass.simpleName}")
            }
        } finally {
            try {
                if (cancel.get() && !installing) cancelCache()
            } catch (cleanupError: Exception) {
                Log.e("InitialDesktop", "Cancellation state could not be saved: ${cleanupError.javaClass.simpleName}")
            } finally { synchronized(controlLock) { installing = false } }
        }
    }
    private fun runInstall(id: String?, localUri: Uri?, previous: DesktopSetupState) {
        val spec = previous.image?.takeIf { it.id == id }
            ?: InitialImageRepository.available(this).firstOrNull { it.id == id }
            ?: throw IOException("Unknown initial image")
        val version = packageManager.getPackageInfo(packageName, 0).versionCode
        if (!InitialImageCatalog.compatible(spec, version)) throw IOException("Incompatible initial image")
        if ("omarchy" in Global.installedContainers) throw IOException("An existing desktop cannot be replaced")
        val root = InitialDesktopState.directory(this)
        val downloads = File(root, "downloads")
        val downloader = ResumableImageDownloader()
        val imports = InitialImageImportCache(File(root, "imports"))
        val installer = InitialDesktopInstaller(this)
        val importUri = localUri ?: previous.importUri?.takeIf { previous.image == spec }?.let(Uri::parse)
        if (importUri != null && importUri.scheme != "content") throw IOException("Invalid local import")
        var imported: File? = null
        var offline = importUri != null
        var offset = 0L
        publish(DesktopSetupState(SetupPhase.VERIFYING, spec, message = "正在检查此前安装进度…", importUri = importUri?.toString()))
        val recovered = installer.recoverOrCheckSpace(spec) {
            InitialImageImportCache.discardLegacyCopies(root)
            imported = imports.recover(spec)
            if (imported != null) offline = true
            offset = if (imported != null) spec.bytes else if (offline) 0L else downloader.partialBytes(spec, downloads)
            offset
        }
        if (recovered != null) {
            // Publication already committed; a late cancel may only clean caches, never undo completion.
            synchronized(controlLock) { installing = true }
            publish(DesktopSetupState(SetupPhase.COMPLETE, spec, spec.bytes, "桌面安装完成，可以启动。"))
            cleanCompletedCache(spec, downloads, imports)
            return
        }
        val started = SystemClock.elapsedRealtime()
        var lastNotice = 0L
        val progress: (Long, Long) -> Unit = { count, _ ->
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            val speed = ((count - offset).coerceAtLeast(0) * 1000 / elapsed)
            val state = DesktopSetupState(if (offline) SetupPhase.IMPORTING else SetupPhase.DOWNLOADING,
                spec, count, if (count == spec.bytes) "正在校验镜像完整性…" else "", speed, importUri?.toString())
            InitialDesktopState.update(this, state)
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotice > 1000) { notify(state); lastNotice = now }
        }
        publish(DesktopSetupState(if (offline) SetupPhase.IMPORTING else SetupPhase.DOWNLOADING, spec, offset, importUri = importUri?.toString()))
        val archive = if (offline) imported ?: imports.importImage(spec, {
            contentResolver.openInputStream(importUri ?: throw IOException("Select the offline image again"))
                ?: throw IOException("Cannot open local image")
        }, { pause.get() }, progress) else {
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
        publish(InitialDesktopState.state.value.copy(phase = SetupPhase.VERIFYING, downloaded = spec.bytes, message = "正在校验并准备安装…", bytesPerSecond = 0))
        val begin = synchronized(controlLock) {
            if (pause.get()) false else { installing = true; true }
        }
        if (!begin) { publish(InitialDesktopState.state.value.copy(phase = SetupPhase.PAUSED, downloaded = spec.bytes)); return }
        publish(InitialDesktopState.state.value.copy(phase = SetupPhase.INSTALLING, downloaded = spec.bytes, message = "正在解压和配置桌面，请稍候…"))
        installer.install(spec, archive)
        publish(DesktopSetupState(SetupPhase.COMPLETE, spec, spec.bytes, "桌面安装完成，可以启动。"))
        cleanCompletedCache(spec, downloads, imports)
    }
    private fun cleanCompletedCache(spec: ImageSpec, downloads: File, imports: InitialImageImportCache) {
        // A cache cleanup failure cannot turn a successfully registered guest into a failed install.
        try { ResumableImageDownloader().cancel(spec, downloads) }
        catch (error: Exception) { Log.w("InitialDesktop", "Download cache cleanup stopped: ${error.javaClass.simpleName}") }
        try { imports.cancel(); InitialImageImportCache.discardLegacyCopies(InitialDesktopState.directory(this)) }
        catch (error: Exception) { Log.w("InitialDesktop", "Import cache cleanup stopped: ${error.javaClass.simpleName}") }
    }
    private fun cancelCache() {
        val state = InitialDesktopState.state.value
        val root = InitialDesktopState.directory(this)
        var failed = false
        fun clean(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                failed = true
                Log.w("InitialDesktop", "Owned cache cleanup stopped: ${error.javaClass.simpleName}")
            }
        }
        clean { state.image?.let { ResumableImageDownloader().cancel(it, File(root, "downloads")) } }
        clean { InitialImageImportCache(File(root, "imports")).cancel() }
        clean { InitialImageImportCache.discardLegacyCopies(root) }
        clean { InitialDesktopInstaller(this).discardInterruptedStages() }
        publish(if (failed) state.copy(phase = SetupPhase.FAILED, bytesPerSecond = 0,
            message = "部分安装缓存未能清理，请重新打开应用后重试取消。")
            else DesktopSetupState(message = "下载已取消，已有桌面数据未改动。"))
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
