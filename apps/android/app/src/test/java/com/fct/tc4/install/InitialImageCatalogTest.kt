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
}
