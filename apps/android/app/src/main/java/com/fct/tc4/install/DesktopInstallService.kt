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
import com.fct.tc4.R
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
    private val foregroundTimedOut = AtomicBoolean(false)
    private val controlLock = Any()
    private var installing = false
    private var foregroundShown = false
    private var latestStartId = 0
    private val workQueue = InitialDesktopWorkQueue(worker, InitialDesktopState.running, controlLock) {
        installing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundShown = false
        stopSelf(latestStartId)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        InitialDesktopState.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.omarchy_setup_install_channel), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(controlLock) {
        // Keep the new id and acceptance atomic with worker idle/stopSelf finalization.
        latestStartId = startId
        if (foregroundTimedOut.get()) {
            stopSelf()
            return@synchronized START_NOT_STICKY
        }
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
            val accepted = workQueue.start({
                pause.set(false)
                cancel.set(false)
                installing = false
                showForeground(InitialDesktopState.state.value)
            }) { performInstall(imageId, localUri, requestedState) }
            // A timed-out instance's read can still be unwinding. A replacement
            // instance rejected by the shared running fence must stop promptly
            // instead of missing startForeground's startup deadline.
            if (!accepted && !foregroundShown) stopSelf(startId)
            START_NOT_STICKY
        }
    }
    private fun showForeground(state: DesktopSetupState) {
        val notice = notification(state)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTICE, notice)
        foregroundShown = true
    }
    private fun cancellingState() = InitialDesktopState.state.value.copy(
        phase = SetupPhase.VERIFYING, message = getString(R.string.omarchy_setup_cleaning_cache), bytesPerSecond = 0)
    private fun performCancellation() {
        try { publish(cancellingState()) }
        catch (error: Exception) { Log.e("InitialDesktop", "Cancellation progress could not be saved: ${error.javaClass.simpleName}") }
        // In particular, a full disk must not prevent the cleanup that frees that disk.
        try { cancelCache() }
        catch (error: Exception) { Log.e("InitialDesktop", "Cancellation state could not be saved: ${error.javaClass.simpleName}") }
    }
    private fun performInstall(imageId: String?, localUri: Uri?, requestedState: DesktopSetupState) {
        if (foregroundTimedOut.get()) return
        try { runInstall(imageId, localUri, requestedState) }
        catch (error: Exception) {
            Log.e("InitialDesktop", "Initial installation stopped: ${error.javaClass.simpleName}")
            val message = when {
                error.message?.contains("space", true) == true -> getString(R.string.omarchy_setup_insufficient_space)
                error.message?.contains("existing", true) == true -> getString(R.string.omarchy_setup_existing_desktop)
                else -> getString(R.string.omarchy_setup_incomplete)
            }
            try {
                if (!cancel.get()) publish(InitialDesktopState.state.value.copy(
                    phase = if (pause.get() && !installing) SetupPhase.PAUSED else SetupPhase.FAILED,
                    message = if (pause.get() && !installing) getString(R.string.omarchy_setup_paused) else message, bytesPerSecond = 0))
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
        var downloadSource = ""
        val downloader = ResumableImageDownloader(onSource = { index, total ->
            downloadSource = getString(R.string.omarchy_setup_source, index, total)
            publish(InitialDesktopState.state.value.copy(message = getString(R.string.omarchy_setup_connecting, downloadSource)))
        })
        val imports = InitialImageImportCache(File(root, "imports"))
        val installer = InitialDesktopInstaller(this)
        val importUri = localUri ?: previous.importUri?.takeIf { previous.image == spec }?.let(Uri::parse)
        if (importUri != null && importUri.scheme != "content") throw IOException("Invalid local import")
        var imported: File? = null
        var offline = importUri != null
        var offset = 0L
        publish(DesktopSetupState(SetupPhase.VERIFYING, spec, message = getString(R.string.omarchy_setup_checking_progress), importUri = importUri?.toString()))
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
            publish(DesktopSetupState(SetupPhase.COMPLETE, spec, spec.bytes, getString(R.string.omarchy_setup_complete)))
            cleanCompletedCache(spec, downloads, imports)
            return
        }
        val started = SystemClock.elapsedRealtime()
        var lastNotice = 0L
        val progress: (Long, Long) -> Unit = { count, _ ->
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            val speed = ((count - offset).coerceAtLeast(0) * 1000 / elapsed)
            val state = DesktopSetupState(if (offline) SetupPhase.IMPORTING else SetupPhase.DOWNLOADING,
                spec, count, if (count == spec.bytes) getString(R.string.omarchy_setup_verifying_image) else if (offline) getString(R.string.omarchy_setup_reading_local) else downloadSource, speed, importUri?.toString())
            synchronized(controlLock) {
                if (!foregroundTimedOut.get()) {
                    InitialDesktopState.update(this, state)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotice > 1000) { notify(state); lastNotice = now }
                }
            }
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
                        it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.net.UnknownHostException || it is RetryableImageResponse
                    }
                    if (!networkError || attempts++ >= 2 || pause.get()) throw e
                    publish(InitialDesktopState.state.value.copy(message = getString(R.string.omarchy_setup_network_retry)))
                    val retryDelay = (e as? RetryableImageResponse)?.retryAfterSeconds?.coerceIn(1, 5) ?: 3L
                    repeat((retryDelay * 10).toInt()) { if (!pause.get()) Thread.sleep(100) }
                }
            }
            result
        }
        if (archive == null || pause.get()) {
            publish(InitialDesktopState.state.value.copy(phase = SetupPhase.PAUSED, message = getString(R.string.omarchy_setup_paused), bytesPerSecond = 0))
            return
        }
        publish(InitialDesktopState.state.value.copy(phase = SetupPhase.VERIFYING, downloaded = spec.bytes, message = getString(R.string.omarchy_setup_preparing_install), bytesPerSecond = 0))
        val begin = synchronized(controlLock) {
            if (pause.get()) false else { installing = true; true }
        }
        if (!begin) { publish(InitialDesktopState.state.value.copy(phase = SetupPhase.PAUSED, downloaded = spec.bytes)); return }
        publish(InitialDesktopState.state.value.copy(phase = SetupPhase.INSTALLING, downloaded = spec.bytes, message = getString(R.string.omarchy_setup_extracting)))
        installer.install(spec, archive)
        publish(DesktopSetupState(SetupPhase.COMPLETE, spec, spec.bytes, getString(R.string.omarchy_setup_complete)))
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
            message = getString(R.string.omarchy_setup_cleanup_failed))
            else DesktopSetupState(message = getString(R.string.omarchy_setup_cancelled)))
    }
    private fun publish(state: DesktopSetupState) = synchronized(controlLock) {
        // A blocked read can finish after onTimeout stopped the foreground service.
        // Preserve its PAUSED checkpoint instead of advertising an active transfer.
        if (foregroundTimedOut.get() && state.phase !in setOf(SetupPhase.COMPLETE, SetupPhase.FAILED)) return@synchronized
        InitialDesktopState.update(this, state)
        if (!foregroundTimedOut.get()) notify(state)
    }
    private fun notify(state: DesktopSetupState) { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(state)) }
    private fun notification(state: DesktopSetupState): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, DesktopSetupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.omarchy_setup_notification_title)).setContentIntent(open).setOnlyAlertOnce(true)
            .setContentText(state.message.ifEmpty { getString(R.string.omarchy_setup_progress_saved) })
            .setOngoing(state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING))
        val total = state.image?.bytes ?: 0
        if (total > 0 && state.phase == SetupPhase.DOWNLOADING) builder.setProgress(1000, (state.downloaded * 1000 / total).toInt(), false)
        else builder.setProgress(0, 0, state.phase == SetupPhase.INSTALLING || state.phase == SetupPhase.VERIFYING)
        if (state.phase == SetupPhase.DOWNLOADING) {
            val pauseIntent = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.omarchy_setup_pause_download), pauseIntent).build())
        }
        return builder.build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ grants only a few seconds here. Signal the worker and save
        // the latest checkpoint; never wait for its socket/read/extraction.
        pause.set(true)
        foregroundTimedOut.set(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Transfer checkpoints already persist regularly; load() maps an active
        // interrupted phase to PAUSED. Flush this pause off Main so a slow fsync
        // or in-flight state write cannot consume Android's stop grace period.
        Thread({
            try {
                synchronized(controlLock) {
                    val state = InitialDesktopState.state.value
                    if (state.phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING)) {
                        InitialDesktopState.update(this, state.copy(phase = SetupPhase.PAUSED,
                            message = getString(R.string.omarchy_setup_paused), bytesPerSecond = 0))
                    }
                }
            } catch (error: Exception) {
                Log.e("InitialDesktop", "Timeout checkpoint could not be saved: ${error.javaClass.simpleName}")
            }
        }, "InitialDesktop-timeout-checkpoint").apply { isDaemon = true; start() }
    }
    override fun onDestroy() { pause.set(true); worker.shutdown(); super.onDestroy() }
}
