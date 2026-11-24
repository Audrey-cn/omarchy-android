// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

/** Linux identity is (pid, startTime); checking both avoids signaling a reused PID. */
internal data class DesktopProcessSnapshot(
    val pid: Int,
    val parentPid: Int,
    val groupId: Int,
    val sessionId: Int,
    val startTime: Long,
    val tracerPid: Int = 0
)

internal fun parseDesktopProcessStat(stat: String): DesktopProcessSnapshot? {
    val endName = stat.lastIndexOf(')')
    if (endName < 0) return null
    val pid = stat.substringBefore(' ').toIntOrNull() ?: return null
    val fields = stat.substring(endName + 1).trim().split(Regex("\\s+"))
    if (fields.size < 20) return null
    return DesktopProcessSnapshot(pid, fields[1].toIntOrNull() ?: return null,
        fields[2].toIntOrNull() ?: return null, fields[3].toIntOrNull() ?: return null,
        fields[19].toLongOrNull() ?: return null)
}

/** Include changed job groups, setsid children and reparented PRoot tracees. */
internal fun desktopProcesses(rootPid: Int, processes: List<DesktopProcessSnapshot>): List<DesktopProcessSnapshot> {
    if (rootPid <= 0) return emptyList()
    val owned = mutableSetOf(rootPid)
    var added: Boolean
    do {
        added = false
        for (process in processes) {
            if (process.sessionId == rootPid || process.parentPid in owned || process.tracerPid in owned) {
                if (owned.add(process.pid)) added = true
            }
        }
    } while (added)
    return processes.filter { it.pid in owned }
}

/** Retain original identity after reparenting; a recycled numeric PID is unrelated. */
internal fun survivingDesktopProcesses(
    original: List<DesktopProcessSnapshot>,
    current: List<DesktopProcessSnapshot>
): List<DesktopProcessSnapshot> {
    val identities = original.associate { it.pid to it.startTime }
    return current.filter { identities[it.pid] == it.startTime }
}
