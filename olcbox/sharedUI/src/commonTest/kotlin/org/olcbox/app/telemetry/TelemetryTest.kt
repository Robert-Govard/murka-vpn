package org.olcbox.app.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.identity.DeviceIdentityProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryTest {
    private class MemoryStore : TelemetryStore {
        override var enabled = true
        override var lastHelloAt = 0L
        override var lastConnectReportAt = 0L
        var crash: String? = null
        override fun pendingCrash() = crash
        override fun clearPendingCrash() { crash = null }
    }

    private data class Sent(val path: String, val body: Map<String, String>)

    private fun fixture(now: () -> Long): Triple<Telemetry, MemoryStore, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val client = HttpClient(MockEngine { req ->
            val json = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            sent += Sent(req.url.encodedPath, json.mapValues { it.value.jsonPrimitive.content })
            respond("", HttpStatusCode.Created)
        })
        val store = MemoryStore()
        val t = Telemetry(
            api = TelemetryApi(client, "https://example.org/murka/api"),
            store = store,
            identity = object : DeviceIdentityProvider { override suspend fun hwid() = "install-0123456789" },
            subscriptionUrls = { listOf("https://subgovard.mooo.com/TestShortUuid01x") },
            platform = "android", osVersion = "Android 15", appVersion = "1.0.19",
            logs = { listOf("start https://subgovard.mooo.com/TestShortUuid01x", "boom") },
            now = now,
        )
        return Triple(t, store, sent)
    }

    @Test
    fun helloAtMostHourly() = runTest {
        var now = 1_000_000_000L
        val (t, store, sent) = fixture { now }
        t.helloIfDue()
        t.helloIfDue()
        assertEquals(1, sent.size)
        assertEquals("/murka/api/hello", sent[0].path)
        assertEquals(sha256Hex("TestShortUuid01x"), sent[0].body["sub_hash"])
        assertEquals("install-0123456789", sent[0].body["device"])
        now += 61 * 60 * 1000
        t.helloIfDue()
        assertEquals(2, sent.size)
        assertEquals(now, store.lastHelloAt)
    }

    @Test
    fun connectErrorsRateLimitedAndLogsScrubbed() = runTest {
        var now = 1_000_000_000L
        val (t, _, sent) = fixture { now }
        t.reportConnectError("timeout")
        t.reportConnectError("timeout again")
        assertEquals(1, sent.size)
        assertEquals("connect", sent[0].body["kind"])
        assertTrue("TestShortUuid01x" !in sent[0].body.getValue("log"))
        now += 61 * 60 * 1000
        t.reportConnectError("later")
        assertEquals(2, sent.size)
    }

    @Test
    fun pendingCrashSentOnceAndUserReports() = runTest {
        val (t, store, sent) = fixture { 1L }
        store.crash = "java.lang.IllegalStateException: boom"
        t.sendPendingCrash()
        t.sendPendingCrash()
        assertEquals(1, sent.size)
        assertEquals("crash", sent[0].body["kind"])
        assertEquals(null, store.crash)
        assertTrue(t.reportUser("не работает телеграм"))
        assertEquals("user", sent[1].body["kind"])
        assertEquals("не работает телеграм", sent[1].body["message"])
    }

    @Test
    fun disabledSendsNothing() = runTest {
        val (t, store, sent) = fixture { 1L }
        store.enabled = false
        store.crash = "x"
        t.helloIfDue()
        t.reportConnectError("x")
        t.sendPendingCrash()
        assertEquals(0, sent.size)
        // An explicit user report is still sent: the user pressed the button.
        assertTrue(t.reportUser("help"))
        assertEquals(1, sent.size)
    }
}
