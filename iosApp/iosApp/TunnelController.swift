import Darwin
import Foundation
import NetworkExtension

/// Drives the MurkaTunnel packet tunnel extension. `start` blocks: never call it on the main thread.
final class TunnelController: @unchecked Sendable {
    private static let extensionBundleSuffix = ".tunnel"
    private static let displayName = "Мурка VPN"
    private static let pollInterval: TimeInterval = 0.2

    private let lock = NSLock()
    private var manager: NETunnelProviderManager?
    private var logTimer: DispatchSourceTimer?
    private var logCursor = 0
    private let logQueue = DispatchQueue(label: "com.murkavpn.app.tunnel-logs")
    private let onLog: @Sendable (String) -> Void

    init(onLog: @escaping @Sendable (String) -> Void) {
        self.onLog = onLog
        // Pick up a tunnel that is still running from an earlier app launch.
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, _ in
            guard let self, let existing = managers?.first else { return }
            self.lock.lock()
            if self.manager == nil { self.manager = existing }
            self.lock.unlock()
            if Self.isActive(existing.connection.status) { self.startLogPolling() }
        }
    }

    func start(_ options: TunnelOptions, timeout: TimeInterval) throws {
        let manager = try loadManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = (Bundle.main.bundleIdentifier ?? "") + Self.extensionBundleSuffix
        proto.serverAddress = Self.displayName
        proto.providerConfiguration = try options.providerConfiguration()
        proto.disconnectOnSleep = false
        manager.protocolConfiguration = proto
        manager.localizedDescription = Self.displayName
        manager.isEnabled = true
        do {
            try Self.wait { manager.saveToPreferences(completionHandler: $0) }
        } catch {
            throw TunnelError("Разрешите Мурке добавить VPN-конфигурацию (\(error.localizedDescription))")
        }
        try Self.wait { manager.loadFromPreferences(completionHandler: $0) }

        let connection = manager.connection
        if connection.status != .disconnected && connection.status != .invalid {
            connection.stopVPNTunnel()
            _ = Self.waitFor(connection, until: Date().addingTimeInterval(10)) { $0 == .disconnected }
        }
        logQueue.sync { logCursor = 0 }
        try connection.startVPNTunnel()

        var sawStarting = false
        let reached = Self.waitFor(connection, until: Date().addingTimeInterval(timeout)) { status in
            if status == .connecting || status == .reasserting { sawStarting = true }
            return status == .connected || (sawStarting && (status == .disconnected || status == .invalid))
        }
        if reached == .connected {
            startLogPolling()
            return
        }
        connection.stopVPNTunnel()
        if reached == nil { throw TunnelError("VPN не поднялся за \(Int(timeout)) с") }
        throw Self.lastDisconnectError(connection) ?? TunnelError("VPN отключился при запуске")
    }

    /// Non-blocking: safe on the main thread.
    func stop() {
        lock.lock()
        let manager = self.manager
        lock.unlock()
        stopLogPolling()
        manager?.connection.stopVPNTunnel()
    }

    var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let status = manager?.connection.status else { return false }
        return Self.isActive(status)
    }

    // MARK: - Logs from the extension

    private func startLogPolling() {
        logQueue.async { [weak self] in
            guard let self, self.logTimer == nil else { return }
            let timer = DispatchSource.makeTimerSource(queue: self.logQueue)
            timer.schedule(deadline: .now(), repeating: .seconds(1))
            timer.setEventHandler { [weak self] in self?.pollLogs() }
            timer.resume()
            self.logTimer = timer
        }
    }

    private func stopLogPolling() {
        logQueue.async {
            // One last fetch so the reason for a stop reaches the journal.
            self.pollLogs()
            self.logTimer?.cancel()
            self.logTimer = nil
        }
    }

    private func pollLogs() {
        lock.lock()
        let session = manager?.connection as? NETunnelProviderSession
        lock.unlock()
        guard let session, session.status != .disconnected, session.status != .invalid else { return }
        let message = TunnelMessage.logs(after: logCursor)
        try? session.sendProviderMessage(message) { [weak self] data in
            guard let self, let data, let batch = try? JSONDecoder().decode(TunnelLogBatch.self, from: data) else { return }
            self.logQueue.async {
                self.logCursor = batch.next
                batch.lines.forEach(self.onLog)
            }
        }
    }

    // MARK: - Helpers

    private func loadManager() throws -> NETunnelProviderManager {
        let box = Box<[NETunnelProviderManager]>([])
        try Self.wait { done in
            NETunnelProviderManager.loadAllFromPreferences { managers, error in
                box.value = managers ?? []
                done(error)
            }
        }
        let manager = box.value.first ?? NETunnelProviderManager()
        lock.lock()
        self.manager = manager
        lock.unlock()
        return manager
    }

    private static func isActive(_ status: NEVPNStatus) -> Bool {
        status == .connected || status == .connecting || status == .reasserting
    }

    /// Polls the connection status until `done` accepts it; nil on timeout.
    private static func waitFor(
        _ connection: NEVPNConnection, until deadline: Date, _ done: (NEVPNStatus) -> Bool
    ) -> NEVPNStatus? {
        while Date() < deadline {
            let status = connection.status
            if done(status) { return status }
            Thread.sleep(forTimeInterval: pollInterval)
        }
        return nil
    }

    private static func wait(_ body: (@escaping @Sendable (Error?) -> Void) -> Void) throws {
        let semaphore = DispatchSemaphore(value: 0)
        let box = Box<Error?>(nil)
        body { error in
            box.value = error
            semaphore.signal()
        }
        semaphore.wait()
        if let error = box.value { throw error }
    }

    private static func lastDisconnectError(_ connection: NEVPNConnection) -> Error? {
        guard #available(iOS 16.0, *) else { return nil }
        let semaphore = DispatchSemaphore(value: 0)
        let box = Box<Error?>(nil)
        connection.fetchLastDisconnectError { error in
            box.value = error
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 3)
        return box.value
    }

    /// IPv4 address for a host, resolved before the tunnel takes over DNS.
    static func resolveIPv4(_ host: String) -> String? {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return nil }
        defer { freeaddrinfo(result) }
        guard let address = first.pointee.ai_addr else { return nil }
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let ok = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin -> Bool in
            var addr = sin.pointee.sin_addr
            return inet_ntop(AF_INET, &addr, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil
        }
        return ok ? buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) } : nil
    }
}

struct TunnelError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}
