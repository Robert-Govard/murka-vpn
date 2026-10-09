package org.olcbox.app.telemetry

import platform.Foundation.NSUserDefaults

/** iOS keeps telemetry state in user defaults; crashes are not captured on iOS. */
class IosTelemetryStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : TelemetryStore {
    override var enabled: Boolean
        get() = defaults.objectForKey(KEY_ENABLED) == null || defaults.boolForKey(KEY_ENABLED)
        set(value) = defaults.setBool(value, KEY_ENABLED)
    override var lastHelloAt: Long
        get() = defaults.doubleForKey(KEY_HELLO).toLong()
        set(value) = defaults.setDouble(value.toDouble(), KEY_HELLO)
    override var lastConnectReportAt: Long
        get() = defaults.doubleForKey(KEY_CONNECT).toLong()
        set(value) = defaults.setDouble(value.toDouble(), KEY_CONNECT)

    override fun pendingCrash(): String? = null
    override fun clearPendingCrash() = Unit

    private companion object {
        const val KEY_ENABLED = "murka.telemetry.enabled"
        const val KEY_HELLO = "murka.telemetry.lastHello"
        const val KEY_CONNECT = "murka.telemetry.lastConnectReport"
    }
}
