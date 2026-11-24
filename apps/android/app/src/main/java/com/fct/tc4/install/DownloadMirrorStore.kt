// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/** Download routes affect binary image transfers only; catalog identity and checksums stay authoritative. */
class DownloadMirrorStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("download-mirror", Context.MODE_PRIVATE))

    enum class Mode { DIRECT, ACCEL, CUSTOM }

    val mode: Mode
        get() {
            val selected = runCatching { Mode.valueOf(preferences.getString("mode", Mode.DIRECT.name).orEmpty()) }
                .getOrDefault(Mode.DIRECT)
            return if (selected == Mode.CUSTOM && customPrefix.isEmpty()) Mode.DIRECT else selected
        }

    val customPrefix: String
        get() = runCatching { normalizePrefix(preferences.getString("custom_prefix", "").orEmpty()) }.getOrDefault("")

    fun set(mode: Mode, customPrefix: String = this.customPrefix) {
        val prefix = if (mode == Mode.CUSTOM || customPrefix.isNotBlank()) normalizePrefix(customPrefix) else ""
        check(preferences.edit().putString("mode", mode.name).putString("custom_prefix", prefix).commit()) {
            "Download route could not be saved"
        }
    }

    fun expandUrls(urls: List<String>): List<String> {
        val prefixes = when (mode) {
            Mode.DIRECT -> return urls.toList()
            Mode.ACCEL -> listOf("https://gh-proxy.com/", "https://ghfast.top/")
            Mode.CUSTOM -> listOf(customPrefix)
        }
        val releases = urls.filter(::isGithubRelease)
        return (prefixes.flatMap { prefix -> releases.map { prefix + it } } + urls).distinct()
    }

    private fun isGithubRelease(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in listOf(-1, 443) &&
            Regex("/[^/]+/[^/]+/releases/download/[^/]+/.+").matches(uri.rawPath.orEmpty())
    }.getOrDefault(false)

    private fun normalizePrefix(value: String): String {
        val prefix = value.trim()
        val uri = runCatching { URI(prefix) }.getOrElse { throw IllegalArgumentException("Mirror prefix must use HTTPS", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Mirror prefix must use HTTPS without credentials, query or fragment" }
        return prefix.trimEnd('/') + "/"
    }
}
