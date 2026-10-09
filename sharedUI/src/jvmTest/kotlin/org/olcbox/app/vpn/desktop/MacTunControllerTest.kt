package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacTunControllerTest {
    /** Real routing table of a Mac where another VPN client (utun7) holds the first default route. */
    private val netstatBehindVpnClient = """
        Routing tables

        Internet:
        Destination        Gateway            Flags               Netif Expire
        default            link#23            UCSg                utun7
        default            192.168.50.1       UGScIg                en0
        1.1.1.1            link#23            UHWIig              utun7
        127                127.0.0.1          UCS                   lo0
    """.trimIndent()

    @Test
    fun physicalRouteSkipsOtherVpnClients() {
        assertEquals(
            MacTunController.PhysicalRoute("en0", "192.168.50.1"),
            MacTunController.physicalRoute(netstatBehindVpnClient)
        )
    }

    @Test
    fun physicalRouteIsNullWithoutGatewayRoute() {
        assertNull(MacTunController.physicalRoute("default            link#23            UCSg                utun7"))
    }

    @Test
    fun tunnelRoutesNeedBothHalves() {
        val half = "0/1                utun9              USc                 utun9\n"
        val both = half + "128.0/1            utun9              USc                 utun9\n"
        assertFalse(MacTunController.hasTunnelRoutes(half, "utun9"))
        assertTrue(MacTunController.hasTunnelRoutes(both, "utun9"))
        assertFalse(MacTunController.hasTunnelRoutes(both, "utun7"))
    }

    @Test
    fun configPicksUdpModeAndCredentials() {
        val xray = MacTunController.configContent(10808, "user", "p'w", udpOverTcp = false, mapDns = false)
        assertTrue("udp: 'udp'" in xray)
        assertTrue("password: 'p''w'" in xray)
        assertTrue("post-up-script: ${MacTunController.DIR_PLACEHOLDER}/up.sh" in xray)
        val olcRtc = MacTunController.configContent(10808, "", "", udpOverTcp = true, mapDns = true)
        assertTrue("udp: 'tcp'" in olcRtc)
        assertFalse("username" in olcRtc)
        // IPv6 goes into the tunnel too, so it cannot leak around the VPN.
        assertTrue("ipv6: '${MacTunController.TUN_IPV6_ADDRESS}'" in xray && "ipv6:" in olcRtc)
        // olcRTC's SOCKS has no UDP: DNS is answered by hev with mapped addresses.
        assertTrue("mapdns:" in olcRtc && "address: ${MacTunController.MAPDNS_ADDRESS}" in olcRtc)
        assertFalse("mapdns:" in xray)
    }

    @Test
    fun appleScriptEscapesQuotesAndBackslashes() {
        val script = MacTunController.adminAppleScript("echo \"a\\b\"", "Мурка")
        assertEquals(
            "do shell script \"echo \\\"a\\\\b\\\"\" with prompt \"Мурка\" with administrator privileges",
            script
        )
    }

    @Test
    fun launchCommandQuotesPathsWithSpaces() {
        val cmd = MacTunController.launchCommand(
            helper = Path.of("/Users/a b/helper.sh"),
            hevBinary = Path.of("/Users/a b/hev"),
            config = Path.of("/Users/a b/hev.yml"),
            appPid = 42,
            stopFile = Path.of("/Users/a b/stop"),
            physical = MacTunController.PhysicalRoute("en0", "192.168.50.1"),
            dnsServer = "1.1.1.1"
        )
        assertTrue("cp '/Users/a b/helper.sh'" in cmd)
        assertTrue("'/Users/a b/hev' '/Users/a b/hev.yml' 42 '/Users/a b/stop' 'en0' '192.168.50.1' '1.1.1.1'" in cmd)
        assertTrue(cmd.endsWith("& }"))
    }

    /**
     * The helper lives for the whole session, so the launch command must detach it
     * fully: osascript waits while anything still holds its stdin/stdout/stderr, and
     * the app then hangs on "connecting" forever.
     */
    @Test
    fun osascriptReturnsWhileHelperKeepsRunning() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("murka-osa-test")
        try {
            val helper = executable(dir.resolve("helper.sh"), "#!/bin/sh\nsleep 15\n")
            val command = MacTunController.launchCommand(
                helper = helper,
                hevBinary = dir.resolve("hev"),
                config = dir.resolve("hev.yml"),
                appPid = ProcessHandle.current().pid(),
                stopFile = dir.resolve("stop"),
                physical = MacTunController.PhysicalRoute("en0", "192.168.50.1"),
                dnsServer = "1.1.1.1"
            )
            // Same AppleScript as production, minus the admin prompt.
            val script = MacTunController.adminAppleScript(command, "test")
                .substringBefore(" with prompt")
            val started = System.currentTimeMillis()
            val osascript = ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start()
            val returned = osascript.waitFor(8, TimeUnit.SECONDS)
            if (!returned) osascript.destroyForcibly()
            assertTrue(returned, "osascript still waiting after ${System.currentTimeMillis() - started} ms")
            assertEquals(0, osascript.exitValue(), osascript.inputStream.bufferedReader().readText())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun helperRejectsMalformedPhysicalRoute() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("murka-tun-bad")
        try {
            val paths = MacTunController.HelperPaths(
                pid = dir.resolve("pid").toString(), ifName = dir.resolve("ifname").toString(),
                log = dir.resolve("hev.log").toString(), route = "/usr/bin/false", tmpDir = dir.toString()
            )
            val helper = executable(dir.resolve("helper.sh"), MacTunController.helperScript(paths))
            val process = ProcessBuilder(
                "/bin/sh", helper.toString(), "/bin/sleep", "/dev/null", "1", dir.resolve("stop").toString(),
                "en0;reboot", "192.168.50.1"
            ).redirectErrorStream(true).start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertTrue(process.exitValue() != 0)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Runs the real helper unprivileged with a fake hev and a recording `route`. */
    @Test
    fun helperBringsTunnelUpAndTearsItDownOnStopFile() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("murka-tun-test")
        try {
            val routeLog = dir.resolve("route.log")
            val route = executable(dir.resolve("route"), "#!/bin/sh\necho \"$@\" >> '$routeLog'\n")
            val nsLog = dir.resolve("networksetup.log")
            val networksetup = executable(
                dir.resolve("networksetup"),
                "#!/bin/sh\necho \"$@\" >> '$nsLog'\ncase \"$1\" in\n" +
                    "-listnetworkserviceorder) printf '(1) Thunderbolt Bridge\\n(Hardware Port: Thunderbolt Bridge, Device: bridge0)\\n\\n(2) Wi-Fi\\n(Hardware Port: Wi-Fi, Device: en0)\\n';;\n" +
                    "-getdnsservers) echo \"There aren't any DNS Servers set on Wi-Fi.\";;\nesac\n"
            )
            // Fake hev: run post-up-script from its config with a utun name, then idle.
            val hev = executable(
                dir.resolve("hev"),
                "#!/bin/sh\nup=$(sed -n 's/.*post-up-script: //p' \"$1\")\n\"\$up\" utun99\nexec sleep 60\n"
            )
            val config = dir.resolve("hev.yml")
            Files.writeString(config, MacTunController.configContent(10808, "", "", udpOverTcp = false, mapDns = false))
            val paths = MacTunController.HelperPaths(
                pid = dir.resolve("pid").toString(),
                ifName = dir.resolve("ifname").toString(),
                log = dir.resolve("hev.log").toString(),
                route = route.toString(),
                tmpDir = dir.toString(),
                networksetup = networksetup.toString(),
                dnsBackup = dir.resolve("dns.backup").toString(),
                flushDns = "true"
            )
            val helper = executable(dir.resolve("helper.sh"), MacTunController.helperScript(paths))
            val stopFile = dir.resolve("stop")
            val process = ProcessBuilder(
                "/bin/sh", helper.toString(), hev.toString(), config.toString(),
                ProcessHandle.current().pid().toString(), stopFile.toString(), "en0", "192.168.50.1", "1.1.1.1"
            ).redirectErrorStream(true).start()

            waitUntil { Files.exists(Path.of(paths.ifName)) }
            waitUntil { Files.exists(nsLog) && "-setdnsservers Wi-Fi 1.1.1.1" in Files.readString(nsLog) }
            assertEquals("utun99", Files.readString(Path.of(paths.ifName)).trim())
            assertEquals(
                listOf(
                    // Sockets bound to en0 (Xray, olcRTC) need a scoped default, or they get
                    // "network is unreachable" once the utun holds 0/1 + 128/1.
                    "-q -n add -ifscope en0 default 192.168.50.1",
                    "-q -n add -inet 0.0.0.0/1 -interface utun99",
                    "-q -n add -inet 128.0.0.0/1 -interface utun99",
                    "-q -n add -inet6 ::/1 -interface utun99",
                    "-q -n add -inet6 8000::/1 -interface utun99"
                ),
                Files.readAllLines(routeLog)
            )

            Files.writeString(stopFile, "")
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "helper did not exit on stop file")
            assertEquals("-q -n delete -ifscope en0 default", Files.readAllLines(routeLog).last())
            // The previous DNS (none set: DHCP) is restored and the backup removed.
            assertEquals("-setdnsservers Wi-Fi Empty", Files.readAllLines(nsLog).last())
            assertFalse(Files.exists(dir.resolve("dns.backup")))
            assertFalse(Files.exists(Path.of(paths.ifName)))
            assertFalse(Files.exists(Path.of(paths.pid)))
            assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().startsWith("murka-tun.") } })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Another VPN client already made en0 non-primary, so macOS has its own scoped default:
     * our add fails with "File exists" and the helper must leave that route alone on stop.
     */
    @Test
    fun helperKeepsExistingScopedDefault() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("murka-tun-keep")
        try {
            val routeLog = dir.resolve("route.log")
            val route = executable(
                dir.resolve("route"),
                "#!/bin/sh\necho \"$@\" >> '$routeLog'\ncase \"$*\" in *'add -ifscope'*) exit 1;; esac\n"
            )
            val hev = executable(
                dir.resolve("hev"),
                "#!/bin/sh\nup=$(sed -n 's/.*post-up-script: //p' \"$1\")\n\"\$up\" utun99\nexec sleep 60\n"
            )
            val config = dir.resolve("hev.yml")
            Files.writeString(config, MacTunController.configContent(10808, "", "", udpOverTcp = false, mapDns = false))
            val paths = MacTunController.HelperPaths(
                pid = dir.resolve("pid").toString(), ifName = dir.resolve("ifname").toString(),
                log = dir.resolve("hev.log").toString(), route = route.toString(), tmpDir = dir.toString()
            )
            val helper = executable(dir.resolve("helper.sh"), MacTunController.helperScript(paths))
            val stopFile = dir.resolve("stop")
            val process = ProcessBuilder(
                "/bin/sh", helper.toString(), hev.toString(), config.toString(),
                ProcessHandle.current().pid().toString(), stopFile.toString(), "en0", "192.168.50.1"
            ).redirectErrorStream(true).start()
            waitUntil { Files.exists(Path.of(paths.ifName)) }
            Files.writeString(stopFile, "")
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertTrue(Files.readAllLines(routeLog).none { "delete" in it }, Files.readString(routeLog))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun executable(path: Path, body: String): Path {
        Files.writeString(path, body)
        path.toFile().setExecutable(true)
        return path
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }
}
