package org.olcbox.app.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.vpn.VpnStatus
import kotlin.time.Clock

/** Telemetry endpoint behind the panel nginx; murka-admin runs on Finland-2. */
const val TELEMETRY_BASE_URL = "https://$SUBSCRIPTION_HOST/murka/api"

/** Small persisted state of telemetry; one per platform. */
interface TelemetryStore {
    var enabled: Boolean
    var lastHelloAt: Long
    var lastConnectReportAt: Long
    fun pendingCrash(): String?
    fun clearPendingCrash()
}

class TelemetryApi(private val client: HttpClient, private val baseUrl: String = TELEMETRY_BASE_URL) {
    suspend fun post(path: String, fields: Map<String, String>): Boolean = runCatching {
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString()
        client.post("$baseUrl/$path") { setBody(TextContent(body, ContentType.Application.Json)) }.status.isSuccess()
    }.getOrDefault(false)
}

/**
 * Usage statistics and error reports for the owner's murka-admin page:
 * a hello at most hourly, connection errors at most hourly, a crash saved by the
 * platform hook and sent on the next start, and user reports from the log screen.
 */
class Telemetry(
    private val api: TelemetryApi,
    private val store: TelemetryStore,
    private val identity: DeviceIdentityProvider,
    private val subscriptionUrls: suspend () -> List<String>,
    private val platform: String,
    private val osVersion: String,
    private val appVersion: String,
    private val logs: () -> List<String>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    var enabled: Boolean
        get() = store.enabled
        set(value) { store.enabled = value }

    /** Hello now and every day, crash from the last run, and connection errors as they happen. */
    fun start(scope: CoroutineScope, status: StateFlow<VpnStatus>) {
        scope.launch {
            sendPendingCrash()
            while (true) {
                helloIfDue()
                delay(HELLO_EVERY_MS)
            }
        }
        scope.launch {
            var previous: VpnStatus? = null
            status.collect { current ->
                if (current is VpnStatus.Error && previous !is VpnStatus.Error) reportConnectError(current.message)
                previous = current
            }
        }
    }

    suspend fun helloIfDue() = mutex.withLock {
        if (!store.enabled || now() - store.lastHelloAt < HELLO_MIN_GAP_MS) return@withLock
        if (send("hello", base() ?: return@withLock)) store.lastHelloAt = now()
    }

    suspend fun reportConnectError(message: String) = mutex.withLock {
        if (!store.enabled || now() - store.lastConnectReportAt < CONNECT_REPORT_GAP_MS) return@withLock
        store.lastConnectReportAt = now()
        report("connect", message)
    }

    suspend fun sendPendingCrash() = mutex.withLock {
        val crash = store.pendingCrash() ?: return@withLock
        if (!store.enabled) {
            store.clearPendingCrash()
            return@withLock
        }
        if (report("crash", crash)) store.clearPendingCrash()
    }

    /** Sent even with statistics off: the user asked for it explicitly. */
    suspend fun reportUser(text: String): Boolean = mutex.withLock { report("user", text) }

    private suspend fun report(kind: String, message: String): Boolean {
        val fields = base() ?: return false
        fields["kind"] = kind
        fields["message"] = LogScrubber.scrub(message).take(MAX_MESSAGE)
        fields["log"] = LogScrubber.tail(logs()).takeLast(MAX_LOG)
        return send("report", fields)
    }

    private suspend fun base(): MutableMap<String, String>? {
        val sub = subscriptionHash(subscriptionUrls()) ?: return null // not a Murka subscriber
        return mutableMapOf(
            "device" to identity.hwid(), "sub_hash" to sub, "platform" to platform,
            "os" to osVersion.take(64), "app_version" to appVersion
        )
    }

    private suspend fun send(path: String, fields: Map<String, String>) = api.post(path, fields)

    private companion object {
        const val HELLO_EVERY_MS = 24L * 60 * 60 * 1000
        const val HELLO_MIN_GAP_MS = 60L * 60 * 1000
        const val CONNECT_REPORT_GAP_MS = 60L * 60 * 1000
        const val MAX_MESSAGE = 4000
        const val MAX_LOG = 60_000
    }
}
