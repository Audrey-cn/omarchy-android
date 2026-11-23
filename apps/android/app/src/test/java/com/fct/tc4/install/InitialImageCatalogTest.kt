// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.junit.Assert.*
import org.junit.Test

class InitialImageCatalogTest {
    private val entry = """{"id":"ubuntu24-test","name":"Ubuntu desktop","abi":"arm64-v8a","minAppVersionCode":64,"bytes":100,"unpackedBytes":1000,"sha256":"${"a".repeat(64)}","sourceSha":"${"b".repeat(40)}","urls":["https://example.com/image.tar.zst"]}"""
    @Test fun catalogRoundTripAndCompatibleVersion() {
        val list = InitialImageCatalog.parse("""{"schemaVersion":1,"images":[$entry]}""")
        assertEquals(1, list.size); assertTrue(InitialImageCatalog.compatible(list[0], 64))
        assertFalse(InitialImageCatalog.compatible(list[0], 63))
        assertEquals(list, InitialImageCatalog.parse(InitialImageCatalog.encode(list)))
    }
    @Test fun duplicateKeysInvalidSourcesAndOversizedCatalogAreRejected() {
        for (invalid in listOf(
            """{"schemaVersion":1,"schemaVersion":1,"images":[$entry]}""",
            """{"schemaVersion":1,"images":[$entry,$entry]}""",
            """{"schemaVersion":2,"images":[$entry]}""",
            """{"schemaVersion":1,"images":[${entry.replace("https://", "http://")}]}""",
            """{"schemaVersion":1,"images":[${entry.replace("\"bytes\":100", "\"bytes\":1.5")}]}""",
            " ".repeat(InitialImageCatalog.MAX_BYTES + 1)
        )) assertThrows(IllegalArgumentException::class.java) { InitialImageCatalog.parse(invalid) }
    }
    @Test fun noSilentAbiOrPathCoercion() {
        assertThrows(IllegalArgumentException::class.java) {
            InitialImageCatalog.parse("""{"schemaVersion":1,"images":[${entry.replace("ubuntu24-test", "../omarchy")}]}""")
        }
        val list = InitialImageCatalog.parse("""{"schemaVersion":1,"images":[${entry.replace("arm64-v8a", "x86_64")}]}""")
        assertFalse(InitialImageCatalog.compatible(list[0], 100))
    }
    @Test fun officialReleaseResolvesCatalogByNameInsteadOfAssetOrderOrFixedId() {
        val release = """{"tag_name":"desktop-images","assets":[{"name":"image.tar.zst","size":10000000000,"url":"https://example.com/image"},{"name":"catalog.json","size":1048576,"url":"https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789"}]}"""
        assertEquals("https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789",
            InitialImageCatalog.catalogAssetUrl(release))
        assertEquals("https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/987654321",
            InitialImageCatalog.catalogAssetUrl(release.replace("123456789", "987654321")))
    }
    @Test fun officialReleaseRejectsWrongRepositoriesAndNonCanonicalAssetUrls() {
        for (url in listOf(
            "https://api.github.com/repos/other/omarchy-android/releases/assets/123456789",
            "https://api.github.com/repos/Audrey-cn/other/releases/assets/123456789",
            "https://api.github.com.evil.example/repos/Audrey-cn/omarchy-android/releases/assets/123456789",
            "http://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789",
            "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789?raw=1",
            "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/../123456789",
            "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/0",
            "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/00123456789"
        )) assertThrows(IllegalArgumentException::class.java) {
            InitialImageCatalog.catalogAssetUrl(officialRelease(catalogAsset(url = url)))
        }
    }
    @Test fun officialReleaseRejectsMissingOrAmbiguousCatalogs() {
        for (assets in listOf("", """{"name":"other.json","size":1,"url":"https://example.com/other"}""",
            "${catalogAsset()},${catalogAsset()}", "${catalogAsset()},${catalogAsset(size = 0)}")) {
            assertThrows(IllegalArgumentException::class.java) {
                InitialImageCatalog.catalogAssetUrl(officialRelease(assets))
            }
        }
    }
    @Test fun officialReleaseEnforcesCatalogSizeAndMetadataBounds() {
        assertEquals("https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789",
            InitialImageCatalog.catalogAssetUrl(officialRelease(catalogAsset(size = 1))))
        for (invalid in listOf(
            officialRelease(catalogAsset(size = 0)),
            officialRelease(catalogAsset(size = -1)),
            officialRelease(catalogAsset(size = InitialImageCatalog.MAX_BYTES + 1)),
            officialRelease(catalogAsset()).replace("\"size\":100", "\"size\":1.5"),
            officialRelease(catalogAsset()) + " ".repeat(InitialImageCatalog.MAX_BYTES)
        )) assertThrows(IllegalArgumentException::class.java) { InitialImageCatalog.catalogAssetUrl(invalid) }
    }
    @Test fun officialReleaseRejectsWrongTagAndDuplicateKeys() {
        for (invalid in listOf(
            officialRelease(catalogAsset()).replace("desktop-images", "other-tag"),
            officialRelease(catalogAsset()).replace("\"assets\":", "\"tag_name\":\"desktop-images\",\"assets\":"),
            officialRelease(catalogAsset()).replace("\"size\":100", "\"size\":100,\"size\":100")
        )) assertThrows(IllegalArgumentException::class.java) { InitialImageCatalog.catalogAssetUrl(invalid) }
    }
    private fun officialRelease(assets: String) = """{"tag_name":"desktop-images","assets":[$assets]}"""
    private fun catalogAsset(size: Int = 100,
        url: String = "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/123456789") =
        """{"name":"catalog.json","size":$size,"url":"$url"}"""
}
