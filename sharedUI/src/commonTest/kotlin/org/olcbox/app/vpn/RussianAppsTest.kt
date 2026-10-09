package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RussianAppsTest {
    @Test
    fun firstRunBypassesRussianApps() {
        val (mode, bypass) = RussianApps.resolveSplitTunnel(storedMode = null, storedBypass = null)
        assertEquals(RussianApps.BYPASS_MODE, mode)
        assertTrue("ru.sberbankmobile" in bypass && "ru.rostel" in bypass && "com.wildberries.ru" in bypass)
    }

    @Test
    fun userChoiceIsKept() {
        assertEquals("all_apps" to emptySet(), RussianApps.resolveSplitTunnel("all_apps", null))
        assertEquals("bypass_selected" to setOf("a.b"), RussianApps.resolveSplitTunnel("bypass_selected", setOf("a.b")))
    }

    @Test
    fun listHasNoDuplicatesOrBlanks() {
        assertEquals(RussianApps.packages.size, RussianApps.packages.toSet().size)
        assertTrue(RussianApps.packages.all { Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$").matches(it) })
    }
}
