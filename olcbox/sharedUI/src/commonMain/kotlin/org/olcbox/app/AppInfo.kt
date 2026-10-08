package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String,
    val olcrtcSha: String
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = GeneratedAppInfo.NAME,
        version = GeneratedAppInfo.VERSION,
        olcrtcSha = GeneratedAppInfo.OLCRTC_SHA
    )

    /** Off until Murka VPN has its own release repository (ReleaseMirror.GitHub). */
    const val updatesEnabled: Boolean = false

    val userAgent: String = "${value.name}/${value.version}"
    val diagnosticVersion: String = "${value.name}/${value.version} olcrtc/${value.olcrtcSha.take(12)}"
}
