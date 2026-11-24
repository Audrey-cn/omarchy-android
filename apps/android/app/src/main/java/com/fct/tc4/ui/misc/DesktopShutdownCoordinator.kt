// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread ownership gate; the scope must outlive the session's ViewModel. */
internal class DesktopShutdownCoordinator(
    private val scope: CoroutineScope,
    private val finish: () -> Unit,
    private val reportError: (Throwable) -> Unit = {}
) {
    private data class Session(val owner: Any, val stop: suspend () -> Unit)
    private var session: Session? = null
    var isStopping = false
        private set

    fun claim(owner: Any, stop: suspend () -> Unit): Boolean {
        if (isStopping) return false
        session = Session(owner, stop)
        return true
    }

    fun release(owner: Any) {
        if (!isStopping && session?.owner === owner) session = null
    }

    fun requestStop(): Job? {
        if (isStopping) return null
        isStopping = true
        val target = session
        return scope.launch {
            try {
                target?.stop?.invoke()
            } catch (error: Exception) {
                reportError(error)
            } catch (error: LinkageError) {
                reportError(error)
            } finally {
                session = null
                try {
                    finish()
                } finally {
                    isStopping = false
                }
            }
        }
    }
}
