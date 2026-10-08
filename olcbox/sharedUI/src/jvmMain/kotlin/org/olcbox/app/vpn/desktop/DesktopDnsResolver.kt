package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object DesktopDnsResolver {
    const val FALLBACK_DNS_SERVER = "1.1.1.1:53"

    fun current(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> currentLinuxDnsServer() ?: FALLBACK_DNS_SERVER
            // System DNS, not a public resolver: under whitelists or behind another
            // VPN client direct queries to 1.1.1.1 often get no answer.
            DesktopOs.MacOS -> runCommand(listOf("/usr/sbin/scutil", "--dns"))
                ?.let(::selectMacDnsServer) ?: FALLBACK_DNS_SERVER
            // PowerShell can take several seconds to start cold.
            DesktopOs.Windows -> runCommand(windowsDnsCommand(), timeoutSeconds = 10)
                ?.let(::selectWindowsDnsServer) ?: FALLBACK_DNS_SERVER
            DesktopOs.Other -> FALLBACK_DNS_SERVER
        }
    }

    private fun currentLinuxDnsServer(): String? {
        val defaultRouteOutput = runCommand(listOf("ip", "route", "show", "default")).orEmpty()
        val interfaceName = defaultRouteInterface(defaultRouteOutput)

        val resolvectlOutput = if (interfaceName != null) {
            runCommand(listOf("resolvectl", "dns", interfaceName))
        } else {
            runCommand(listOf("resolvectl", "dns"))
        }
        val nmcliOutput = interfaceName?.let {
            runCommand(listOf("nmcli", "-g", "IP4.DNS,IP6.DNS", "device", "show", it))
        }
        val resolvConf = runCatching {
            Files.readString(Path.of("/etc/resolv.conf"))
        }.getOrDefault("")

        return selectLinuxDnsServer(
            resolvectlOutput = resolvectlOutput.orEmpty(),
            nmcliOutput = nmcliOutput.orEmpty(),
            resolvConf = resolvConf
        )
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS): String? {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    internal fun defaultRouteInterface(output: String): String? {
        return output.lineSequence()
            .filter { it.trimStart().startsWith("default ") }
            .mapNotNull { line ->
                DEFAULT_ROUTE_DEVICE.find(line)?.groupValues?.getOrNull(1)
            }
            .firstOrNull { it != LinuxTunController.TUN_NAME }
    }

    /** First non-loopback `nameserver[n]` of the first resolver in `scutil --dns`. */
    internal fun selectMacDnsServer(scutilOutput: String): String? {
        val firstResolver = scutilOutput.substringAfter("resolver #1", missingDelimiterValue = "")
            .substringBefore("resolver #2")
        val servers = firstResolver.lineSequence()
            .mapNotNull { MAC_NAMESERVER.find(it)?.groupValues?.get(1) }
            .mapNotNull(::ipLiteralOrNull)
            .toList()
        val selected = servers.firstOrNull { !isLoopback(it) } ?: return null
        return dnsEndpoint(selected)
    }

    /** DNS servers of the default-route interface, one per line (see [windowsDnsCommand]). */
    internal fun selectWindowsDnsServer(output: String): String? {
        val selected = ipAddresses(output).firstOrNull { !isLoopback(it) } ?: return null
        return dnsEndpoint(selected)
    }

    private fun windowsDnsCommand(): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "\$r = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 | " +
            "Where-Object { \$_.InterfaceAlias -ne '${WindowsTunController.TUN_NAME}' } | " +
            "Sort-Object RouteMetric | Select-Object -First 1; " +
            "(Get-DnsClientServerAddress -InterfaceIndex \$r.InterfaceIndex -AddressFamily IPv4).ServerAddresses"
    )

    internal fun selectLinuxDnsServer(
        resolvectlOutput: String,
        nmcliOutput: String,
        resolvConf: String
    ): String? {
        val candidates = buildList {
            addAll(ipAddresses(resolvectlOutput))
            addAll(ipAddresses(nmcliOutput))
            addAll(resolvConfNameservers(resolvConf))
        }.distinct()

        val selected = candidates.firstOrNull { !isLoopback(it) }
            ?: candidates.firstOrNull()
            ?: return null
        return dnsEndpoint(selected)
    }

    private fun ipAddresses(output: String): List<String> {
        return output.lineSequence()
            .flatMap { it.splitToSequence(Regex("\\s+")) }
            .mapNotNull(::ipLiteralOrNull)
            .toList()
    }

    private fun resolvConfNameservers(content: String): List<String> {
        return content.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("nameserver ") }
            .mapNotNull { line -> ipLiteralOrNull(line.substringAfter("nameserver ").trim()) }
            .toList()
    }

    private fun ipLiteralOrNull(token: String): String? {
        val candidate = token
            .trim()
            .trim(',', ';')
            .substringBefore('#')
        if (candidate.isEmpty() || ('.' !in candidate && ':' !in candidate)) return null

        val addressWithoutZone = candidate.substringBefore('%')
        val parsed = runCatching { InetAddress.getByName(addressWithoutZone) }.getOrNull()
            ?: return null
        if (':' in candidate && parsed.hostAddress?.contains(':') != true) return null
        if ('.' in candidate && ':' !in candidate && parsed.hostAddress?.contains('.') != true) return null
        return candidate
    }

    private fun isLoopback(address: String): Boolean {
        return runCatching {
            InetAddress.getByName(address.substringBefore('%')).isLoopbackAddress
        }.getOrDefault(false)
    }

    private fun dnsEndpoint(address: String): String {
        return if (':' in address) "[$address]:53" else "$address:53"
    }

    private val DEFAULT_ROUTE_DEVICE = Regex("(?:^|\\s)dev\\s+(\\S+)")
    private val MAC_NAMESERVER = Regex("nameserver\\[\\d+\\]\\s*:\\s*(\\S+)")
    private const val COMMAND_TIMEOUT_SECONDS = 2L
}
