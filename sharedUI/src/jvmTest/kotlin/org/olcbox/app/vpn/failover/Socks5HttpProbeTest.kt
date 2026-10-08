package org.olcbox.app.vpn.failover

import com.sun.net.httpserver.HttpServer
import org.olcbox.app.vpn.desktop.XrayProcess
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Socks5HttpProbeTest {
    @Test
    fun closedPortIsFailure() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(Socks5HttpProbe.probe("127.0.0.1", port, "u", "p", timeoutMs = 1000))
    }

    @Test
    fun probesThroughRealXrayWithPassword() {
        val binary = hostXray() ?: return
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/generate_204") { it.sendResponseHeaders(204, -1); it.close() }
            start()
        }
        val port = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"loglevel":"warning"},
            "inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks",
              "settings":{"auth":"password","accounts":[{"user":"u1","pass":"p1"}]}}],
            "outbounds":[{"protocol":"freedom"}]}"""
        val path = XrayProcess.writeConfig(Files.createTempDirectory("probe"), config)
        val process = ProcessBuilder(XrayProcess.command(binary, path)).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) {
                check(System.currentTimeMillis() < deadline) { "xray did not start" }
                Thread.sleep(50)
            }
            val targetPort = target.address.port
            assertTrue(Socks5HttpProbe.probe("127.0.0.1", port, "u1", "p1", "127.0.0.1", targetPort, timeoutMs = 3000))
            assertFalse(Socks5HttpProbe.probe("127.0.0.1", port, "u1", "wrong", "127.0.0.1", targetPort, timeoutMs = 3000))
            assertFalse(Socks5HttpProbe.probe("127.0.0.1", port, "u1", "p1", "127.0.0.1", targetPort, path = "/missing", timeoutMs = 3000))
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            target.stop(0)
        }
    }

    private fun hostXray() = Path("../desktopApp/build/generated/desktopNativeResources/native")
        .resolve(if (System.getProperty("os.arch").contains("aarch64")) "xray-darwin-arm64" else "xray-darwin-amd64")
        .takeIf { System.getProperty("os.name").lowercase().contains("mac") && it.exists() }
}
