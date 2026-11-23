// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicBoolean

enum class SetupPhase { IDLE, DOWNLOADING, IMPORTING, PAUSED, VERIFYING, INSTALLING, COMPLETE, FAILED }
data class DesktopSetupState(val phase: SetupPhase = SetupPhase.IDLE, val image: ImageSpec? = null,
    val downloaded: Long = 0, val message: String = "", val bytesPerSecond: Long = 0)

object InitialDesktopState {
    val running = AtomicBoolean(false)
    private val mutable = MutableStateFlow(DesktopSetupState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var lastSaved = 0L
    fun directory(context: Context) = File(context.filesDir, "initial-desktop").apply {
        require(!Files.isSymbolicLink(toPath()))
        if (!isDirectory) check(mkdirs())
    }
    @Synchronized fun load(context: Context) {
        if (initialized) return
        initialized = true
        val file = File(directory(context), "state.json")
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > InitialImageCatalog.MAX_BYTES) return
        try {
            val json = JSONObject(file.readText())
            val image = InitialImageCatalog.parse(json.getString("image")).single()
            var phase = SetupPhase.valueOf(json.getString("phase"))
            if (phase in setOf(SetupPhase.DOWNLOADING, SetupPhase.IMPORTING, SetupPhase.VERIFYING, SetupPhase.INSTALLING)) phase = SetupPhase.PAUSED
            if (phase == SetupPhase.COMPLETE && !File(context.dataDir, "omarchy").isDirectory) phase = SetupPhase.IDLE
            mutable.value = DesktopSetupState(phase, image, json.optLong("downloaded").coerceIn(0, image.bytes),
                if (phase == SetupPhase.PAUSED) "此前任务已中断，进度已保留，可继续。" else json.optString("message"))
        } catch (_: Exception) { mutable.value = DesktopSetupState(message = "此前任务信息不可用，请重新选择镜像。") }
    }
    @Synchronized fun update(context: Context, next: DesktopSetupState, persist: Boolean = true) {
        mutable.value = next
        val now = android.os.SystemClock.elapsedRealtime()
        if (!persist || (next.phase == SetupPhase.DOWNLOADING && now - lastSaved < 1000)) return
        lastSaved = now
        val image = next.image ?: run { File(directory(context), "state.json").delete(); return }
        val json = JSONObject().put("phase", next.phase.name).put("image", InitialImageCatalog.encode(listOf(image)))
            .put("downloaded", next.downloaded).put("message", next.message)
        val target = File(directory(context), "state.json")
        val temp = File(target.parentFile, "state.json.tmp")
        check(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(temp.toPath()))
        FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
