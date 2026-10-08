package org.olcbox.app.vpn.failover

import org.olcbox.app.data.model.LocationEntry

/** What to suggest to the user; the app never switches by itself. */
sealed interface FailoverHint {
    /** Regular VPN stopped passing traffic: offer this emergency room. */
    data class UseEmergency(val storageId: String) : FailoverHint

    /** Emergency mode is on but this regular server answers again. */
    data class ReturnToRegular(val storageId: String) : FailoverHint
}

/**
 * Decides when to offer emergency mode and when to offer going back.
 * Regular servers are Xray locations; emergency rooms are olcRTC locations of
 * the same subscription. Pure logic: callers run the probes and pass the time.
 */
class FailoverAdvisor(
    private val failuresBeforeHint: Int = 3,
    private val hintCooldownMs: Long = 30 * MINUTE_MS,
    private val regularProbeIntervalMs: Long = 5 * MINUTE_MS
) {
    private var active: LocationEntry? = null
    private var emergencyRooms: List<LocationEntry> = emptyList()
    private var regularServers: List<LocationEntry> = emptyList()
    private var lastRegular: LocationEntry? = null
    private var failures = 0
    private var lastEmergencyHintAt: Long? = null
    private var lastReturnHintAt: Long? = null
    private var lastRegularProbeAt: Long? = null

    private val onRegular get() = active?.location?.isXray == true
    private val onEmergency get() = active != null && !onRegular && regularServers.isNotEmpty()

    fun onConnected(active: LocationEntry, locations: List<LocationEntry>, nowMs: Long) {
        val subscription = active.subscriptionUrl?.trim()
        val sameSubscription = locations.filter { subscription != null && it.subscriptionUrl?.trim() == subscription }
        this.active = active
        regularServers = sameSubscription.filter { it.location.isXray }
        emergencyRooms = sameSubscription.filter { !it.location.isXray }
        failures = 0
        lastRegularProbeAt = null
        if (active.location.isXray) {
            lastRegular = active
        } else if (lastRegular?.storageId !in regularServers.map { it.storageId }) {
            lastRegular = regularServers.firstOrNull()
        }
    }

    fun onTunnelProbe(ok: Boolean, hasNetwork: Boolean, nowMs: Long): FailoverHint? {
        if (!onRegular) return null
        if (ok) {
            failures = 0
            return null
        }
        if (!hasNetwork) return null
        failures++
        val room = emergencyRooms.firstOrNull() ?: return null
        if (failures < failuresBeforeHint || !cooledDown(lastEmergencyHintAt, nowMs)) return null
        lastEmergencyHintAt = nowMs
        return FailoverHint.UseEmergency(room.storageId)
    }

    /** The regular server to check outside the tunnel now, or null if it is not time yet. */
    fun regularToProbe(nowMs: Long): LocationEntry? {
        if (!onEmergency) return null
        val last = lastRegularProbeAt
        if (last != null && nowMs - last < regularProbeIntervalMs) return null
        return lastRegular
    }

    fun onRegularProbe(ok: Boolean, nowMs: Long): FailoverHint? {
        lastRegularProbeAt = nowMs
        val server = lastRegular ?: return null
        if (!ok || !onEmergency || !cooledDown(lastReturnHintAt, nowMs)) return null
        lastReturnHintAt = nowMs
        return FailoverHint.ReturnToRegular(server.storageId)
    }

    private fun cooledDown(lastAt: Long?, nowMs: Long) = lastAt == null || nowMs - lastAt >= hintCooldownMs

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
