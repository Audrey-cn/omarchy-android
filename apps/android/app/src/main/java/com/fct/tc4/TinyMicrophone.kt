// TinyMicrophone.kt -- This file is part of tiny_container.
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

package com.fct.tc4

import android.util.Log
import com.fct.tc4.ui.misc.Global
import java.io.File

/** Owns one native stream and reconnects until explicitly stopped. */
object TinyMicrophone {
    private const val TAG = "TinyMicrophone"
    private const val INITIAL_RETRY_MS = 250L
    private const val MAX_RETRY_MS = 5_000L
    private const val HEALTH_CHECK_MS = 250L

    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var workerThread: Thread? = null
    // Accessed only by the supervisor, including across worker replacement.
    private var libraryLoaded = false

    @JvmStatic private external fun nativeStart(socketPath: String): Boolean
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeStop()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        // A stopped worker retains ownership until its nativeStop/join finishes.
        if (workerThread == null) startWorker()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        generation++
        workerThread?.interrupt()
        // JNI shutdown/join belongs to the worker, never the Android UI thread.
    }

    private fun startWorker() {
        val socketPath = File(Global.appContext.filesDir, "tmp/.tiny.mic").absolutePath
        workerThread = Thread({ supervise(socketPath) }, "TinyMic-supervisor").apply {
            isDaemon = true
            start()
        }
    }

    private fun isCurrent(session: Long) = running && generation == session

    private fun supervise(socketPath: String) {
        var retryMs = INITIAL_RETRY_MS
        try {
            while (running) {
                val session = generation
                val startedAt = System.nanoTime()
                var nativeAttempted = false
                try {
                    if (!libraryLoaded) {
                        System.loadLibrary("tiny_microphone_jni")
                        libraryLoaded = true
                    }
                    if (!isCurrent(session)) continue
                    nativeAttempted = true
                    if (nativeStart(socketPath)) {
                        // nativeStart returns after creating its pthread. Observe
                        // that thread so an EOF/device error can reconnect later.
                        while (isCurrent(session) && nativeIsRunning()) Thread.sleep(HEALTH_CHECK_MS)
                    }
                } catch (error: InterruptedException) {
                    // stop() wakes both health polling and retry backoff.
                } catch (error: Exception) {
                    Log.w(TAG, "Native stream attempt failed", error)
                } catch (error: LinkageError) {
                    Log.w(TAG, "Native stream library unavailable", error)
                } finally {
                    if (nativeAttempted) {
                        try { nativeStop() }
                        catch (error: Exception) { Log.w(TAG, "Native stream cleanup failed", error) }
                        catch (error: LinkageError) { Log.w(TAG, "Native stream cleanup unavailable", error) }
                    }
                }
                if (!running) break
                if (!isCurrent(session)) { retryMs = INITIAL_RETRY_MS; continue }
                if (System.nanoTime() - startedAt >= 5_000_000_000L) retryMs = INITIAL_RETRY_MS
                try { Thread.sleep(retryMs) }
                catch (error: InterruptedException) { /* Recheck ownership before another attempt. */ }
                retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        } finally {
            synchronized(this) {
                workerThread = null
                // start() may arrive while the old worker is joining its pthread.
                if (running) startWorker()
            }
        }
    }
}
