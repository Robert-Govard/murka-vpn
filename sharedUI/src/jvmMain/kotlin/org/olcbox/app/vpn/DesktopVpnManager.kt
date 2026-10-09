package org.olcbox.app.vpn

import org.olcbox.app.data.xray.XrayConfig
import org.olcbox.app.vpn.desktop.XrayProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.DesktopLogFile
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.MacTunController
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var processWatchJob: Job? = null
    private var tunProcessWatchJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var activeDesktopMode: DesktopMode? = null
    private var generation = 0L
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)
    private val macTunController = MacTunController(::addLog)
    private val logFile = DesktopLogFile(DesktopPaths.appDataDir().resolve("murka.log"))

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val socks = _socksProxySettings.value.normalized()
        return SubscriptionFetchProxy(
            host = socks.host,
            port = socks.port,
            username = socks.username,
            password = socks.password
        )
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = _socksProxySettings.value.copy(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            scope.cancel()
        }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        try {
            val ready = CompletableDeferred<Unit>()
            val startupFailure = CompletableDeferred<String>()
            val socksSettings = _socksProxySettings.value.normalized()
            val desktopMode = DesktopMode.from(socksSettings.routingMode)
            activeDesktopMode = desktopMode

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }

            // Cores bind to the physical interface so their own traffic skips the TUN.
            val macPhysicalRoute = if (desktopMode == DesktopMode.MacTun) {
                macTunController.detectPhysicalRoute()
            } else {
                null
            }
            val bindInterface = when (desktopMode) {
                DesktopMode.WindowsTun -> if (location.isXray) windowsTunController.detectPhysicalInterface() else null
                DesktopMode.MacTun -> macPhysicalRoute?.interfaceName
                else -> null
            }

            process = if (location.isXray) {
                startXrayProcess(
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    bindInterface = bindInterface,
                    privileged = desktopMode == DesktopMode.LinuxTun
                )
            } else {
                startOlcRtcProcessWithFallback(
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = true,
                    privileged = desktopMode == DesktopMode.LinuxTun,
                    bindInterface = bindInterface
                )
            }

            val olcRtcProcess = process ?: error("olcRTC process is missing")
            waitForOlcRtcReady(
                process = olcRtcProcess,
                ready = ready,
                startupFailure = startupFailure,
                socksPort = socksSettings.port,
                requestGeneration = requestGeneration
            )

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(socksSettings.port, requestGeneration)
                DesktopMode.WindowsTun -> startWindowsTun(socksSettings.port, requestGeneration)
                DesktopMode.MacTun -> startMacTun(
                    socksSettings = socksSettings,
                    isXray = location.isXray,
                    physical = macPhysicalRoute ?: error("macOS physical route is missing"),
                    requestGeneration = requestGeneration
                )
                DesktopMode.SystemProxy -> startSystemProxy(socksSettings, requestGeneration)
                DesktopMode.LocalSocks -> Unit
            }

            if (!olcRtcProcess.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            startProcessExitWatchers(
                desktopMode = desktopMode,
                olcRtcProcess = olcRtcProcess,
                currentTunProcess = tunProcess,
                requestGeneration = requestGeneration
            )

            setStatus(VpnStatus.Connected)
            addLog(
                when (desktopMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN connected"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN connected"
                    DesktopMode.MacTun -> "Desktop macOS TUN connected"
                    DesktopMode.SystemProxy -> "Desktop proxy connected"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy connected"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            stopDesktopMode(finalStatus = false)

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(hevBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(socksPort: Int, requestGeneration: Long) {
        val tun2SocksBinary = DesktopNativeAssets.resolveWindowsTun2SocksBinary()
        tunProcess = windowsTunController.start(tun2SocksBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("tun2socks process is missing"))
    }

    private suspend fun startMacTun(
        socksSettings: DesktopSocksProxySettings,
        isXray: Boolean,
        physical: MacTunController.PhysicalRoute,
        requestGeneration: Long
    ) {
        tunProcess = macTunController.start(
            hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary(),
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password,
            udpOverTcp = !isXray,
            physical = physical
        )

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("macOS TUN monitor is missing"))
    }

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        proxyController.enable(pacServer.url)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    private enum class DesktopMode {
        LinuxTun,
        WindowsTun,
        MacTun,
        SystemProxy,
        LocalSocks;

        companion object {
            fun from(mode: DesktopRoutingMode): DesktopMode {
                return when (mode.resolveForCurrentPlatform()) {
                    DesktopRoutingMode.Tun -> when (DesktopPaths.os) {
                        DesktopOs.Linux -> LinuxTun
                        DesktopOs.Windows -> WindowsTun
                        DesktopOs.MacOS -> MacTun
                        DesktopOs.Other -> SystemProxy
                    }
                    DesktopRoutingMode.SystemProxy -> SystemProxy
                    DesktopRoutingMode.LocalSocks -> LocalSocks
                    DesktopRoutingMode.Auto -> error("Auto desktop mode was not resolved")
                }
            }
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        bindInterface: String? = null
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        val dnsServer = location.dnsServer.ifBlank { DesktopDnsResolver.current() }
        var lastException: Exception? = null

        addLog("Using DNS server $dnsServer for olcRTC")

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged,
                    dnsServer = dnsServer,
                    bindInterface = bindInterface
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null) {
            cancelProcessJobs()
            return
        }

        setStatus(VpnStatus.Stopping)
        cancelProcessJobs()

        val stoppedMode = activeDesktopMode
        when (stoppedMode) {
            DesktopMode.LinuxTun -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.WindowsTun -> {
                runCatching {
                    windowsTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.MacTun -> {
                runCatching {
                    macTunController.stop(tunProcess)
                }.onFailure {
                    addLog("macOS TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.SystemProxy -> {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
            DesktopMode.LocalSocks,
            null -> Unit
        }

        if (stoppedMode == DesktopMode.SystemProxy) {
            pacServer.stop()
        }
        activeDesktopMode = null

        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(
                when (stoppedMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN stopped"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN stopped"
                    DesktopMode.MacTun -> "Desktop macOS TUN stopped"
                    DesktopMode.SystemProxy -> "Desktop proxy stopped"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy stopped"
                    null -> "Desktop connection stopped"
                }
            )
        }
    }

    private fun cancelProcessJobs() {
        processWatchJob?.cancel()
        processWatchJob = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        dnsServer: String,
        bindInterface: String?
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dnsServer = dnsServer,
            bindInterface = bindInterface
        )
        val configPath = writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, room=${config.id}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        if (logOutput) {
                            val message = "rtc: $line"
                            addLog(message)
                            println(message)
                        }

                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }

                        if (isFatalOlcRtcStartupLine(line)) {
                            startupFailure.complete(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    /** Remnawave location: Xray serves the same local SOCKS port as olcRTC would. */
    private fun startXrayProcess(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        bindInterface: String?,
        privileged: Boolean
    ): Process {
        val binary = DesktopNativeAssets.resolveXrayBinary()
        val assetsDir = DesktopNativeAssets.resolveXrayAssetsDir()
        val prepared = XrayConfig.prepare(
            raw = location.xrayConfig,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            username = socksSettings.username,
            password = socksSettings.password,
            bindInterface = bindInterface
        )
        val configPath = XrayProcess.writeConfig(DesktopPaths.appDataDir().resolve("runtime"), prepared)
        deleteOlcRtcConfig()
        olcRtcConfigPath = configPath

        addLog(
            "Starting Xray ${location.providerName()} ${location.transportName()} for ${location.displayName()}" +
                (bindInterface?.let { " via $it" } ?: "")
        )
        val command = XrayProcess.command(binary, configPath)
        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)
        processBuilder.environment()[XrayProcess.ASSET_ENV] = assetsDir.toString()
        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            deleteOlcRtcConfig()
            throw e
        }

        logJob?.cancel()
        logJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val message = "xray: $line"
                        addLog(message)
                        println(message)
                        if (XrayProcess.isReadyLine(line)) ready.complete(Unit)
                        if (XrayProcess.isFatalLine(line)) startupFailure.complete(line)
                    }
                }
            } catch (_: IOException) {
                // stdout closes when the process stops.
            }
        }
        return startedProcess
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-client-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            try {
                target.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        val message = "tun: $line"
                        addLog(message)
                        println(message)
                    }
                }
            } catch (_: IOException) {
                // TUN stdout may close while the process is being stopped.
            }
        }
    }

    private fun startProcessExitWatchers(
        desktopMode: DesktopMode,
        olcRtcProcess: Process,
        currentTunProcess: Process?,
        requestGeneration: Long
    ) {
        startOlcRtcExitWatcher(olcRtcProcess, requestGeneration)

        when (desktopMode) {
            DesktopMode.LinuxTun,
            DesktopMode.WindowsTun,
            DesktopMode.MacTun -> startTunExitWatcher(
                currentTunProcess ?: error("TUN process is missing"),
                requestGeneration
            )
            DesktopMode.SystemProxy,
            DesktopMode.LocalSocks -> {
                tunProcessWatchJob?.cancel()
                tunProcessWatchJob = null
            }
        }
    }

    private fun startOlcRtcExitWatcher(target: Process, requestGeneration: Long) {
        processWatchJob?.cancel()
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "olcRTC process exited unexpectedly with code $exitCode",
                        errorMessage = "olcRTC exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private fun startTunExitWatcher(target: Process, requestGeneration: Long) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private suspend fun handleUnexpectedProcessExit(
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        stopDesktopMode(finalStatus = false)

        if (requestGeneration == generation) {
            setStatus(VpnStatus.Error(errorMessage))
        }
    }

    private fun waitForProcessExit(target: Process): Int? {
        return try {
            target.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                waitForOlcRtcStartupStability(process, startupFailure, requestGeneration)
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        logFile.append(message)
        _logs.update {
            (it + message).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 5_000
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                    "join room failed" in text ||
                    "get room token" in text && "failed" in text ||
                    "transport connect" in text && "failed" in text
        }
    }
}
