package com.fct.tc4.ui.misc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Read-only check of the Omarchy desktop launched against Termux:X11 display :0. */
internal class SwayDesktopProbe(private val runtimeDir: File) {
    private data class SocketStamp(val inode: Long, val modifiedAt: Long)
    private data class Candidate(val path: File, val stamp: SocketStamp, val pid: Int)
    private data class WaybarProcess(val pid: Int, val startTicks: Long)

    private val uid = Process.myUid()
    private val proc = File("/proc")
    private val x11Dir = File(runtimeDir, ".X11-unix")
    private val x11Socket = File(x11Dir, "X0")
    private val waylandSocket = File(runtimeDir, SwayOnboardingIpc.WAYLAND_SOCKET_NAME)

    suspend fun sample(timeoutMillis: Long): OnboardingDesktop? {
        val probe = ProbeConnection(SystemClock.elapsedRealtime() + timeoutMillis)
        return sampleOnboardingBefore(timeoutMillis, probe::close) { sample(probe) }
    }

    private fun sample(probe: ProbeConnection): OnboardingDesktop? {
        if (!isOwnedDirectory(runtimeDir) || !isOwnedDirectory(x11Dir)) return null
        val x11 = socketStamp(x11Socket) ?: return null
        val wayland = socketStamp(waylandSocket) ?: return null
        val candidates = runtimeDir.listFiles { _, name -> name.startsWith("sway-ipc.") }
            .orEmpty()
            .mapNotNull { path ->
                val stamp = socketStamp(path) ?: return@mapNotNull null
                val pid = SwayOnboardingIpc.candidatePid(path.name, x11.modifiedAt, stamp.modifiedAt)
                    ?: return@mapNotNull null
                Candidate(path, stamp, pid)
            }
            .sortedWith(compareByDescending<Candidate> { it.stamp.modifiedAt }.thenByDescending { it.pid })

        for (candidate in candidates.take(4)) {
            val swayStart = processStart(candidate.pid, "sway", SwayOnboardingIpc::matchesSwayEnvironment)
                ?: continue
            // Wayland's socket identity ties the launcher-configured wayland-1
            // to this compositor. Sway itself sets WAYLAND_DISPLAY after exec.
            if (probe.withPeer(waylandSocket, candidate.pid, uid) { true } != true) continue
            var matchedPeer = false
            val output = probe.withPeer(candidate.path, candidate.pid, uid) {
                matchedPeer = true
                readOutput(it, probe)
            }
            if (!matchedPeer) continue
            // A matching listener with an inactive desktop must finish starting.
            if (output == null) return null
            val waybar = findWaybar(candidate, swayStart) ?: return null
            if (socketStamp(x11Socket) != x11 || socketStamp(waylandSocket) != wayland ||
                socketStamp(candidate.path) != candidate.stamp
            ) return null
            return OnboardingDesktop(
                x11.inode, candidate.stamp.inode, candidate.pid, waybar.pid, waybar.startTicks, output
            )
        }
        return null
    }

    private fun isOwnedDirectory(path: File): Boolean {
        val stat = try { Os.lstat(path.path) } catch (_: ErrnoException) { return false }
        return OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid
    }

    private fun socketStamp(path: File): SocketStamp? {
        val stat = try { Os.lstat(path.path) } catch (_: ErrnoException) { return null }
        if (!OsConstants.S_ISSOCK(stat.st_mode) || stat.st_uid != uid) return null
        return SocketStamp(stat.st_ino, stat.st_mtime * 1_000_000_000 + stat.st_mtim.tv_nsec)
    }

    private fun readOutput(socket: LocalSocket, probe: ProbeConnection): OnboardingOutput? {
        fun request(type: Int): JSONArray = JSONArray(
            SwayOnboardingIpc.request(socket.inputStream, socket.outputStream, type) { probe.prepare(socket) }
        )

        val outputs = request(SwayOnboardingIpc.GET_OUTPUTS)
        val output = outputs.firstObject {
            it.optString("name") == "X11-1" && it.optBoolean("active") &&
                it.optBoolean("power", it.optBoolean("dpms"))
        } ?: return null
        val mode = output.getJSONObject("current_mode")
        val width = mode.getInt("width")
        val height = mode.getInt("height")
        if (width <= 0 || height <= 0) return null
        val workspaces = request(SwayOnboardingIpc.GET_WORKSPACES)
        val workspace = workspaces.firstObject {
            it.optBoolean("visible") && it.optString("output") == "X11-1" &&
                it.optString("name") == output.optString("current_workspace")
        } ?: return null
        val outputRect = output.getJSONObject("rect")
        val workspaceRect = workspace.getJSONObject("rect")
        val barHeight = SwayOnboardingIpc.topReservation(
            outputRect.getInt("y"), outputRect.getInt("height"),
            workspaceRect.getInt("y"), workspaceRect.getInt("height")
        ) ?: return null
        return OnboardingOutput(width, height, barHeight)
    }

    private fun findWaybar(candidate: Candidate, swayStart: Long): WaybarProcess? {
        return proc.list().orEmpty()
            .asSequence()
            .mapNotNull(String::toIntOrNull)
            .mapNotNull { pid ->
                val start = processStart(pid, "waybar") {
                    SwayOnboardingIpc.matchesWaybarEnvironment(it, candidate.path.name)
                }
                start?.takeIf { it >= swayStart }?.let { WaybarProcess(pid, it) }
            }
            .maxByOrNull { it.startTicks }
    }

    private fun processStart(pid: Int, name: String, matchesEnvironment: (InputStream) -> Boolean): Long? {
        val directory = File(proc, pid.toString())
        return try {
            if (Os.stat(directory.path).st_uid != uid) return null
            val stat = File(directory, "stat")
            val start = SwayOnboardingIpc.liveProcessStart(stat.readText(), name) ?: return null
            if (!File(directory, "environ").inputStream().use(matchesEnvironment)) return null
            start.takeIf { SwayOnboardingIpc.liveProcessStart(stat.readText(), name) == start }
        } catch (_: ErrnoException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private class ProbeConnection(private val expiresAt: Long) {
        private val lock = Any()
        private var closed = false
        private var active: LocalSocket? = null

        fun prepare(socket: LocalSocket) {
            val remaining = expiresAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException("Sway IPC deadline expired")
            socket.soTimeout = remaining.coerceAtMost(400).toInt()
        }

        fun <T> withPeer(path: File, pid: Int, uid: Int, read: (LocalSocket) -> T): T? = try {
            LocalSocket().use { socket ->
                // The stream creates the descriptor before the timeout and
                // cancellation handler can operate on it.
                socket.inputStream
                synchronized(lock) {
                    if (closed) return null
                    active = socket
                }
                try {
                    prepare(socket)
                    socket.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    val peer = socket.peerCredentials
                    if (SwayOnboardingIpc.matchesPeer(pid, uid, peer.pid, peer.uid)) read(socket) else null
                } finally {
                    synchronized(lock) { active = null }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }

        fun close() {
            val socket = synchronized(lock) {
                closed = true
                active
            }
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private inline fun JSONArray.firstObject(predicate: (JSONObject) -> Boolean): JSONObject? {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            if (predicate(item)) return item
        }
        return null
    }
}
