package org.olcbox.app.data.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One server from a Remnawave Xray-JSON subscription. */
data class XrayServer(val name: String, val config: String)

/** Remnawave Xray-JSON subscriptions: parsing, launch preparation, display. */
object XrayConfig {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sections that only make sense on a server or for panel tooling. */
    private val SERVICE_SECTIONS = listOf("stats", "api", "observatory", "burstObservatory", "metrics", "remarks")

    /**
     * Parses a JSON array of full Xray configs (Remnawave `/json`).
     * Returns null when the text is anything else.
     */
    fun parseSubscription(text: String): List<XrayServer>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull() ?: return null
        if (array.isEmpty()) return null
        return array.mapIndexed { index, element ->
            val config = element as? JsonObject ?: return null
            if (config["outbounds"] !is JsonArray) return null
            val name = (config["remarks"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            XrayServer(name = name.ifEmpty { "Server ${index + 1}" }, config = config.toString())
        }
    }

    /**
     * Turns a stored server config into the config Xray runs: one local SOCKS
     * inbound for the tunnel, service sections removed, routing/dns/outbounds kept.
     * [bindInterface] pins every outbound to a physical interface, so that Xray's
     * own traffic does not loop back into a desktop TUN that captures all routes.
     */
    fun prepare(
        raw: String,
        socksHost: String,
        socksPort: Int,
        username: String,
        password: String,
        bindInterface: String? = null
    ): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val out = root.toMutableMap()
        if (!bindInterface.isNullOrBlank()) {
            val outbounds = root["outbounds"] as? JsonArray
            if (outbounds != null) out["outbounds"] = JsonArray(outbounds.map { it.withInterface(bindInterface) })
        }
        SERVICE_SECTIONS.forEach { out.remove(it) }
        out["log"] = buildJsonObject { put("loglevel", "warning") }
        out["inbounds"] = buildJsonArray {
            addJsonObject {
                put("tag", "murka-socks")
                put("listen", socksHost)
                put("port", socksPort)
                put("protocol", "socks")
                putJsonObject("settings") {
                    if (username.isEmpty()) {
                        put("auth", "noauth")
                    } else {
                        put("auth", "password")
                        putJsonArray("accounts") {
                            addJsonObject {
                                put("user", username)
                                put("pass", password)
                            }
                        }
                    }
                    put("udp", true)
                }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") {
                        add("http")
                        add("tls")
                        add("quic")
                    }
                    put("routeOnly", true)
                }
            }
        }
        return JsonObject(out).toString()
    }

    /**
     * Config for a reachability check: without routing every request goes to
     * the first outbound, so the proxy outbound is moved first and no
     * geoip/geosite files are needed. Check replaces the inbounds itself.
     */
    fun forCheck(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val out = root.toMutableMap()
        SERVICE_SECTIONS.forEach { out.remove(it) }
        out.remove("routing")
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val proxy = outbounds.firstOrNull { it.string("tag") == "proxy" }
        if (proxy != null) out["outbounds"] = JsonArray(listOf(proxy) + outbounds.filterNot { it === proxy })
        return JsonObject(out).toString()
    }

    /** Protocol and transport labels of the proxy outbound, e.g. "VLESS" to "TCP · Reality". */
    fun summary(raw: String): Pair<String, String> = runCatching {
        val outbounds = json.parseToJsonElement(raw).jsonObject["outbounds"] as JsonArray
        val proxies = outbounds.mapNotNull { it as? JsonObject }
        val proxy = proxies.firstOrNull { it.string("tag") == "proxy" }
            ?: proxies.first { it.string("protocol") !in setOf("freedom", "blackhole", "dns") }
        val protocol = when (val p = proxy.string("protocol").orEmpty().lowercase()) {
            "shadowsocks" -> "SS"
            else -> p.uppercase()
        }
        val stream = proxy["streamSettings"] as? JsonObject
        val network = (stream?.string("network") ?: "tcp").uppercase()
        val security = stream?.string("security")
            ?.takeIf { it.isNotBlank() && it != "none" }
            ?.let { if (it == "tls") "TLS" else it.replaceFirstChar(Char::uppercaseChar) }
        protocol to listOfNotNull(network, security).joinToString(" · ")
    }.getOrDefault("Xray" to "")

    private fun JsonElement.withInterface(name: String): JsonElement {
        val outbound = this as? JsonObject ?: return this
        val stream = (outbound["streamSettings"] as? JsonObject).orEmpty().toMutableMap()
        val sockopt = (stream["sockopt"] as? JsonObject).orEmpty().toMutableMap()
        sockopt["interface"] = JsonPrimitive(name)
        stream["sockopt"] = JsonObject(sockopt)
        return JsonObject(outbound + ("streamSettings" to JsonObject(stream)))
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
