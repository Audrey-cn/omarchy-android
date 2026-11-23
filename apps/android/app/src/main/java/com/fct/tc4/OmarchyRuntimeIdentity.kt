// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

/** Capabilities passed to an explicitly launched session, without modifying guest files. */
object OmarchyRuntimeIdentity {
    fun environment(code: String, versionCode: Int, versionName: String): List<String> {
        if (code != "omarchy") return emptyList()
        require(versionCode > 0 && versionName.matches(Regex("[A-Za-z0-9._+\\-]{1,80}")))
        return listOf("OMARCHY_APK_VERSION_CODE=$versionCode", "OMARCHY_APK_VERSION_NAME=$versionName",
            "OMARCHY_RUNTIME_API=1", "OMARCHY_RUNTIME_KIND=proot-app")
    }
}
