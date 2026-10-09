package org.olcbox.app.telemetry

import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.desktop.DesktopPaths

/** Telemetry state next to the other desktop app data. */
fun desktopTelemetryStore(): TelemetryStore = FileTelemetryStore(DesktopPaths.appDataDir().toFile())

fun installDesktopCrashRecorder() = CrashRecorder.install(DesktopPaths.appDataDir().toFile(), CurrentAppInfo.value.version)
