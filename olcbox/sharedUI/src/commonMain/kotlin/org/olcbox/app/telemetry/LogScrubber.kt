package org.olcbox.app.telemetry

/** Removes subscription links, keys, UUIDs and passwords from log text before it leaves the device. */
internal object LogScrubber {
    private val proxyLinks = Regex("""\b(vless|vmess|trojan|ss|ssr|hysteria2?|hy2|tuic|wireguard|olcrtc|olcbox|murka|socks5?h?)://\S+""", RegexOption.IGNORE_CASE)
    private val urls = Regex("""\b(https?://[^/\s"'<>]+)/[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val jsonSecrets = Regex(
        """"(password|pass|id|key|token|privateKey|secretKey|publicKey|shortId|shortIds|auth|uuid)"\s*:\s*"[^"]*"""",
        RegexOption.IGNORE_CASE
    )
    private val uuids = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val longTokens = Regex("""(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{40,}(?![A-Za-z0-9+/_=-])""")

    fun scrub(text: String): String = text
        .replace(proxyLinks) { "${it.groupValues[1]}://***" }
        .replace(urls) { "${it.groupValues[1]}/***" }
        .replace(jsonSecrets) { "\"${it.groupValues[1]}\":\"***\"" }
        .replace(uuids, "***")
        .replace(longHex, "***")
        .replace(longTokens, "***")

    /** Last [lines] log lines, scrubbed. */
    fun tail(log: List<String>, lines: Int = 200): String = scrub(log.takeLast(lines).joinToString("\n"))
}
