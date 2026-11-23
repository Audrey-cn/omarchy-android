package com.fct.tc4.ui.misc

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SwayOnboardingIpc {
    const val GET_WORKSPACES = 1
    const val GET_OUTPUTS = 3
    const val WAYLAND_SOCKET_NAME = "wayland-1"
    private const val GUEST_RUNTIME_DIR = "/tmp"
    private val socketName = Regex("""sway-ipc\.\d+\.(\d+)\.sock""")
    private val whitespace = Regex("""\s+""")
    private val magic = "i3-ipc".toByteArray(Charsets.US_ASCII)
    private const val headerSize = 14
    private const val maxReplyBytes = 1024 * 1024
    private const val maxEnvironmentBytes = 128 * 1024

    fun candidatePid(name: String, x11CreatedAt: Long, swayCreatedAt: Long): Int? {
        if (swayCreatedAt < x11CreatedAt) return null
        return socketName.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun matchesPeer(expectedPid: Int, expectedUid: Int, peerPid: Int, peerUid: Int): Boolean =
        expectedPid == peerPid && expectedUid == peerUid

    fun request(input: InputStream, output: OutputStream, type: Int, beforeIo: () -> Unit = {}): String {
        val request = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        request.put(magic).putInt(0).putInt(type)
        beforeIo()
        output.write(request.array())

        fun readFully(buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                beforeIo()
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count <= 0) throw EOFException("Truncated Sway IPC reply")
                offset += count
            }
        }

        val header = ByteArray(headerSize)
        readFully(header)
        val fields = ByteBuffer.wrap(header, magic.size, 8).order(ByteOrder.LITTLE_ENDIAN)
        val size = fields.int
        val replyType = fields.int
        if (!header.copyOfRange(0, magic.size).contentEquals(magic) ||
            size !in 1..maxReplyBytes || replyType != type
        ) {
            throw IOException("Invalid Sway IPC reply")
        }
        val payload = ByteArray(size)
        readFully(payload)
        return payload.toString(Charsets.UTF_8)
    }

    fun matchesSwayEnvironment(input: InputStream): Boolean =
        environmentMatches(input, mapOf("DISPLAY" to ":0", "XDG_RUNTIME_DIR" to GUEST_RUNTIME_DIR))

    fun matchesWaybarEnvironment(input: InputStream, swaySocketName: String): Boolean = environmentMatches(
        input,
        mapOf(
            "SWAYSOCK" to "$GUEST_RUNTIME_DIR/$swaySocketName",
            "WAYLAND_DISPLAY" to WAYLAND_SOCKET_NAME,
            "XDG_RUNTIME_DIR" to GUEST_RUNTIME_DIR
        )
    )

    private fun environmentMatches(input: InputStream, expected: Map<String, String>): Boolean {
        val stream = input.buffered()
        val fields = expected.entries.toList()
        val keys = fields.map { it.key.toByteArray() }
        val values = fields.map { it.value.toByteArray() }
        val seen = BooleanArray(keys.size)
        val possible = BooleanArray(keys.size) { true }
        var target = -1
        var offset = 0
        var inValue = false

        // Compare only allowlisted fields. Unknown values are streamed past without
        // decoding them into strings or returning them to the caller.
        repeat(maxEnvironmentBytes) {
            val byte = stream.read()
            when {
                byte == -1 -> return !inValue && offset == 0 && seen.all { it }
                byte == 0 -> {
                    if (target >= 0) {
                        if (seen[target] || offset != values[target].size) return false
                        seen[target] = true
                    }
                    possible.fill(true)
                    target = -1
                    offset = 0
                    inValue = false
                }
                !inValue && byte == '='.code -> {
                    target = keys.indices.firstOrNull { possible[it] && offset == keys[it].size } ?: -1
                    offset = 0
                    inValue = true
                }
                !inValue -> {
                    for (i in keys.indices) {
                        possible[i] = possible[i] && offset < keys[i].size && byte == keys[i][offset].toUByte().toInt()
                    }
                    offset++
                }
                target >= 0 -> {
                    if (offset >= values[target].size || byte != values[target][offset].toUByte().toInt()) return false
                    offset++
                }
            }
        }
        return false
    }

    fun topReservation(outputY: Int, outputHeight: Int, workspaceY: Int, workspaceHeight: Int): Int? {
        if (outputHeight <= 0 || workspaceHeight <= 0) return null
        val topInset = workspaceY.toLong() - outputY
        val bottomInset = outputHeight - topInset - workspaceHeight
        // The shipped Sway config has symmetric outer gaps. Waybar adds a top reservation.
        if (topInset <= bottomInset || bottomInset < 0) return null
        return (topInset - bottomInset).toInt()
    }

    fun liveProcessStart(stat: String, name: String): Long? {
        val nameStart = stat.indexOf('(')
        val nameEnd = stat.lastIndexOf(')')
        if (nameStart < 0 || nameEnd <= nameStart || stat.substring(nameStart + 1, nameEnd) != name) return null
        // Linux /proc/<pid>/stat: state is field 3, starttime is field 22.
        val fields = stat.substring(nameEnd + 1).trim().split(whitespace, limit = 21)
        if (fields.firstOrNull() in listOf("Z", "X", "x")) return null
        return fields.getOrNull(19)?.toLongOrNull()
    }
}
