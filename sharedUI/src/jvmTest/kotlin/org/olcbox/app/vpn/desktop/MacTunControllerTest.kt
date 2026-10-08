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
    fun physicalInterfaceSkipsOtherVpnClients() {
        assertEquals("en0", MacTunController.physicalInterface(netstatBehindVpnClient))
    }

    @Test
    fun physicalInterfaceIsNullWithoutGatewayRoute() {
        assertNull(MacTunController.physicalInterface("default            link#23            UCSg                utun7"))
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
        val xray = MacTunController.configContent(10808, "user", "p'w", udpOverTcp = false)
        assertTrue("udp: 'udp'" in xray)
        assertTrue("password: 'p''w'" in xray)
        assertTrue("post-up-script: ${MacTunController.DIR_PLACEHOLDER}/up.sh" in xray)
        val olcRtc = MacTunController.configContent(10808, "", "", udpOverTcp = true)
        assertTrue("udp: 'tcp'" in olcRtc)
        assertFalse("username" in olcRtc)
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
            stopFile = Path.of("/Users/a b/stop")
        )
        assertTrue("cp '/Users/a b/helper.sh'" in cmd)
        assertTrue("'/Users/a b/hev' '/Users/a b/hev.yml' 42 '/Users/a b/stop'" in cmd)
        assertTrue(cmd.endsWith("&"))
    }

    /** Runs the real helper unprivileged with a fake hev and a recording `route`. */
    @Test
    fun helperBringsTunnelUpAndTearsItDownOnStopFile() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("murka-tun-test")
        try {
            val routeLog = dir.resolve("route.log")
            val route = executable(dir.resolve("route"), "#!/bin/sh\necho \"$@\" >> '$routeLog'\n")
            // Fake hev: run post-up-script from its config with a utun name, then idle.
            val hev = executable(
                dir.resolve("hev"),
                "#!/bin/sh\nup=$(sed -n 's/.*post-up-script: //p' \"$1\")\n\"\$up\" utun99\nexec sleep 60\n"
            )
            val config = dir.resolve("hev.yml")
            Files.writeString(config, MacTunController.configContent(10808, "", "", udpOverTcp = false))
            val paths = MacTunController.HelperPaths(
                pid = dir.resolve("pid").toString(),
                ifName = dir.resolve("ifname").toString(),
                log = dir.resolve("hev.log").toString(),
                route = route.toString(),
                tmpDir = dir.toString()
            )
            val helper = executable(dir.resolve("helper.sh"), MacTunController.helperScript(paths))
            val stopFile = dir.resolve("stop")
            val process = ProcessBuilder(
                "/bin/sh", helper.toString(), hev.toString(), config.toString(),
                ProcessHandle.current().pid().toString(), stopFile.toString()
            ).redirectErrorStream(true).start()

            waitUntil { Files.exists(Path.of(paths.ifName)) }
            assertEquals("utun99", Files.readString(Path.of(paths.ifName)).trim())
            assertEquals(
                listOf("-q -n add -inet 0.0.0.0/1 -interface utun99", "-q -n add -inet 128.0.0.0/1 -interface utun99"),
                Files.readAllLines(routeLog)
            )

            Files.writeString(stopFile, "")
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "helper did not exit on stop file")
            assertFalse(Files.exists(Path.of(paths.ifName)))
            assertFalse(Files.exists(Path.of(paths.pid)))
            assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().startsWith("murka-tun.") } })
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
