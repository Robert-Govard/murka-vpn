import Foundation
import HevSocks5Tunnel
import Network
import NetworkExtension
import OlcRtcMobile
import os

/// System VPN for iPhone. Runs one core (Xray for regular servers, olcRTC for WB
/// rooms) with a local SOCKS inbound, and hev-socks5-tunnel feeding the utun into it.
/// The extension's own sockets bypass the tunnel, so the cores need no protection.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private static let mtu = 1500
    private static let ipv4Address = "198.18.0.1"
    private static let ipv6Address = "fd8a:88::88"
    private static let xrayDnsServer = "1.1.1.1"
    private static let mapDnsAddress = "10.0.88.53"
    /// The extension is killed past ~50 MB; keep the Go heap well below that.
    private static let goMemoryLimit: Int64 = 28 << 20
    private static let olcReadyTimeoutMillis = 60_000
    private static let watchdogSeconds = 5
    private static let maxRestartDelaySeconds = 30

    private let queue = DispatchQueue(label: "com.murkavpn.tunnel")
    private let log = TunnelLog()
    private var options: TunnelOptions?
    private var xray: XraymobileRuntime?
    private var olc: MobileRuntime?
    private var hevThread: Thread?
    private var watchdog: DispatchSourceTimer?
    private var pathMonitor: NWPathMonitor?
    private var lastPathKey: String?
    private var restartAttempt = 0
    private var nextRestartAt = Date.distantPast
    private var ticks = 0
    private var stopping = false

    override func startTunnel(options _: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let configuration = (protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration
        guard let options = TunnelOptions(providerConfiguration: configuration) else {
            completionHandler(TunnelError("Нет настроек сервера: откройте Мурку и нажмите «СТАРТ»"))
            return
        }
        XraymobileSetMemoryLimit(Self.goMemoryLimit)
        queue.async {
            self.options = options
            self.stopping = false
            self.log.add("Starting \(options.core.rawValue), \(Self.memoryReport())")
            do {
                try self.startCore(options)
            } catch {
                self.log.add("Core start failed: \(error.localizedDescription)")
                self.stopCore()
                completionHandler(error)
                return
            }
            self.setTunnelNetworkSettings(Self.networkSettings(for: options)) { error in
                self.queue.async {
                    if let error {
                        self.log.add("Network settings failed: \(error.localizedDescription)")
                        self.stopCore()
                        completionHandler(error)
                        return
                    }
                    let fd = XraymobileTunFD()
                    guard fd >= 0 else {
                        self.stopCore()
                        completionHandler(TunnelError("Не найден интерфейс VPN (utun)"))
                        return
                    }
                    self.startHev(fd: Int32(fd), options: options)
                    self.startMonitoring()
                    self.log.add("VPN up, \(Self.memoryReport())")
                    completionHandler(nil)
                }
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        queue.async {
            self.log.add("Stopping (reason \(reason.rawValue))")
            self.stopping = true
            self.watchdog?.cancel()
            self.watchdog = nil
            self.pathMonitor?.cancel()
            self.pathMonitor = nil
            hev_socks5_tunnel_quit()
            self.hevThread = nil
            self.stopCore()
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let text = String(data: messageData, encoding: .utf8),
              text.hasPrefix(TunnelMessage.logsPrefix),
              let after = Int(text.dropFirst(TunnelMessage.logsPrefix.count)) else {
            completionHandler?(nil)
            return
        }
        completionHandler?(try? JSONEncoder().encode(log.batch(after: after)))
    }

    // MARK: - Cores (on `queue`)

    private func startCore(_ options: TunnelOptions) throws {
        switch options.core {
        case .xray:
            XraymobileSetAssetDir(Self.xrayAssetDir())
            guard let runtime = XraymobileNew() else { throw TunnelError("Xray недоступен") }
            try runtime.start(options.xrayConfig ?? "")
            xray = runtime
            log.add("Xray \(XraymobileVersion()) started")
        case .olcrtc:
            olc = try startOlc(options)
        }
    }

    private func startOlc(_ options: TunnelOptions) throws -> MobileRuntime {
        guard let runtime = MobileNew() else { throw TunnelError("olcRTC недоступен") }
        runtime.setLogWriter(TunnelLogWriter(log: log))
        try runtime.setProvider(options.carrier ?? "")
        try runtime.setTransport(options.transport ?? "")
        try runtime.setRoom(options.roomId ?? "")
        try runtime.setKey(options.keyHex ?? "")
        runtime.setDeviceID(options.clientId ?? "")
        try runtime.setDNS(options.dnsServer ?? "1.1.1.1:53")
        try runtime.setSocksListenHost("127.0.0.1")
        try runtime.setSocksPort(options.socksPort)
        try runtime.setSocksCredentials(options.socksUser, password: options.socksPass)
        try runtime.setVP8Options(options.vp8Fps ?? 0, batchSize: options.vp8Batch ?? 0)
        try runtime.start()
        do {
            try runtime.waitReady(Self.olcReadyTimeoutMillis)
        } catch {
            try? runtime.stop(1)
            throw error
        }
        log.add("olcRTC room ready")
        return runtime
    }

    private func stopCore() {
        if let runtime = xray {
            xray = nil
            try? runtime.stop()
        }
        if let runtime = olc {
            olc = nil
            try? runtime.stop(2_000)
        }
        XraymobileFreeOSMemory()
    }

    private func coreIsRunning() -> Bool {
        if let xray { return xray.isRunning() }
        if let olc { return olc.state() == "running" }
        return false
    }

    /// Rebuilds the core behind the same SOCKS endpoint; hev and the utun stay up.
    private func restartCore(_ why: String) {
        guard !stopping, let options else { return }
        let now = Date()
        guard now >= nextRestartAt else { return }
        log.add("Restarting \(options.core.rawValue): \(why)")
        stopCore()
        do {
            try startCore(options)
            restartAttempt = 0
            nextRestartAt = .distantPast
        } catch {
            let delay = min(2 << min(restartAttempt, 4), Self.maxRestartDelaySeconds)
            restartAttempt += 1
            nextRestartAt = now.addingTimeInterval(TimeInterval(delay))
            log.add("Restart failed (\(error.localizedDescription)), next try in \(delay) s")
        }
    }

    // MARK: - Monitoring (on `queue`)

    private func startMonitoring() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .seconds(Self.watchdogSeconds), repeating: .seconds(Self.watchdogSeconds))
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        watchdog = timer

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in self?.pathChanged(path) }
        monitor.start(queue: queue)
        pathMonitor = monitor
    }

    private func tick() {
        guard !stopping else { return }
        ticks += 1
        if ticks % 60 == 0 { log.add(Self.memoryReport()) }
        if !coreIsRunning() { restartCore("core stopped") }
    }

    /// WebRTC does not migrate between networks, so a new Wi-Fi/cellular path needs a new room session.
    private func pathChanged(_ path: Network.NWPath) {
        guard path.status == .satisfied else { return }
        let key = path.availableInterfaces.filter { $0.type != .other }.map(\.name).joined(separator: ",")
        defer { lastPathKey = key }
        guard let previous = lastPathKey, previous != key, options?.core == .olcrtc else { return }
        log.add("Network changed (\(previous) → \(key))")
        nextRestartAt = .distantPast
        restartCore("network changed")
    }

    // MARK: - hev-socks5-tunnel

    private func startHev(fd: Int32, options: TunnelOptions) {
        let config = Array(Self.hevConfig(options).utf8)
        let log = self.log
        let thread = Thread {
            let code = config.withUnsafeBufferPointer { buffer in
                hev_socks5_tunnel_main_from_str(buffer.baseAddress, UInt32(buffer.count), fd)
            }
            log.add("hev-socks5-tunnel exited (\(code))")
        }
        thread.name = "hev-socks5-tunnel"
        thread.start()
        hevThread = thread
    }

    static func hevConfig(_ options: TunnelOptions) -> String {
        func quoted(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "''") + "'" }
        var lines = [
            "tunnel:",
            "  mtu: \(mtu)",
            "socks5:",
            "  address: 127.0.0.1",
            "  port: \(options.socksPort)",
            // Xray speaks standard SOCKS5 UDP; olcRTC wants hev's UDP-over-TCP.
            "  udp: '\(options.core == .xray ? "udp" : "tcp")'",
        ]
        if !options.socksUser.isEmpty && !options.socksPass.isEmpty {
            lines += ["  username: \(quoted(options.socksUser))", "  password: \(quoted(options.socksPass))"]
        }
        if options.core == .olcrtc {
            // olcRTC's SOCKS has CONNECT only, so DNS is answered with fake IPs that map back to names.
            lines += [
                "mapdns:",
                "  address: \(mapDnsAddress)",
                "  port: 53",
                "  network: 100.64.0.0",
                "  netmask: 255.192.0.0",
                "  cache-size: 10000",
            ]
        }
        lines += [
            "misc:",
            "  task-stack-size: 24576",
            "  tcp-buffer-size: 16384",
            "  max-session-count: 800",
            "  connect-timeout: 10000",
            "  tcp-read-write-timeout: 300000",
            "  udp-read-write-timeout: 60000",
            "  log-file: stderr",
            "  log-level: warn",
        ]
        return lines.joined(separator: "\n") + "\n"
    }

    static func networkSettings(for options: TunnelOptions) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = NSNumber(value: mtu)
        let ipv4 = NEIPv4Settings(addresses: [ipv4Address], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        // Route IPv6 in as well so nothing leaks past the tunnel.
        let ipv6 = NEIPv6Settings(addresses: [ipv6Address], networkPrefixLengths: [64])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = ipv6
        let dns = NEDNSSettings(servers: [options.core == .xray ? xrayDnsServer : mapDnsAddress])
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        return settings
    }

    /// Geo files ship once, in the containing app's bundle (extension: App.app/PlugIns/X.appex).
    private static func xrayAssetDir() -> String {
        let appBundle = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        let shared = appBundle.appendingPathComponent("xray")
        if FileManager.default.fileExists(atPath: shared.appendingPathComponent("geosite.dat").path) {
            return shared.path
        }
        return Bundle.main.bundleURL.appendingPathComponent("xray").path
    }

    private static func memoryReport() -> String {
        "memory available \(os_proc_available_memory() >> 20) MB"
    }
}

struct TunnelError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Ring buffer of log lines the app reads with `TunnelMessage.logs(after:)`.
final class TunnelLog: @unchecked Sendable {
    private static let capacity = 500
    private let lock = NSLock()
    private var lines: [String] = []
    private var next = 0
    private let osLog = Logger(subsystem: "com.murkavpn.tunnel", category: "tunnel")

    func add(_ line: String) {
        osLog.info("\(line, privacy: .public)")
        lock.lock()
        defer { lock.unlock() }
        lines.append(line)
        next += 1
        if lines.count > Self.capacity { lines.removeFirst(lines.count - Self.capacity) }
    }

    func batch(after sequence: Int) -> TunnelLogBatch {
        lock.lock()
        defer { lock.unlock() }
        let first = next - lines.count
        let start = max(sequence, first)
        let slice = start < next ? Array(lines[(start - first)...]) : []
        return TunnelLogBatch(next: next, lines: slice)
    }
}

private final class TunnelLogWriter: NSObject, MobileLogWriterProtocol {
    private let log: TunnelLog

    init(log: TunnelLog) { self.log = log }

    func writeLog(_ message: String?) {
        if let message, !message.isEmpty { log.add("rtc: \(message)") }
    }
}
