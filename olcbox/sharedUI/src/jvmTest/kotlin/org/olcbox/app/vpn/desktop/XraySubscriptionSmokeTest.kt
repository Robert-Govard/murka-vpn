package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.xray.XrayConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manual end-to-end check of the desktop Xray path against a real Remnawave
 * subscription. Skipped unless MURKA_SMOKE_SUB_URL is set, e.g.
 * MURKA_SMOKE_SUB_URL=https://sub.example/<shortUuid> ./gradlew :sharedUI:jvmTest --tests '*XraySubscriptionSmokeTest*'
 */
class XraySubscriptionSmokeTest {
    @Test
    fun everyServerReachesTheInternet() {
        val url = System.getenv("MURKA_SMOKE_SUB_URL")?.trimEnd('/') ?: return
        val binary = Path("../desktopApp/build/generated/desktopNativeResources/native")
            .resolve(if (System.getProperty("os.arch").contains("aarch64")) "xray-darwin-arm64" else "xray-darwin-amd64")
        val assets = Path("../desktopApp/build/generated/desktopNativeResources/native/xray")
        assertTrue(binary.exists() && assets.resolve("geosite.dat").exists(), "build :desktopApp:verifyDesktopNativeResources first")

        val text = URI("$url/json").toURL().openStream().use { it.readBytes().decodeToString() }
        val servers = XrayConfig.parseSubscription(text) ?: error("not a Remnawave Xray subscription")
        val results = servers.map { server ->
            val port = ServerSocket(0).use { it.localPort }
            val config = XrayConfig.prepare(server.config, "127.0.0.1", port, "u", "p")
            val path = XrayProcess.writeConfig(Files.createTempDirectory("xray-smoke"), config)
            val process = ProcessBuilder(XrayProcess.command(binary, path)).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()[XrayProcess.ASSET_ENV] = assets.toAbsolutePath().toString() }
                .start()
            try {
                val deadline = System.currentTimeMillis() + 10_000
                while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) {
                    check(process.isAlive && System.currentTimeMillis() < deadline) { "${server.name}: xray did not start" }
                    Thread.sleep(100)
                }
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication() = java.net.PasswordAuthentication("u", "p".toCharArray())
                })
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                val ip = URI("https://api.ipify.org").toURL().openConnection(proxy)
                    .apply { connectTimeout = 10_000; readTimeout = 10_000 }
                    .getInputStream().use { it.readBytes().decodeToString().trim() }
                "${server.name}: $ip"
            } catch (e: Exception) {
                "${server.name}: FAILED ${e.message}"
            } finally {
                process.destroy()
                process.waitFor(5, TimeUnit.SECONDS)
                Files.deleteIfExists(path)
            }
        }
        results.forEach { println("SMOKE $it") }
        assertTrue(results.none { "FAILED" in it }, results.joinToString("\n"))
    }
}
