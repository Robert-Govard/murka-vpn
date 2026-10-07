package org.olcbox.app.data.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val REMNAWAVE = """[
 {"remarks":" 🇳🇱 Нидерланды ","log":{"loglevel":"warning","access":"/var/log/a.log"},
  "inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}},
              {"tag":"http","port":10809,"listen":"127.0.0.1","protocol":"http"}],
  "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"nl.example","port":443,"users":[{"id":"u","flow":"xtls-rprx-vision","encryption":"none"}]}]},
                "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"x","publicKey":"k","shortId":"s","fingerprint":"chrome"}}},
               {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
  "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"ip":["geoip:ru","geoip:private"],"outboundTag":"direct"}]},
  "dns":{"servers":[{"address":"1.1.1.1"}]},"policy":{"system":{}},"stats":{}},
 {"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"dk.example","port":443,"password":"p"}]},
                "streamSettings":{"network":"tcp","security":"tls"}}]}
]"""

class XrayConfigTest {
    private val json = Json

    @Test
    fun parsesRemnawaveSubscription() {
        val servers = XrayConfig.parseSubscription(REMNAWAVE)!!
        assertEquals(listOf("🇳🇱 Нидерланды", "Server 2"), servers.map { it.name })
        assertTrue(servers[0].config.contains("nl.example"))
    }

    @Test
    fun rejectsOtherFormats() {
        assertNull(XrayConfig.parseSubscription("olcrtc://wbstream?vp8channel@r#k\$n"))
        assertNull(XrayConfig.parseSubscription("""{"outbounds":[]}"""))
        assertNull(XrayConfig.parseSubscription("""[{"remarks":"no outbounds"}]"""))
        assertNull(XrayConfig.parseSubscription("dmxlc3M6Ly8="))
    }

    @Test
    fun prepareReplacesInboundsAndStripsServiceSections() {
        val raw = XrayConfig.parseSubscription(REMNAWAVE)!![0].config
        val out = json.parseToJsonElement(XrayConfig.prepare(raw, "127.0.0.1", 10901, "u1", "p1")).jsonObject
        val inbounds = out["inbounds"]!!.jsonArray
        assertEquals(1, inbounds.size)
        val socks = inbounds[0].jsonObject
        assertEquals("socks", socks["protocol"]!!.jsonPrimitive.content)
        assertEquals(10901, socks["port"]!!.jsonPrimitive.int)
        assertEquals("127.0.0.1", socks["listen"]!!.jsonPrimitive.content)
        val settings = socks["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
        assertEquals("u1", settings["accounts"]!!.jsonArray[0].jsonObject["user"]!!.jsonPrimitive.content)
        assertEquals("true", settings["udp"]!!.jsonPrimitive.content)
        assertTrue(socks["sniffing"]!!.jsonObject["routeOnly"]!!.jsonPrimitive.content == "true")
        assertFalse("stats" in out)
        assertFalse("remarks" in out)
        assertEquals("""{"loglevel":"warning"}""", out["log"].toString())
        assertEquals(3, out["outbounds"]!!.jsonArray.size)
        assertTrue("routing" in out && "dns" in out && "policy" in out)
    }

    @Test
    fun prepareWithoutCredentialsIsNoAuth() {
        val raw = XrayConfig.parseSubscription(REMNAWAVE)!![1].config
        val out = json.parseToJsonElement(XrayConfig.prepare(raw, "127.0.0.1", 1080, "", "")).jsonObject
        val settings = out["inbounds"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject
        assertEquals("noauth", settings["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun forCheckDropsRoutingSoNoGeoAssetsAreNeeded() {
        val raw = XrayConfig.parseSubscription(REMNAWAVE)!![0].config
        val out = json.parseToJsonElement(XrayConfig.forCheck(raw)).jsonObject
        assertFalse("routing" in out)
        assertFalse("stats" in out)
        assertEquals("proxy", out["outbounds"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun forCheckMovesProxyOutboundFirst() {
        val raw = """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"vless"}],"routing":{}}"""
        val out = json.parseToJsonElement(XrayConfig.forCheck(raw)).jsonObject
        assertEquals("proxy", out["outbounds"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun summaryDescribesProxyOutbound() {
        val servers = XrayConfig.parseSubscription(REMNAWAVE)!!
        assertEquals("VLESS" to "TCP · Reality", XrayConfig.summary(servers[0].config))
        assertEquals("TROJAN" to "TCP · TLS", XrayConfig.summary(servers[1].config))
        assertEquals("Xray" to "", XrayConfig.summary("not json"))
    }
}
