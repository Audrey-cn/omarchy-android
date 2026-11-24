// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadMirrorStoreTest {
    private val release = "https://github.com/Audrey-cn/omarchy-android/releases/download/image-v1/ubuntu.tar.zst"

    @Test
    fun defaultRouteIsDirectAndLeavesUrlsUnchanged() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences())
        val urls = listOf("https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/1", release)
        assertEquals(DownloadMirrorStore.Mode.DIRECT, store.mode)
        assertEquals(urls, store.expandUrls(urls))
    }

    @Test
    fun acceleratorPersistsAndPrependsOnlyGithubReleaseDownloads() {
        val preferences = inMemoryMirrorPreferences()
        DownloadMirrorStore(preferences).set(DownloadMirrorStore.Mode.ACCEL)
        val store = DownloadMirrorStore(preferences)
        val api = "https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/1"
        val foreign = "https://example.org/image.tar.zst"
        val repo = "https://github.com/Audrey-cn/omarchy-android"
        assertEquals(DownloadMirrorStore.Mode.ACCEL, store.mode)
        assertEquals(listOf("https://gh-proxy.com/$release", "https://ghfast.top/$release", api, foreign, repo, release),
            store.expandUrls(listOf(api, foreign, repo, release)))
    }

    @Test
    fun customPrefixPersistsNormalizesAndAlwaysKeepsOfficialFallback() {
        val preferences = inMemoryMirrorPreferences()
        DownloadMirrorStore(preferences).set(DownloadMirrorStore.Mode.CUSTOM, "https://mirror.example.org/github")
        val store = DownloadMirrorStore(preferences)
        assertEquals("https://mirror.example.org/github/", store.customPrefix)
        assertEquals(listOf("https://mirror.example.org/github/$release", release), store.expandUrls(listOf(release)))
        store.set(DownloadMirrorStore.Mode.DIRECT)
        assertEquals(listOf(release), store.expandUrls(listOf(release)))
        assertEquals("https://mirror.example.org/github/", store.customPrefix)
    }

    @Test
    fun invalidCustomPrefixesCannotChangeTheSavedRoute() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences())
        store.set(DownloadMirrorStore.Mode.ACCEL)
        for (invalid in listOf("", "http://mirror.example.org/", "https://", "https://user:pass@mirror.example.org/",
            "https://mirror.example.org/?token=1", "https://mirror.example.org/#fragment", "https://mirror.example.org:0/")) {
            assertThrows(IllegalArgumentException::class.java) { store.set(DownloadMirrorStore.Mode.CUSTOM, invalid) }
            assertEquals(DownloadMirrorStore.Mode.ACCEL, store.mode)
        }
    }

    @Test
    fun lookalikeOrInsecureGithubUrlsAreNotAccelerated() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply { set(DownloadMirrorStore.Mode.ACCEL) }
        val urls = listOf(release.replace("github.com", "github.com.example.org"),
            release.replace("https://", "http://"), release.replace("github.com", "user@github.com"),
            release.replace("/releases/download/", "/releases/tag/"))
        assertEquals(urls, store.expandUrls(urls))
    }

    @Test
    fun multipleAssetsKeepAllOriginalUrlsAfterMirrorCandidates() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply { set(DownloadMirrorStore.Mode.ACCEL) }
        val other = release.replace("ubuntu.tar.zst", "ubuntu-minimal.tar.zst")
        val routes = store.expandUrls(listOf(release, other))
        assertEquals(listOf(release, other), routes.takeLast(2))
        assertEquals(6, routes.size)
    }

    @Test
    fun invalidPersistedSelectionSafelyReturnsToDirect() {
        val preferences = inMemoryMirrorPreferences()
        preferences.edit().putString("mode", "unknown").commit()
        val store = DownloadMirrorStore(preferences)
        assertEquals(DownloadMirrorStore.Mode.DIRECT, store.mode)
        preferences.edit().putString("mode", "CUSTOM").putString("custom_prefix", "http://insecure.example/").commit()
        assertEquals(DownloadMirrorStore.Mode.DIRECT, store.mode)
        assertEquals(listOf(release), store.expandUrls(listOf(release)))
    }
}

/** Exercise the Android interface without requiring an emulator or framework method stubs. */
internal fun inMemoryMirrorPreferences(): SharedPreferences {
    val data = mutableMapOf<String, String>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString" -> { data[args!![0] as String] = args[1] as String; proxy }
            "commit" -> true
            "apply" -> null
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SharedPreferences.Editor
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> data[args!![0]] ?: args[1]
            "edit" -> editor
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SharedPreferences
}
