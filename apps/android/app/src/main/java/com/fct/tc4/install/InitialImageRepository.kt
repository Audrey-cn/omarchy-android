// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object InitialImageRepository {
    fun available(context: Context): List<ImageSpec> {
        val cache = File(InitialDesktopState.directory(context), "catalog.json")
        if (cache.isFile && !Files.isSymbolicLink(cache.toPath()) && cache.length() <= InitialImageCatalog.MAX_BYTES) {
            try { return InitialImageCatalog.parse(cache.readText()) } catch (_: Exception) { /* bundled fallback */ }
        }
        return context.assets.open("initial-images.json").bufferedReader().use { InitialImageCatalog.parse(it.readText()) }
    }
    fun refresh(context: Context): List<ImageSpec> {
        val (bytes, catalog) = try {
            val release = fetch(InitialImageCatalog.RELEASE_API_URL, "application/vnd.github+json")
            val apiAssetUrl = InitialImageCatalog.catalogAssetUrl(release.toString(Charsets.UTF_8))
            val downloaded = fetch(apiAssetUrl, "application/octet-stream")
            downloaded to InitialImageCatalog.parse(downloaded.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            val downloaded = fetch(InitialImageCatalog.CATALOG_URL, "application/json")
            downloaded to InitialImageCatalog.parse(downloaded.toString(Charsets.UTF_8))
        }
        val target = File(InitialDesktopState.directory(context), "catalog.json")
        val temp = File(target.parentFile, "catalog.json.tmp")
        check(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(temp.toPath()))
        temp.writeBytes(bytes); Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        return catalog
    }
    private fun fetch(address: String, accept: String): ByteArray {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("Missing catalog address"))
                } else {
                    if (status != 200) throw IOException("Catalog unavailable")
                    return connection.inputStream.use { it.readBytesBounded(InitialImageCatalog.MAX_BYTES) }
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many catalog redirects")
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer); if (n < 0) break
            if (output.size() + n > limit) throw IOException("Catalog too large")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
