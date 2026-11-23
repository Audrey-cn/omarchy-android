// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

import org.junit.Assert.*
import org.junit.Test

class OmarchyRuntimeIdentityTest {
    @Test fun identifiesOnlyOmarchyAndCannotInjectShellTokens() {
        assertTrue(OmarchyRuntimeIdentity.environment("other", 1, "1.0").isEmpty())
        val environment = OmarchyRuntimeIdentity.environment("omarchy", 80, "1.0.80-test")
        assertTrue(environment.contains("OMARCHY_APK_VERSION_CODE=80"))
        assertTrue(environment.contains("OMARCHY_RUNTIME_API=1"))
        assertTrue(environment.contains("OMARCHY_RUNTIME_KIND=proot-app"))
        assertThrows(IllegalArgumentException::class.java) { OmarchyRuntimeIdentity.environment("omarchy", 80, "bad;touch /tmp/x") }
    }
}
