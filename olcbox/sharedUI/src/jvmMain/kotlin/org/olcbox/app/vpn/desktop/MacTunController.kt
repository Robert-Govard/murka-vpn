package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * System-wide VPN on macOS without a Network Extension: hev-socks5-tunnel on a utun
 * with 0/1 + 128/1 routes, started once per connection behind the standard admin
 * password prompt. The cores bind their sockets to the physical interface, so their
 * own traffic stays outside the tunnel.
 *
 * Nothing user-writable is ever executed as root: the root helper copies hev, its
 * config and scripts into a fresh root-owned directory first. The helper tears the
 * tunnel down when [STOP_FILE_NAME] appears or the app process dies; routes vanish
 * together with the utun, so a crash cannot leave the Mac without internet.
 */
internal class MacTunController(
    private val addLog: (String) -> Unit
) {
    suspend fun start(
        hevBinary: Path,
        socksPort: Int,
        socksUsername: String,
        socksPassword: String,
        udpOverTcp: Boolean,
        physical: PhysicalRoute
    ): Process {
        val dir = DesktopPaths.appDataDir().resolve("mac-tun")
        Files.createDirectories(dir)
        val stopFile = dir.resolve(STOP_FILE_NAME)
        Files.deleteIfExists(stopFile)
        val config = dir.resolve("hev.yml")
        Files.writeString(config, configContent(socksPort, socksUsername, socksPassword, udpOverTcp))
        val helper = dir.resolve("helper.sh")
        Files.writeString(helper, helperScript())
        val startedAt = System.currentTimeMillis()

        runAdminCommand(
            launchCommand(
                helper = helper,
                hevBinary = hevBinary,
                config = config,
                appPid = ProcessHandle.current().pid(),
                stopFile = stopFile,
                physical = physical
            )
        )

        val ifName = try {
            waitForTunnel(startedAt)
        } catch (e: Exception) {
            Files.writeString(stopFile, "")
            throw e
        }
        addLog("macOS TUN connected on $ifName")
        return ProcessBuilder("/bin/sh", "-c", monitorScript(ifName))
            .redirectErrorStream(true)
            .start()
    }

    suspend fun stop(monitor: Process?) = withContext(Dispatchers.IO) {
        val ifName = readIfName()
        Files.writeString(
            DesktopPaths.appDataDir().resolve("mac-tun").also { Files.createDirectories(it) }
                .resolve(STOP_FILE_NAME),
            ""
        )
        if (ifName != null) {
            val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && interfaceExists(ifName)) {
                delay(POLL_MS)
            }
        }
        monitor?.let {
            it.toHandle().descendants().forEach { child -> child.destroy() }
            it.destroy()
            it.waitFor(1, TimeUnit.SECONDS)
        }
    }

    /**
     * Interface and gateway of the IPv4 default route outside any tunnel. Read it before
     * our routes exist; another VPN client may own the first default route.
     */
    suspend fun detectPhysicalRoute(): PhysicalRoute = withContext(Dispatchers.IO) {
        val output = runCommand(listOf("/usr/sbin/netstat", "-rn", "-f", "inet"))
        physicalRoute(output) ?: error("No physical IPv4 default route found")
    }

    private suspend fun runAdminCommand(shellCommand: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "/usr/bin/osascript", "-e", adminAppleScript(shellCommand, PASSWORD_PROMPT)
        ).redirectErrorStream(true).start()
        // Covers the time to type the password; never leave the app spinning forever.
        if (!process.waitFor(ADMIN_PROMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Не дождались ввода пароля администратора — VPN не включён")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) {
            if ("-128" in output) error("Пароль администратора не введён — VPN не включён")
            error("Не удалось включить VPN: $output")
        }
    }

    private suspend fun waitForTunnel(startedAt: Long): String {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ifName = readIfName(newerThan = startedAt)
            if (ifName != null && routesInstalled(ifName)) return ifName
            delay(POLL_MS)
        }
        val log = runCatching { Files.readString(Path.of(LOG_PATH)).takeLast(500) }.getOrDefault("")
        error("macOS TUN was not ready in time" + if (log.isNotBlank()) ": $log" else "")
    }

    private fun readIfName(newerThan: Long = 0L): String? {
        val file = Path.of(IFNAME_PATH)
        return runCatching {
            if (Files.getLastModifiedTime(file).toMillis() < newerThan - CLOCK_SLACK_MS) return null
            Files.readString(file).trim().takeIf { UTUN_NAME.matches(it) }
        }.getOrNull()
    }

    private suspend fun routesInstalled(ifName: String): Boolean = withContext(Dispatchers.IO) {
        hasTunnelRoutes(runCatching { runCommand(listOf("/usr/sbin/netstat", "-rn", "-f", "inet")) }.getOrDefault(""), ifName)
    }

    private suspend fun interfaceExists(ifName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("/sbin/ifconfig", ifName).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun runCommand(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            error("${command.first()} failed: $output")
        }
        return output
    }

    /** Root-owned locations used by [helperScript]; overridable so tests can run it unprivileged. */
    internal data class HelperPaths(
        val pid: String = PID_PATH,
        val ifName: String = IFNAME_PATH,
        val log: String = LOG_PATH,
        val route: String = "/sbin/route",
        val tmpDir: String = "/tmp"
    )

    internal data class PhysicalRoute(val interfaceName: String, val gateway: String)

    internal companion object {
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val STOP_FILE_NAME = "stop"
        const val IFNAME_PATH = "/var/run/murka-tun.ifname"
        const val PID_PATH = "/var/run/murka-tun.pid"
        const val LOG_PATH = "/var/log/murka-tun.log"
        const val DIR_PLACEHOLDER = "@MURKA_TUN_DIR@"
        const val PASSWORD_PROMPT = "Мурка VPN включает VPN для всех приложений."
        private const val ADMIN_PROMPT_TIMEOUT_MS = 120_000L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 200L
        private const val CLOCK_SLACK_MS = 2_000L
        private val UTUN_NAME = Regex("utun\\d+")
        private val TUNNEL_PREFIXES = listOf("utun", "ipsec", "ppp", "gif", "stf", "tun", "tap")

        /** First `default` route in `netstat -rn -f inet` that has a gateway and is not a tunnel. */
        fun physicalRoute(netstatOutput: String): PhysicalRoute? = netstatOutput.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 4 && it[0] == "default" }
            .filter { cols -> cols[1].matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
            .map { cols -> PhysicalRoute(cols[3], cols[1]) }
            .firstOrNull { route -> TUNNEL_PREFIXES.none { route.interfaceName.startsWith(it) } }

        fun hasTunnelRoutes(netstatOutput: String, ifName: String): Boolean {
            val routes = netstatOutput.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size >= 4 && it[3] == ifName }
                .map { it[0] }
                .toSet()
            return "0/1" in routes && "128.0/1" in routes
        }

        fun configContent(
            socksPort: Int,
            socksUsername: String,
            socksPassword: String,
            udpOverTcp: Boolean
        ): String = buildString {
            appendLine("tunnel:")
            appendLine("  name: utun")
            appendLine("  mtu: $TUN_MTU")
            appendLine("  ipv4: $TUN_IPV4_ADDRESS")
            appendLine("  post-up-script: $DIR_PLACEHOLDER/up.sh")
            appendLine()
            appendLine("socks5:")
            appendLine("  address: ${PacServer.LOCAL_SOCKS_HOST}")
            appendLine("  port: $socksPort")
            // Xray speaks standard SOCKS5 UDP; olcRTC wants hev's UDP-over-TCP.
            appendLine("  udp: '${if (udpOverTcp) "tcp" else "udp"}'")
            if (socksUsername.isNotBlank() && socksPassword.isNotBlank()) {
                appendLine("  username: '${socksUsername.replace("'", "''")}'")
                appendLine("  password: '${socksPassword.replace("'", "''")}'")
            }
            appendLine()
            appendLine("misc:")
            appendLine("  task-stack-size: 86016")
            appendLine("  tcp-buffer-size: 65536")
            appendLine("  max-session-count: 1200")
            appendLine("  connect-timeout: 10000")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-file: stderr")
            appendLine("  log-level: warn")
        }.trimEnd()

        /** Runs as root. Args: hev binary, hev config, app pid, stop file, physical interface, its gateway. */
        fun helperScript(paths: HelperPaths = HelperPaths()): String = """
            #!/bin/sh
            hev_src="${'$'}1"; conf_src="${'$'}2"; app_pid="${'$'}3"; stop_file="${'$'}4"; phys_if="${'$'}5"; phys_gw="${'$'}6"
            echo "${'$'}phys_if" | grep -Eq '^[a-z]+[0-9]+${'$'}' || exit 2
            echo "${'$'}phys_gw" | grep -Eq '^[0-9]{1,3}([.][0-9]{1,3}){3}${'$'}' || exit 2
            # A previous tunnel (reconnect, crashed app): stop its hev and let its helper clean up.
            if [ -f ${paths.pid} ]; then kill "${'$'}(cat ${paths.pid})" 2>/dev/null; sleep 2; fi
            rm -f ${paths.ifName}
            dir=${'$'}(mktemp -d ${paths.tmpDir}/murka-tun.XXXXXX) || exit 1
            cp "${'$'}hev_src" "${'$'}dir/hev" && cp "${'$'}conf_src" "${'$'}dir/hev.yml" || exit 1
            sed -i '' "s#$DIR_PLACEHOLDER#${'$'}dir#" "${'$'}dir/hev.yml"
            cat > "${'$'}dir/up.sh" <<'UP'
            #!/bin/sh
            ${paths.route} -q -n add -inet 0.0.0.0/1 -interface "${'$'}1"
            ${paths.route} -q -n add -inet 128.0.0.0/1 -interface "${'$'}1"
            echo "${'$'}1" > ${paths.ifName}
            UP
            chmod 700 "${'$'}dir/hev" "${'$'}dir/up.sh"
            # Xray and olcRTC pin their sockets to the physical interface (IP_BOUND_IF). macOS
            # keeps a scoped default only for non-primary interfaces, so without this they get
            # "network is unreachable" once the utun holds 0/1 + 128/1.
            added_scope=0
            if ! ${paths.route} -n get -ifscope "${'$'}phys_if" default > /dev/null 2>&1; then
              ${paths.route} -q -n add -ifscope "${'$'}phys_if" default "${'$'}phys_gw" && added_scope=1
            fi
            "${'$'}dir/hev" "${'$'}dir/hev.yml" > ${paths.log} 2>&1 &
            hev_pid=${'$'}!
            echo "${'$'}hev_pid" > ${paths.pid}
            chmod 644 ${paths.log}
            while kill -0 "${'$'}app_pid" 2>/dev/null && kill -0 "${'$'}hev_pid" 2>/dev/null && [ ! -e "${'$'}stop_file" ]; do
              sleep 1
            done
            kill "${'$'}hev_pid" 2>/dev/null
            for _ in 1 2 3; do kill -0 "${'$'}hev_pid" 2>/dev/null || break; sleep 1; done
            kill -9 "${'$'}hev_pid" 2>/dev/null
            if [ "${'$'}added_scope" = 1 ]; then ${paths.route} -q -n delete -ifscope "${'$'}phys_if" default; fi
            rm -rf "${'$'}dir"
            if [ "${'$'}(cat ${paths.pid} 2>/dev/null)" = "${'$'}hev_pid" ]; then rm -f ${paths.ifName} ${paths.pid}; fi
        """.trimIndent() + "\n"

        /**
         * Shell command run with admin rights: copy the helper out of reach of the user and
         * detach it. osascript returns only once no process holds its stdin/stdout/stderr;
         * the braces keep `&` on the helper alone, not on the whole `&&` chain.
         */
        fun launchCommand(
            helper: Path,
            hevBinary: Path,
            config: Path,
            appPid: Long,
            stopFile: Path,
            physical: PhysicalRoute
        ): String {
            val args = listOf(hevBinary, config).joinToString(" ") { shellQuote(it.toString()) } +
                " $appPid " + listOf(stopFile.toString(), physical.interfaceName, physical.gateway)
                    .joinToString(" ") { shellQuote(it) }
            return "d=\$(mktemp -d /tmp/murka-helper.XXXXXX) && cp ${shellQuote(helper.toString())} \"\$d/h.sh\" && " +
                "chmod 700 \"\$d/h.sh\" && { (/bin/sh \"\$d/h.sh\" $args; rm -rf \"\$d\") < /dev/null > /dev/null 2>&1 & }"
        }

        fun adminAppleScript(shellCommand: String, prompt: String): String =
            "do shell script ${appleScriptString(shellCommand)} with prompt ${appleScriptString(prompt)} " +
                "with administrator privileges"

        /** Exits when the utun disappears; meanwhile streams the hev log for the app log view. */
        fun monitorScript(ifName: String): String =
            "tail -n 0 -F $LOG_PATH 2>/dev/null & t=\$!; " +
                "while /sbin/ifconfig $ifName > /dev/null 2>&1; do sleep 1; done; kill \$t"

        private fun appleScriptString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
