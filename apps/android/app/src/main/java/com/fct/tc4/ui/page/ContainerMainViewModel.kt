// ContainerMainViewModel.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.app.Application
import android.app.ActivityManager
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.x11.CmdEntryPointService
import com.offsec.nhterm.backend.TerminalSession
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.fct.tc4.TinyAudio
import com.fct.tc4.TinyMicrophone
import com.fct.tc4.R
import com.fct.tc4.TinyIpp
import com.fct.tc4.TinyStorage
import com.fct.tc4.ProotRuntimePolicy
import com.fct.tc4.ui.misc.ConfigManager
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.misc.OmarchyKeepAliveService
import com.fct.tc4.ui.misc.UpdateChecker
import com.fct.tc4.ui.misc.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.BindException
import java.net.DatagramSocket
import java.net.ServerSocket

sealed interface GuiNavigationEvent {
    data class OpenWebView(val link: String) : GuiNavigationEvent
    data class OpenAvnc(
        val link: String,
        val adaptToScreenSize: Boolean,
        val scaleRatio: Double,
        val useUnixSocket: Boolean
    ) : GuiNavigationEvent
    data object OpenX11 : GuiNavigationEvent
}

class ContainerMainViewModel(
    application: Application,
    private val code: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ContainerMainVM"
    }

    /** 是否启动过 X server（避免未启动时就发送 ACTION_STOP 导致创建 :xserver 进程） */
    private var xserverStarted = false
    private var exited = false
    private var explicitExitCompleted = false
    private var ownedSession: TerminalSession? = null
    private var initializationJob: Job? = null
    private val xserverMutex = Mutex()

    private lateinit var config: Map<String, Any>

    private val _configState = MutableStateFlow<Map<String, Any>?>(null)
    val configState: StateFlow<Map<String, Any>?> = _configState.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<GuiNavigationEvent>(extraBufferCapacity = 64)
    val navigationEvents: SharedFlow<GuiNavigationEvent> = _navigationEvents.asSharedFlow()

    private val _updateEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val updateEvent: SharedFlow<String> = _updateEvent.asSharedFlow()

    init {
        initializationJob = viewModelScope.launch {
            val loaded = ConfigManager.load(code)
            if (loaded == null) {
                Global.x11SessionActive = false
                _errorMessage.value = getApplication<Application>().getString(R.string.tc4_container_config_lost, code)
                _isInitialized.value = true
                return@launch
            }
            config = loaded
            _configState.value = config
            if (config["boot_command"] !is String) {
                Global.x11SessionActive = false
                _errorMessage.value = getApplication<Application>().getString(R.string.tc4_container_missing_boot_cmd, code)
                _isInitialized.value = true
                return@launch
            }
            // The desktop is persistent user state. App/native updates must not
            // replace or retire guest files when APK payloads change or disappear.
            // New Linux contents are delivered only through explicit initial install.
            if (!setupEnvironment()) {
                Global.x11SessionActive = false
                _isInitialized.value = true
                return@launch
            }
            startKeepAliveService()
            var launchCompleted = false
            try {
                withContext(Dispatchers.IO) {
                    launchContainer()
                }
                launchCompleted = _errorMessage.value == null && !exited
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Container startup failed", error)
                _errorMessage.value = getApplication<Application>().getString(R.string.omarchy_container_start_failed)
            } finally {
                if (!launchCompleted) {
                    stopRuntime()
                }
            }
            _isInitialized.value = true
            if (!launchCompleted) return@launch

            // 自动检测更新
            if (Global.autoCheckUpdate) {
                viewModelScope.launch(Dispatchers.IO) {
                    when (val result = UpdateChecker.check(getApplication())) {
                        is UpdateResult.NewVersion -> _updateEvent.tryEmit(result.version)
                        else -> {}
                    }
                }
            }

            // 检查是否有待执行的捷径命令
            if (code == com.fct.tc4.ui.main.MainViewModel.pendingCommandCode) {
                val cmd = com.fct.tc4.ui.main.MainViewModel.pendingCommandText
                if (cmd != null) {
                    delay(2000)
                    Global.sendCommand(cmd)
                }
                com.fct.tc4.ui.main.MainViewModel.clearPendingCommand()
            }
        }
    }

    fun exitContainer() {
        if (explicitExitCompleted) return
        explicitExitCompleted = true
        stopRuntime()
        initializationJob?.cancel()
        if (!::config.isInitialized) return
        // Startup failure already stopped runtime resources. Explicit Exit must
        // still reset its host shell, but may never replace a newer VM's shell.
        val session = ownedSession ?: return
        if (Global.terminalSession !== session) return
        Global.newSession()
        Global.setupEnvironment()
        Global.sendCommand("rm ${getApplication<Application>().filesDir}/boot_${code}.sh")
        val merged = collectEnabledOptions()
        val application = getApplication<Application>()
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        merged.env.removeAll { it.substringBefore('=') in setOf("OMARCHY_APK_VERSION_CODE", "OMARCHY_APK_VERSION_NAME", "OMARCHY_RUNTIME_API", "OMARCHY_RUNTIME_KIND") }
        merged.env.addAll(com.fct.tc4.OmarchyRuntimeIdentity.environment(
            code, packageInfo.versionCode, packageInfo.versionName ?: "unknown"))
        for (cmd in merged.postEndHostCommands) {
            Global.sendCommand(cmd)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun enterGui() {
        if (exited || !::config.isInitialized) return
        Global.userRequestedManagementPage = false
        viewModelScope.launch {
            initializationJob?.join()
            if (exited) return@launch
            val features = config["feature"] as? List<Map<String, Any>> ?: emptyList()
            for (feature in features) {
                val type = feature["type"] as? String ?: continue
                if (feature["enabled"] as? Boolean != true) continue
                when (type) {
                    "webview" -> {
                        val link = feature["link"] as? String ?: return@launch
                        _navigationEvents.tryEmit(GuiNavigationEvent.OpenWebView(link))
                    }
                    "avnc" -> {
                        val link = feature["link"] as? String ?: return@launch
                        val adaptToScreenSize = feature["adapt_to_screen_size"] as? Boolean ?: return@launch
                        val scaleRatio = (feature["scale_ratio"] as? Number)?.toDouble() ?: return@launch
                        val useUnixSocket = feature["use_unix_socket"] as? Boolean ?: true
                        _navigationEvents.tryEmit(GuiNavigationEvent.OpenAvnc(link, adaptToScreenSize, scaleRatio, useUnixSocket))
                    }
                    "x11" -> {
                        val args = feature["args"] as? List<String> ?: continue
                        val command = feature["command"] as? String ?: continue
                        if (!ensureXServer(args, command)) return@launch
                        _navigationEvents.tryEmit(GuiNavigationEvent.OpenX11)
                    }
                }
            }
        }
    }

    private fun setupEnvironment(): Boolean {
        val app = getApplication<Application>()
        val runtime = ProotRuntimePolicy.select(code, config["boot_command"] as? String, Global.useLegacyProot)
        val nativeDirectory = File(app.applicationInfo.nativeLibraryDir)
        val usesReadonlyRuntime = runtime.id == "omarchy-proot-readonly-v1"
        if (usesReadonlyRuntime && listOf(runtime.executableLibrary, runtime.loaderLibrary, runtime.loader32Library)
                .any { name -> File(nativeDirectory, name).let { !it.isFile || !it.canExecute() } }) {
            Log.e(TAG, "Bundled Omarchy PRoot runtime is incomplete in $nativeDirectory")
            _errorMessage.value = app.getString(R.string.tc4_bundled_runtime_incomplete)
            return false
        }
        Global.terminalSession?.finishIfRunning()
        ownedSession = Global.newSession(onFinished = { exitCode ->
            // An exited VM can receive its old shell's callback after a new session starts.
            if (exited) return@newSession
            if (Global.terminalSession !== ownedSession) return@newSession
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (exited) return@launch
                if (Global.terminalSession !== ownedSession) return@launch
                val onSignal9 = Global.onSessionSignal9.takeIf { exitCode == -9 }
                stopRuntime(app.getString(R.string.omarchy_container_start_failed))
                initializationJob?.cancel()
                onSignal9?.invoke()
            }
        })
        Global.onSessionSignal9 = {
            val intent = Intent(getApplication(), Signal9Activity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
        Global.setupEnvironment()
        val containerDir = "${app.dataDir.absolutePath}/$code"
        Global.sendCommand("export CONTAINER_DIR=$containerDir")
        // setupEnvironment() installs Tiny's old loader pair first. Override both
        // only for Omarchy's generic PRoot command, including existing installs.
        val runtimeDirectory = if (usesReadonlyRuntime) nativeDirectory else File(app.filesDir, "applib")
        if (usesReadonlyRuntime) {
            Global.sendCommand("export PROOT_LOADER=${File(runtimeDirectory, runtime.loaderLibrary).absolutePath}")
            Global.sendCommand("export PROOT_LOADER_32=${File(runtimeDirectory, runtime.loader32Library).absolutePath}")
        }
        Global.sendCommand(
            "ln -sf ${File(runtimeDirectory, runtime.executableLibrary).absolutePath}" +
            " ${app.filesDir.absolutePath}/bootstrap/bin/proot"
        )
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun launchContainer() {

        val merged = collectEnabledOptions()
        // 处理 lstat-cache feature：解析路径，生成 --assured-path= 参数
        merged.args.addAll(collectLstatCacheArgs())
        // 处理 storage feature：生成 --tiny-storage 和初始 --bind= 参数
        collectStorageArgs(merged.args)
        for (cmd in merged.preStartHostCommands) {
            sendStartupCommand(cmd)
        }

        val bootCmd = config["boot_command"] as? String
        if (bootCmd == null) {
            _errorMessage.value = getApplication<Application>().getString(R.string.tc4_container_missing_boot_cmd, code)
            return
        }
        val resolvedBootCmd = bootCmd
            .replace("\$EXTRA_ARGS", merged.args.joinToString(" "))
            .replace("\$EXTRA_PATH", merged.path.joinToString(":"))
            .replace("\$EXTRA_LD_LIBRARY_PATH", merged.ldLibraryPath.joinToString(":"))
            .replace("\$EXTRA_LD_PRELOAD", merged.ldPreload.joinToString(" "))
            .replace("\$EXTRA_ENV", merged.env.joinToString(" "))

        // 写入临时脚本文件，绕过 PTY 单行 4096 字节限制
        val bootScript = File("${getApplication<Application>().filesDir}/boot_${code}.sh")
        currentCoroutineContext().ensureActive()
        bootScript.writeText("exec $resolvedBootCmd")
        sendStartupCommand("source ${bootScript.absolutePath}")

        for (cmd in merged.postStartContainerCommands) {
            sendStartupCommand(cmd)
        }

        launchEnabledFeature()
    }

    private suspend fun sendStartupCommand(command: String) = withContext(Dispatchers.Main) {
        // Exit/reset also runs on Main. Never dispatch an old startup command
        // into the fresh host shell after exitContainer replaces Global's session.
        if (exited || ownedSession == null || Global.terminalSession !== ownedSession) {
            throw CancellationException("Container session was replaced or exited")
        }
        Global.sendCommand(command)
    }

    // ===================== 图形界面处理 =====================

    @Suppress("UNCHECKED_CAST")
    private suspend fun launchEnabledFeature() {
        val features = config["feature"] as? List<Map<String, Any>> ?: emptyList()
        for (feature in features) {
            currentCoroutineContext().ensureActive()
            val type = feature["type"] as? String ?: continue
            if (feature["enabled"] as? Boolean != true) continue
            when (type) {
                "audio" -> {
                    withContext(Dispatchers.Main) { if (!exited) TinyAudio.start() }
                }
                "print" -> {
                    withContext(Dispatchers.Main) { if (!exited) TinyIpp.start() }
                }
                "storage" -> {
                    withContext(Dispatchers.Main) { if (!exited) TinyStorage.start() }
                }
                "webview" -> {
                    if (!Global.autoLaunchGui) continue
                    val link = feature["link"] as? String ?: continue
                    val command = feature["command"] as? String ?: continue
                    sendStartupCommand(command)
                    val port = extractPort(link)
                    Log.d(TAG, "webview: link=$link port=$port waiting for port...")
                    if (port == null) continue
                    if (!waitForPort(port)) {
                        Log.e(TAG, "webview: port=$port not ready, giving up")
                        continue
                    }
                    _navigationEvents.tryEmit(GuiNavigationEvent.OpenWebView(link))
                }
                "avnc" -> {
                    if (!Global.autoLaunchGui) continue
                    val link = feature["link"] as? String ?: continue
                    val command = feature["command"] as? String ?: continue
                    val adaptToScreenSize = feature["adapt_to_screen_size"] as? Boolean ?: continue
                    val scaleRatio = (feature["scale_ratio"] as? Number)?.toDouble() ?: continue
                    val useUnixSocket = feature["use_unix_socket"] as? Boolean ?: true
                    sendStartupCommand(command)
                    if (useUnixSocket) {
                        if (!waitForFile("${getApplication<Application>().filesDir}/tmp/.tiny.vnc")) {
                            continue
                        }
                    } else {
                        val port = extractPort(link)
                        Log.d(TAG, "avnc: link=$link port=$port waiting for port...")
                        if (port == null) continue
                        if (!waitForPort(port)) {
                            Log.e(TAG, "avnc: port=$port not ready, giving up")
                            continue
                        }
                    }
                    _navigationEvents.tryEmit(GuiNavigationEvent.OpenAvnc(link, adaptToScreenSize, scaleRatio, useUnixSocket))
                }
                "x11" -> {
                    if (!Global.autoLaunchGui) continue
                    val args = feature["args"] as? List<String> ?: continue
                    val command = feature["command"] as? String ?: continue
                    if (!ensureXServer(args, command)) return
                    _navigationEvents.tryEmit(GuiNavigationEvent.OpenX11)
                }
            }
        }
    }

    /** One owner covers initial launch and a return after Android killed the X server. */
    private suspend fun ensureXServer(xserverArgs: List<String>, guestCommand: String): Boolean = withContext(Dispatchers.Main) {
        xserverMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (exited) return@withLock false
            val app = getApplication<Application>()
            val socket = File(app.filesDir, "tmp/.X11-unix/X${extractDisplay(xserverArgs)}")
            if (xserverStarted && isXSocketReady(socket)) return@withLock !exited
            val restarting = xserverStarted
            xserverStarted = false
            // ACTION_STOP kills the process, while BIND_AUTO_CREATE may create
            // a fresh idle service immediately. Wait for the OLD PIDs only.
            val previousPids = xserverProcesses().map { it.pid }.toSet()
            if (previousPids.isNotEmpty()) {
                try {
                    app.startService(Intent(app, CmdEntryPointService::class.java).apply {
                        action = CmdEntryPointService.ACTION_STOP
                    })
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Previous X server could not be stopped", error)
                    stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
                    return@withLock false
                }
                val stopped = withTimeoutOrNull(2_000L) {
                    while (xserverProcesses().any { it.pid in previousPids }) delay(50)
                    true
                } ?: false
                if (!stopped) {
                    Log.e(TAG, "Previous X server did not stop; refusing duplicate native startup")
                    stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
                    return@withLock false
                }
            }
            currentCoroutineContext().ensureActive()
            if (exited) return@withLock false
            if (socket.exists() && !socket.delete()) {
                Log.e(TAG, "Cannot remove stale X server socket: $socket")
                stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
                return@withLock false
            }
            val envKeys = arrayOf(
                "TMPDIR", "XKB_CONFIG_ROOT",
                "TERMUX_X11_DEBUG", "TERMUX_X11_OVERRIDE_PACKAGE"
            )
            val envVals = arrayOf(
                "${app.filesDir.absolutePath}/tmp",
                "${app.dataDir.absolutePath}/$code/usr/share/X11/xkb",
                "1",
                app.packageName
            )
            try {
                app.startService(Intent(app, CmdEntryPointService::class.java).apply {
                    action = CmdEntryPointService.ACTION_START
                    putExtra(CmdEntryPointService.EXTRA_ARGS, xserverArgs.toTypedArray())
                    putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
                    putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
                })
            } catch (error: RuntimeException) {
                Log.e(TAG, "X server service could not be started", error)
                stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
                return@withLock false
            }
            xserverStarted = true
            val ready = withTimeoutOrNull(15_000L) {
                while (!exited && !isXSocketReady(socket)) delay(100)
                !exited
            } ?: false
            if (!ready && !exited) {
                Log.e(TAG, "X server socket did not become ready: $socket")
                stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
            }
            if (!ready) return@withLock false
            // Starting only the Android listener cannot revive an X11-backed
            // compositor that exited when the old listener died.
            if (!startGuestDisplay(guestCommand, restarting)) {
                if (!exited) stopRuntime(app.getString(R.string.omarchy_xserver_restart_failed))
                return@withLock false
            }
            !exited
        }
    }

    private suspend fun startGuestDisplay(command: String, restarting: Boolean): Boolean {
        if (!restarting || code != "omarchy") {
            sendStartupCommand(command)
            return true
        }
        val app = getApplication<Application>()
        val completion = File(app.filesDir, "tmp/.omarchy-x11-recovery-${java.util.UUID.randomUUID()}")
        // /tmp is the guest bind of filesDir/tmp. Never replay a legacy startup
        // block on recovery: it can duplicate audio, D-Bus and deck daemons.
        sendStartupCommand("( if [ -x /usr/local/bin/omarchy-recover-x11 ]; then " +
            "/usr/local/bin/omarchy-recover-x11 >/tmp/x11-recovery.log 2>&1; result=\$?; " +
            "else result=127; fi; printf '%s\\n' \"\$result\" > /tmp/${completion.name}; ) &")
        return try {
            val status = withTimeoutOrNull(30_000L) {
                while (!exited) {
                    val result = withContext(Dispatchers.IO) {
                        try { if (completion.isFile) completion.readText().trim().toIntOrNull() else null }
                        catch (_: java.io.IOException) { null }
                    }
                    if (result != null) return@withTimeoutOrNull result
                    delay(100)
                }
                null
            }
            if (status != 0) Log.e(TAG, "Guest display recovery failed (status=$status); see /tmp/x11-recovery.log")
            status == 0 && !exited
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { completion.delete() }
        }
    }

    private fun xserverProcesses(): List<ActivityManager.RunningAppProcessInfo> {
        val app = getApplication<Application>()
        return app.getSystemService(ActivityManager::class.java).runningAppProcesses
            .orEmpty().filter { it.processName == "${app.packageName}:xserver" }
    }

    /** A stale filesystem entry is not evidence of a listening X server. */
    private suspend fun isXSocketReady(socket: File): Boolean = withContext(Dispatchers.IO) {
        if (!socket.exists() || xserverProcesses().isEmpty()) return@withContext false
        try {
            LocalSocket().use { client ->
                client.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
            true
        } catch (_: java.io.IOException) { false }
    }

    private fun startKeepAliveService() {
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(app, Intent(app, OmarchyKeepAliveService::class.java))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Android did not permit background residency", error)
        }
    }

    private fun stopKeepAliveService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, OmarchyKeepAliveService::class.java))
    }

    private fun killXServer() {
        if (xserverStarted || xserverProcesses().isNotEmpty()) {
            xserverStarted = false
            // ACTION_START can still be queued before :xserver appears in the
            // process list. Queue STOP too so an immediate exit cannot orphan it.
            val app = getApplication<Application>()
            try {
                app.startService(Intent(app, CmdEntryPointService::class.java).apply {
                    action = CmdEntryPointService.ACTION_STOP
                })
            } catch (error: RuntimeException) {
                Log.w(TAG, "X server stop service was rejected; stopping this app's process directly", error)
                app.stopService(Intent(app, CmdEntryPointService::class.java))
                app.getSystemService(ActivityManager::class.java).runningAppProcesses
                    ?.filter { it.processName == "${app.packageName}:xserver" }
                    ?.forEach { android.os.Process.killProcess(it.pid) }
            }
        }
    }

    /** Main-thread terminal state shared by startup failure, shell exit and explicit exit. */
    private fun stopRuntime(message: String? = null): Boolean {
        if (exited) return false
        exited = true
        if (message != null) _errorMessage.value = message
        _isInitialized.value = true
        if (ownedSession == null || Global.terminalSession !== ownedSession) return false
        Global.x11SessionActive = false
        Global.onSessionSignal9 = null
        // Try every owner even if an individual bridge's teardown fails.
        val cleanup: List<Pair<String, () -> Unit>> = listOf(
            "keepalive" to { stopKeepAliveService() },
            "X server" to { killXServer() },
            "audio" to { TinyAudio.stop() },
            "microphone" to { TinyMicrophone.stop() },
            "printing" to { TinyIpp.stop() },
            "storage" to { TinyStorage.stop() },
            "container shell" to {
                val session = ownedSession
                val pid = session?.pid ?: 0
                // Preserve process-group termination for PRoot descendants;
                // finishIfRunning alone may terminate only the terminal PID.
                if (pid > 0) {
                    try { Os.kill(-pid, OsConstants.SIGQUIT) }
                    catch (error: android.system.ErrnoException) {
                        if (error.errno != OsConstants.ESRCH) Log.w(TAG, "Container process group stop failed", error)
                    }
                }
                session?.finishIfRunning()
            }
        )
        for ((owner, stop) in cleanup) {
            try { stop() }
            catch (error: Exception) { Log.w(TAG, "$owner cleanup failed", error) }
            catch (error: LinkageError) { Log.w(TAG, "$owner cleanup unavailable", error) }
        }
        return true
    }

    override fun onCleared() {
        stopRuntime()
        initializationJob?.cancel()
        super.onCleared()
    }

    private fun extractDisplay(args: List<String>): String {
        for (arg in args) {
            if (arg.startsWith(":")) return arg.drop(1)
        }
        return "0"
    }

    /**
     * 收集所有启用的 lstat-cache feature 的路径，解析通配符和变量，
     * 生成 --assured-path= 格式的 proot 参数列表。
     */
    @Suppress("UNCHECKED_CAST")
    private fun collectLstatCacheArgs(): List<String> {
        val features = config["feature"] as? List<Map<String, Any>> ?: return emptyList()
        val app = getApplication<Application>()
        val cacheDir = app.filesDir.absolutePath
        val containerDir = "${app.dataDir.absolutePath}/$code"

        val result = mutableListOf<String>()
        for (feature in features) {
            val type = feature["type"] as? String ?: continue
            if (type != "lstat-cache") continue
            if (feature["enabled"] as? Boolean != true) continue
            val paths = feature["path"] as? List<String> ?: continue

            for (rawPath in paths) {
                // 替换变量
                val resolved = rawPath
                    .replace("\$CACHE_DIR", cacheDir)
                    .replace("\$CONTAINER_DIR", containerDir)

                // 处理通配符
                val expanded = expandLstatPath(resolved)
                for (p in expanded) {
                    val escaped = p.replace(" ", "\\ ")
                    result.add("--assured-path=$escaped")
                }
            }
        }
        return result
    }

    private fun expandLstatPath(path: String): List<String> {
        val starIdx = path.indexOf('*')
        if (starIdx == -1) return listOf(path)

        // * 前后最近的两个 / 切出父目录和剩余路径
        val slashBeforeStar = path.lastIndexOf('/', starIdx)
        val slashAfterStar = path.indexOf('/', starIdx)

        val parentDir = if (slashBeforeStar >= 0) {
            path.substring(0, slashBeforeStar)
        } else {
            Log.w(TAG, "lstat-cache: * 前没有 /，无法定位父目录 : $path")
            return emptyList()
        }

        val suffix = if (slashAfterStar >= 0) path.substring(slashAfterStar) else ""

        val wildcardSegment = path.substring(slashBeforeStar + 1,
            if (slashAfterStar >= 0) slashAfterStar else path.length)

        val dir = File(parentDir)
        if (!dir.isDirectory) {
            Log.w(TAG, "lstat-cache: 父目录不存在 : $parentDir")
            return emptyList()
        }

        val pattern = globToRegex(wildcardSegment)
        return try {
            val files = dir.listFiles()
            if (files == null) {
                Log.w(TAG, "lstat-cache: 无法列出目录 : $parentDir")
                return emptyList()
            }
            val matched = files
                .filter { it.name != "." && it.name != ".." && pattern.matches(it.name) }
                .map { File(it.absolutePath + suffix) }
                .filter { candidate ->
                    val parent = candidate.parentFile ?: return@filter false
                    try {
                        parent.listFiles()?.any { it.name == candidate.name } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                .map { it.absolutePath }
            if (matched.isNotEmpty()) {
                Log.i(TAG, "lstat-cache: $parentDir/*$suffix → ${matched.size} 个路径")
            } else {
                Log.w(TAG, "lstat-cache: 无匹配, $parentDir/*$suffix, ${files.size} 个候选")
            }
            matched
        } catch (e: SecurityException) {
            Log.w(TAG, "lstat-cache: 无权限 : $parentDir", e)
            emptyList()
        }
    }

    /**
     * 如果 storage feature 启用，检测已插入的外部存储设备，
     * 将 --tiny-storage 和初始 --bind= 参数注入 args 列表。
     */
    @Suppress("UNCHECKED_CAST")
    private fun collectStorageArgs(args: MutableList<String>) {
        val features = config["feature"] as? List<Map<String, Any>> ?: return
        val storageFeature = features.firstOrNull {
            (it["type"] as? String) == "storage" && it["enabled"] == true
        } ?: return

        // 通知 proot 启动 socket listener
        args.add("--tiny-storage")

        // 检测当前已挂载的外部存储，添加静态 bind
        val devices = TinyStorage.detectExternalStorage()
        for ((path, name) in devices) {
            args.add("--bind=$path:/mnt/$name")
        }
    }

    /** glob → regex，* 通配任意字符 */
    private fun globToRegex(glob: String): Regex {
        val parts = glob.split("*").map { Regex.escape(it) }
        return Regex("^${parts.joinToString(".*")}$")
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectEnabledOptions(): MergedOptions {
        val rawOptions = config["options"] as? List<Map<String, Any>> ?: emptyList()
        return collectOptionsRecursive(rawOptions)
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectOptionsRecursive(options: List<Map<String, Any>>): MergedOptions {
        val result = MergedOptions()
        for (option in options) {
            val type = option["type"] as? String ?: continue
            if (type == "options") {
                val subs = option["options"] as? List<Map<String, Any>> ?: emptyList()
                result += collectOptionsRecursive(subs)
            } else if (type == "option") {
                if (option["enabled"] as? Boolean == true) {
                    result += option
                }
            }
        }
        return result
    }

    private data class MergedOptions(
        val env: MutableList<String> = mutableListOf(),
        val path: MutableList<String> = mutableListOf(),
        val ldLibraryPath: MutableList<String> = mutableListOf(),
        val ldPreload: MutableList<String> = mutableListOf(),
        val args: MutableList<String> = mutableListOf(),
        val preStartHostCommands: MutableList<String> = mutableListOf(),
        val postStartContainerCommands: MutableList<String> = mutableListOf(),
        val postEndHostCommands: MutableList<String> = mutableListOf()
    ) {
        @Suppress("UNCHECKED_CAST")
        operator fun plusAssign(option: Map<String, Any>) {
            (option["env"] as? List<String>)?.let { env.addAll(it) }
            (option["path"] as? List<String>)?.let { path.addAll(it) }
            (option["ld_library_path"] as? List<String>)?.let { ldLibraryPath.addAll(it) }
            (option["ld_preload"] as? List<String>)?.let { ldPreload.addAll(it) }
            (option["args"] as? List<String>)?.let { args.addAll(it) }
            (option["pre_start_host_command"] as? String)?.let { preStartHostCommands.add(it) }
            (option["post_start_container_command"] as? String)?.let { postStartContainerCommands.add(it) }
            (option["post_end_host_command"] as? String)?.let { postEndHostCommands.add(it) }
        }

        operator fun plusAssign(other: MergedOptions) {
            env.addAll(other.env)
            path.addAll(other.path)
            ldLibraryPath.addAll(other.ldLibraryPath)
            ldPreload.addAll(other.ldPreload)
            args.addAll(other.args)
            preStartHostCommands.addAll(other.preStartHostCommands)
            postStartContainerCommands.addAll(other.postStartContainerCommands)
            postEndHostCommands.addAll(other.postEndHostCommands)
        }
    }

    private fun extractPort(link: String): Int? {
        val portRegex = Regex(":(\\d+)(?:/|$)")
        return portRegex.find(link)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long = 15_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (checkTcpPort(port) || checkUdpPort(port)) {
                    return@withTimeoutOrNull true
                }
                delay(500)
            }
            return@withTimeoutOrNull false
        } ?: false
    }

    private fun checkTcpPort(port: Int): Boolean {
        return try {
            ServerSocket(port).close()
            false
        } catch (e: BindException) {
            true
        }
    }

    private fun checkUdpPort(port: Int): Boolean {
        return try {
            DatagramSocket(port).close()
            false
        } catch (e: BindException) {
            true
        }
    }

    private suspend fun waitForFile(filePath: String, timeoutMs: Long = 15_000): Boolean {
        val file = File(filePath)
        var timer = 0
        while (timeoutMs > timer) {
            if (file.exists()) return true
            delay(500)
            timer += 500
        }
        return false
    }


    fun saveConfig() {
        ConfigManager.save(code, config)
    }

    class Factory(private val application: Application, private val code: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContainerMainViewModel(application, code) as T
        }
    }
}
