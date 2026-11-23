// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URI

/** Initial-install choices only. This catalog never schedules an existing guest update. */
object InitialImageCatalog {
    const val MAX_BYTES = 1024 * 1024
    const val CATALOG_URL = "https://github.com/Audrey-cn/omarchy-android/releases/download/desktop-images/catalog.json"
    private val identifier = Regex("[a-z0-9][a-z0-9._-]{0,79}")
    fun parse(text: String): List<ImageSpec> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Image catalog is too large" }
        require(text.trimStart().startsWith("{")) { "Expected an image catalog" }
        try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                codePointLimit = MAX_BYTES
                nestingDepthLimit = 12
            }
            val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid image catalog")
            require(integer(root, "schemaVersion") == 1L) { "Unsupported image catalog" }
            val entries = root["images"] as? List<*> ?: throw IllegalArgumentException("Missing images")
            require(entries.size <= 32) { "Too many initial images" }
            val images = entries.map { value ->
                val m = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid image entry")
                val urls = (m["urls"] as? List<*>)?.map {
                    it as? String ?: throw IllegalArgumentException("Invalid download address")
                } ?: throw IllegalArgumentException("Missing download addresses")
                ImageSpec(string(m, "id"), string(m, "name"), string(m, "abi"),
                    integer(m, "minAppVersionCode").also { require(it in 2..2_100_000_000L) }.toInt(),
                    integer(m, "bytes"), integer(m, "unpackedBytes"), string(m, "sha256"), string(m, "sourceSha"), urls)
                    .also(::validate)
            }
            require(images.map { it.id }.toSet().size == images.size) { "Duplicate image version" }
            return images
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: Exception) { throw IllegalArgumentException("Invalid image catalog") }
    }
    fun validate(spec: ImageSpec) {
        require(identifier.matches(spec.id)) { "Invalid image version" }
        require(spec.name.length in 1..160 && spec.name.none { it.isISOControl() }) { "Invalid image name" }
        require(spec.abi.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "Invalid image architecture" }
        require(spec.minAppVersionCode in 2..2_100_000_000)
        require(spec.bytes in 1..16L * 1024 * 1024 * 1024 && spec.unpackedBytes in 1..64L * 1024 * 1024 * 1024) { "Invalid image size" }
        require(spec.sha256.matches(Regex("[0-9a-f]{64}")) && spec.sourceSha.matches(Regex("[0-9a-f]{40}"))) { "Invalid image identity" }
        require(spec.urls.size in 1..4)
        spec.urls.forEach { value ->
            require(value.length <= 4096)
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Image downloads require HTTPS" }
        }
    }
    fun compatible(spec: ImageSpec, appVersionCode: Int) = spec.abi == "arm64-v8a" && spec.minAppVersionCode <= appVersionCode
    fun encode(images: List<ImageSpec>): String = images.joinToString(",", "{\"schemaVersion\":1,\"images\":[", "]}") { image ->
        validate(image)
        "{\"id\":${quote(image.id)},\"name\":${quote(image.name)},\"abi\":${quote(image.abi)},\"minAppVersionCode\":${image.minAppVersionCode},\"bytes\":${image.bytes},\"unpackedBytes\":${image.unpackedBytes},\"sha256\":${quote(image.sha256)},\"sourceSha\":${quote(image.sourceSha)},\"urls\":[${image.urls.joinToString(",") { quote(it) }}]}"
    }
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun string(map: Map<*, *>, key: String) = map[key] as? String ?: throw IllegalArgumentException("Missing $key")
    private fun integer(map: Map<*, *>, key: String): Long {
        val value = map[key]
        require(value is Int || value is Long) { "Invalid integer $key" }
        return (value as Number).toLong()
    }
}
