// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

import org.junit.Assert.*
import org.junit.Test

class ProotRuntimePolicyTest {
    private fun selected(code: String, command: String?, legacy: Boolean = true) =
        ProotRuntimePolicy.select(code, command, legacy)

    @Test fun omarchyGenericCommandsSelectMatchedReadonlyRuntime() {
        for (command in listOf("\$BIN_DIR/proot", "\${BIN_DIR}/proot", "proot")) {
            val spellings = listOf(command, "\"$command\"") + if (command == "proot") listOf("'proot'") else emptyList()
            for (quoted in spellings) {
                for (legacy in listOf(true, false)) {
                    val runtime = selected("omarchy", "  $quoted -0 --rootfs=guest", legacy)
                    assertEquals("omarchy-proot-readonly-v1", runtime.id)
                    assertEquals("libproot-omarchy-readonly.so", runtime.executableLibrary)
                    assertEquals("libproot-omarchy-loader-aarch64.so", runtime.loaderLibrary)
                    assertEquals("libproot-omarchy-loader-arm.so", runtime.loader32Library)
                }
            }
        }
    }

    @Test fun otherContainersKeepGlobalClassicOrLatestSelection() {
        for (code in listOf("ubuntu", "debian", "omarchy-backup", "Omarchy")) {
            assertEquals("lib__bin__proot-classic__.so", selected(code, "\$BIN_DIR/proot -0").executableLibrary)
            assertEquals("lib__bin__proot-latest__.so", selected(code, "\$BIN_DIR/proot -0", false).executableLibrary)
            assertEquals("libproot-loader-aarch64-5.1.107-68.so", selected(code, "\$BIN_DIR/proot -0").loaderLibrary)
            assertEquals("libproot-loader32-aarch64-5.1.107-68.so", selected(code, "\$BIN_DIR/proot -0", false).loader32Library)
        }
    }

    @Test fun explicitOrCustomOmarchyCommandsKeepExistingSelectionAndLoaders() {
        for (command in listOf("\$BIN_DIR/proot-classic -0", "\$BIN_DIR/proot-latest -0",
                "proot-classic -0", "\$BIN_DIR/proot-custom -0", "\$BIN_DIR/prootXYZ -0",
                "/private/custom/proot -0", "env SPECIAL=1 \$BIN_DIR/proot -0", "'\$BIN_DIR/proot' -0")) {
            val runtime = selected("omarchy", command)
            assertEquals("lib__bin__proot-classic__.so", runtime.executableLibrary)
            assertEquals("libproot-loader-aarch64-5.1.107-68.so", runtime.loaderLibrary)
            assertEquals("libproot-loader32-aarch64-5.1.107-68.so", runtime.loader32Library)
            assertEquals("lib__bin__proot-latest__.so", selected("omarchy", command, false).executableLibrary)
        }
    }

    @Test fun missingBootCommandDoesNotOptIntoNewRuntime() {
        for (command in listOf(null, "", "   ")) {
            assertEquals("lib__bin__proot-classic__.so", selected("omarchy", command).executableLibrary)
        }
    }
}
