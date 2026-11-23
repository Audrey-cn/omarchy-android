// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

data class ProotRuntimeSelection(
    val id: String,
    val executableLibrary: String,
    val loaderLibrary: String,
    val loader32Library: String
)

/** Select a matched executable/loader set without rewriting custom boot commands. */
object ProotRuntimePolicy {
    private val readonly = ProotRuntimeSelection(
        "omarchy-proot-readonly-v1",
        "libproot-omarchy-readonly.so",
        "libproot-omarchy-loader-aarch64.so",
        "libproot-omarchy-loader-arm.so"
    )
    private val genericCommands = listOf("\$BIN_DIR/proot", "\${BIN_DIR}/proot", "proot")
        .flatMap { listOf(it, "\"$it\"") } + "'proot'"

    fun select(code: String, bootCommand: String?, useLegacy: Boolean): ProotRuntimeSelection {
        val command = bootCommand?.trimStart().orEmpty()
        val usesGenericProot = genericCommands.any { executable ->
            command.startsWith(executable) &&
                (command.length == executable.length || command[executable.length].isWhitespace())
        }
        if (code == "omarchy" && usesGenericProot) return readonly
        return ProotRuntimeSelection(
            if (useLegacy) "tiny-classic" else "tiny-latest",
            if (useLegacy) "lib__bin__proot-classic__.so" else "lib__bin__proot-latest__.so",
            "libproot-loader-aarch64-5.1.107-68.so",
            "libproot-loader32-aarch64-5.1.107-68.so"
        )
    }
}
