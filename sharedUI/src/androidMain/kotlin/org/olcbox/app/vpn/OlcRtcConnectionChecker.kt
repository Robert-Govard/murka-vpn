package org.olcbox.app.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.xray.XrayConfig
import xraymobile.Xraymobile
import java.net.ServerSocket

internal object OlcRtcConnectionChecker {
    suspend fun check(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null
            if (config.isXray) return@withContext xrayCheck(config, CONNECTION_CHECK_TIMEOUT_MS)

            repeat(CONNECTION_CHECK_ATTEMPTS) {
                val socksPort = allocateLocalPort()

                val result: Long? = runCatching {
                    Mobile.new_().check(
                        config.bypassProvider,
                        config.transport,
                        config.id,
                        deviceId,
                        config.key,
                        socksPort.toLong(),
                        CONNECTION_CHECK_TIMEOUT_MS,
                        config.vp8Fps.toLong(),
                        config.vp8Batch.toLong()
                    )
                }.getOrNull()

                if (result != null && result > 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    suspend fun ping(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null
            if (config.isXray) return@withContext xrayCheck(config, HTTP_PING_TIMEOUT_MS)

            repeat(HTTP_PING_ATTEMPTS) {
                val socksPort = allocateLocalPort()

                val result: Long? = runCatching {
                    Mobile.new_().ping(
                        config.bypassProvider,
                        config.transport,
                        config.id,
                        deviceId,
                        config.key,
                        socksPort.toLong(),
                        HTTP_PING_TIMEOUT_MS,
                        HTTP_PING_URL,
                        config.vp8Fps.toLong(),
                        config.vp8Batch.toLong()
                    )
                }.onFailure {
                    Log.e("OlcRtcConnectionChecker", "HTTP ping failed", it)
                }.getOrNull()

                if (result != null && result >= 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    /** Remnawave server: fetch the ping URL through a temporary Xray. */
    private fun xrayCheck(config: LocationConfig, timeoutMs: Long): Long? =
        runCatching { Xraymobile.check(XrayConfig.forCheck(config.xrayConfig), HTTP_PING_URL, timeoutMs) }
            .onFailure { Log.e("OlcRtcConnectionChecker", "Xray check failed", it) }
            .getOrNull()
            ?.takeIf { it >= 0L }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L

    private const val HTTP_PING_ATTEMPTS = 1
    private const val HTTP_PING_TIMEOUT_MS = 8_000L
    private const val HTTP_PING_URL = "https://www.google.com/generate_204"
}
