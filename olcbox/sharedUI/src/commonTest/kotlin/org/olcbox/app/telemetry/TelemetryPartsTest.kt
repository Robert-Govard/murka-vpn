package org.olcbox.app.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryPartsTest {
    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        // Two blocks, and UTF-8.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
        assertEquals(64, sha256Hex("Мурка").length)
    }

    @Test
    fun scrubberRemovesSecretsAndKeepsTheRest() {
        val log = listOf(
            "Fetching https://subgovard.mooo.com/TestShortUuid01x/json",
            "import vless://3b241101-e2bb-4255-8caf-4136c566a962@1.2.3.4:443?security=reality#DE",
            "olcrtc://wbstream?vp8channel@01a117ca-6a96-70d2-8bfa-3e1432545a0e#d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799\$DK-1",
            """{"id":"3b241101-e2bb-4255-8caf-4136c566a962","password":"hunter2","flow":"xtls-rprx-vision"}""",
            "socks5://user:secretpass@127.0.0.1:10808",
            "Desktop start failed: connection refused",
        ).joinToString("\n")
        val clean = LogScrubber.scrub(log)
        for (secret in listOf("TestShortUuid01x", "3b241101", "hunter2", "secretpass", "d823fa01cb3e", "01a117ca-6a96")) {
            assertFalse(secret in clean, "leaked $secret:\n$clean")
        }
        assertTrue("subgovard.mooo.com" in clean)
        assertTrue("Desktop start failed: connection refused" in clean)
        assertTrue("xtls-rprx-vision" in clean)
    }

    @Test
    fun scrubberKeepsTheTail() {
        val lines = (1..500).map { "line $it" }
        val tail = LogScrubber.tail(lines, 200)
        assertEquals("line 301", tail.lines().first())
        assertEquals("line 500", tail.lines().last())
    }

    @Test
    fun subHashComesFromOurSubscriptionLinks() {
        val expected = sha256Hex("TestShortUuid01x")
        assertEquals(expected, subscriptionHash(listOf("https://other.example/abc", "https://subgovard.mooo.com/TestShortUuid01x/json")))
        assertEquals(expected, subscriptionHash(listOf("https://subgovard.mooo.com/TestShortUuid01x")))
        assertNull(subscriptionHash(listOf("https://other.example/abc")))
        assertNull(subscriptionHash(emptyList()))
    }
}
