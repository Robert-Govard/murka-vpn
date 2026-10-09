package org.olcbox.app.telemetry

import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.update.UpdatePlatform
import org.olcbox.app.vpn.VpnManager

/** Telemetry wired to the app's own repositories; [osVersion] comes from the platform. */
fun createTelemetry(
    store: TelemetryStore,
    identity: DeviceIdentityProvider,
    locationsRepository: LocationsRepository,
    vpnManager: VpnManager,
    osVersion: String,
): Telemetry = Telemetry(
    api = TelemetryApi(createProxyHttpClient(requestTimeoutMs = 15_000, socketTimeoutMs = 15_000)),
    store = store,
    identity = identity,
    subscriptionUrls = { locationsRepository.getBundle().locations.mapNotNull { it.subscriptionUrl } },
    platform = UpdatePlatform.current().os,
    osVersion = osVersion,
    appVersion = CurrentAppInfo.value.version,
    logs = { vpnManager.logs.value },
)
