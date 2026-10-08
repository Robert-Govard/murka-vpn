package org.olcbox.app.vpn.failover

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEndpointConfig
import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailoverAdvisorTest {
    private val sub = "https://sub.test/abc"
    private fun xray(id: String) = LocationEntry(
        storageId = id, name = id, subscriptionUrl = sub,
        kind = LocationConfig.KIND_XRAY, xrayConfig = """{"outbounds":[{"protocol":"vless"}]}"""
    )
    private fun room(id: String, url: String? = sub) = LocationEntry(
        storageId = id, name = id, subscriptionUrl = url, authProvider = "wbstream",
        endpoint = LocationEndpointConfig(roomId = "room-$id", key = "a".repeat(64))
    )
    private val nl = xray("nl")
    private val fi = xray("fi")
    private val dk1 = room("dk1")
    private val all = listOf(nl, fi, dk1)
    private val min = 60_000L

    @Test
    fun threeFailuresSuggestEmergency() {
        val a = FailoverAdvisor()
        a.onConnected(fi, all, 0)
        assertNull(a.onTunnelProbe(false, hasNetwork = true, nowMs = 30_000))
        assertNull(a.onTunnelProbe(false, hasNetwork = true, nowMs = 60_000))
        assertEquals(FailoverHint.UseEmergency("dk1"), a.onTunnelProbe(false, hasNetwork = true, nowMs = 90_000))
    }

    @Test
    fun successResetsFailures() {
        val a = FailoverAdvisor()
        a.onConnected(nl, all, 0)
        a.onTunnelProbe(false, true, 1)
        a.onTunnelProbe(false, true, 2)
        assertNull(a.onTunnelProbe(true, true, 3))
        assertNull(a.onTunnelProbe(false, true, 4))
        assertNull(a.onTunnelProbe(false, true, 5))
    }

    @Test
    fun noHintWithoutNetworkOrEmergencyRooms() {
        val offline = FailoverAdvisor().apply { onConnected(nl, all, 0) }
        repeat(5) { assertNull(offline.onTunnelProbe(false, hasNetwork = false, nowMs = it.toLong())) }

        val other = FailoverAdvisor().apply { onConnected(nl, listOf(nl, fi, room("x", url = "https://other.test")), 0) }
        repeat(5) { assertNull(other.onTunnelProbe(false, true, it.toLong())) }
    }

    @Test
    fun hintsAreThrottled() {
        val a = FailoverAdvisor()
        a.onConnected(nl, all, 0)
        repeat(3) { a.onTunnelProbe(false, true, it.toLong()) }
        repeat(6) { assertNull(a.onTunnelProbe(false, true, 10 * min + it)) }
        assertEquals(FailoverHint.UseEmergency("dk1"), a.onTunnelProbe(false, true, 31 * min))
    }

    @Test
    fun emergencyProbesLastRegularEveryFiveMinutes() {
        val a = FailoverAdvisor()
        a.onConnected(fi, all, 0)
        a.onConnected(dk1, all, 1 * min)
        assertNull(a.onTunnelProbe(false, true, 2 * min))
        assertEquals("fi", a.regularToProbe(1 * min)?.storageId)
        assertNull(a.onRegularProbe(false, 1 * min))
        assertNull(a.regularToProbe(3 * min))
        assertEquals("fi", a.regularToProbe(6 * min + 1)?.storageId)
        assertEquals(FailoverHint.ReturnToRegular("fi"), a.onRegularProbe(true, 6 * min + 1))
        assertNull(a.regularToProbe(12 * min)?.let { a.onRegularProbe(true, 12 * min) })
    }

    @Test
    fun emergencyWithoutHistoryUsesFirstRegular() {
        val a = FailoverAdvisor()
        a.onConnected(dk1, all, 0)
        assertEquals("nl", a.regularToProbe(0)?.storageId)
        assertNull(FailoverAdvisor().apply { onConnected(nl, all, 0) }.regularToProbe(10 * min))
    }
}
