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
    fun prepareBindsEveryOutboundToInterface() {
        val raw = """{"outbounds":[
            {"tag":"proxy","protocol":"vless","streamSettings":{"network":"tcp","security":"reality",
             "realitySettings":{"serverName":"x"},"sockopt":{"mark":7}}},
            {"tag":"direct","protocol":"freedom"},
            {"tag":"block","protocol":"blackhole"}]}"""
        val out = json.parseToJsonElement(XrayConfig.prepare(raw, "127.0.0.1", 1080, "", "", bindInterface = "Ethernet")).jsonObject
        val outbounds = out["outbounds"]!!.jsonArray.map { it.jsonObject }
        for (o in outbounds) {
            val sockopt = o["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
            assertEquals("Ethernet", sockopt["interface"]!!.jsonPrimitive.content)
        }
        val proxyStream = outbounds[0]["streamSettings"]!!.jsonObject
        assertEquals("reality", proxyStream["security"]!!.jsonPrimitive.content)
        assertEquals("x", proxyStream["realitySettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content)
        assertEquals(7, proxyStream["sockopt"]!!.jsonObject["mark"]!!.jsonPrimitive.int)
    }

    @Test
    fun prepareWithoutInterfaceLeavesOutboundsAlone() {
        val raw = XrayConfig.parseSubscription(REMNAWAVE)!![0].config
        val before = json.parseToJsonElement(raw).jsonObject["outbounds"]
        val after = json.parseToJsonElement(XrayConfig.prepare(raw, "127.0.0.1", 1080, "", "")).jsonObject["outbounds"]
        assertEquals(before, after)
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

class XrayPinServersTest {
    private val raw = """
        {"outbounds":[
          {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"node.example.org","port":443,"users":[{"id":"u"}]}]},
           "streamSettings":{"security":"reality","realitySettings":{"serverName":"cover.example.com"}}},
          {"tag":"tro","protocol":"trojan","settings":{"servers":[{"address":"tro.example.org","port":443,"password":"p"}]},
           "streamSettings":{"security":"tls","tlsSettings":{}}},
          {"tag":"ip","protocol":"vless","settings":{"vnext":[{"address":"203.0.113.9","port":443,"users":[{"id":"u"}]}]}},
          {"tag":"direct","protocol":"freedom"}
        ]}
    """.trimIndent()

    @Test
    fun serverDomainsBecomeIpsAndTlsKeepsTheName() {
        val resolved = mapOf("node.example.org" to "198.51.100.1", "tro.example.org" to "198.51.100.2")
        val out = kotlinx.serialization.json.Json.parseToJsonElement(XrayConfig.pinServerAddresses(raw) { resolved[it] }).toString()
        assertTrue("\"address\":\"198.51.100.1\"" in out, out)
        assertTrue("\"address\":\"198.51.100.2\"" in out, out)
        // Reality already names its cover site; TLS gets the original domain as SNI.
        assertTrue("\"serverName\":\"cover.example.com\"" in out, out)
        assertTrue("\"tlsSettings\":{\"serverName\":\"tro.example.org\"}" in out, out)
        assertTrue("\"address\":\"203.0.113.9\"" in out, out)
    }

    @Test
    fun unresolvedDomainsStayAsTheyAre() {
        val out = XrayConfig.pinServerAddresses(raw) { null }
        assertTrue("node.example.org" in out && "tro.example.org" in out)
    }
}
