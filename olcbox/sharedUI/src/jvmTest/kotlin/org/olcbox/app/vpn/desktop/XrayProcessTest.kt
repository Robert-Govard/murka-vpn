package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrayProcessTest {
    @Test
    fun commandRunsConfig() {
        assertEquals(
            listOf("/opt/xray", "run", "-c", "/tmp/x.json"),
            XrayProcess.command(Path("/opt/xray"), Path("/tmp/x.json"))
        )
    }

    @Test
    fun configIsOwnerOnly() {
        val dir = Files.createTempDirectory("xray-test")
        val path = XrayProcess.writeConfig(dir, """{"outbounds":[]}""")
        assertEquals("""{"outbounds":[]}""", Files.readString(path))
        if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        }
    }

    @Test
    fun logLines() {
        assertTrue(XrayProcess.isReadyLine("2026/10/08 00:44:22.385469 [Warning] core: Xray 26.3.27 started"))
        assertFalse(XrayProcess.isReadyLine("2026/10/08 [Info] transport: started listening"))
        assertTrue(XrayProcess.isFatalLine("Failed to start: main: failed to load config files"))
        assertFalse(XrayProcess.isFatalLine("2026/10/08 [Warning] core: Xray 26.3.27 started"))
    }

    /** Runs the bundled host xray (if built) with a direct outbound and fetches through it. */
    @Test
    fun realXrayProxiesTraffic() {
        val binary = hostXrayBinary() ?: return
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { it.sendResponseHeaders(200, 2); it.responseBody.use { body -> body.write("ok".toByteArray()) } }
            start()
        }
        val port = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"loglevel":"warning"},
            "inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"auth":"noauth"}}],
            "outbounds":[{"protocol":"freedom"}]}"""
        val path = XrayProcess.writeConfig(Files.createTempDirectory("xray-real"), config)
        val process = ProcessBuilder(XrayProcess.command(binary, path)).redirectErrorStream(true).start()
        try {
            val ready = process.inputStream.bufferedReader().lineSequence().take(20).any { XrayProcess.isReadyLine(it) }
            assertTrue(ready, "xray did not report start")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val body = URI("http://127.0.0.1:${target.address.port}/").toURL().openConnection(proxy)
                .getInputStream().use { it.readBytes().decodeToString() }
            assertEquals("ok", body)
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            target.stop(0)
        }
    }

    private fun hostXrayBinary(): Path? {
        val os = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch") == "arm64") "arm64" else "amd64"
        val name = when {
            os.contains("mac") -> "xray-darwin-$arch"
            os.contains("win") -> "xray-windows-amd64.exe"
            else -> "xray-linux-$arch"
        }
        return Path("../desktopApp/build/generated/desktopNativeResources/native/$name").takeIf { it.exists() }
    }
}
