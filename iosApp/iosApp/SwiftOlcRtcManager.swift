import Darwin
import Foundation
import OlcRtcMobile
import SharedUI

final class SwiftOlcRtcManager: NSObject, @unchecked Sendable, IosOlcRtcBridge {
    /// How long the extension may take to join a WB room or start Xray.
    private static let startTimeout: TimeInterval = 75

    private var logWriter: IosLogWriter?
    private static let assetsReady: Void = {
        if let resources = Bundle.main.resourcePath {
            XraymobileSetAssetDir(resources + "/xray")
        }
    }()
    private let logLock = NSLock()
    private lazy var tunnel = TunnelController { [weak self] in self?.log($0) }

    override init() {
        super.init()
        _ = tunnel // load the VPN profile now, so a tunnel left running is seen at launch
    }

    func setLogWriter(writer: IosLogWriter?) {
        logLock.lock()
        defer { logLock.unlock() }
        logWriter = writer
    }

    /// WB room: the packet tunnel extension runs olcRTC and routes the whole device through it.
    func start(request: IosOlcRtcStartRequest) -> IosBridgeResult {
        startTunnel(TunnelOptions(
            core: .olcrtc,
            socksPort: Int(request.socksPort),
            socksUser: request.socksUser,
            socksPass: request.socksPass,
            carrier: request.carrierName,
            transport: request.transportName,
            roomId: request.roomId,
            keyHex: request.keyHex,
            clientId: request.clientId,
            dnsServer: request.dnsServer,
            vp8Fps: Int(request.vp8Fps),
            vp8Batch: Int(request.vp8BatchSize)
        ))
    }

    func startXray(configJson: String, socksPort: Int32, socksUser: String, socksPass: String) -> IosBridgeResult {
        startTunnel(TunnelOptions(
            core: .xray,
            socksPort: Int(socksPort),
            socksUser: socksUser,
            socksPass: socksPass,
            xrayConfig: configJson
        ))
    }

    private func startTunnel(_ options: TunnelOptions) -> IosBridgeResult {
        do {
            try tunnel.start(options, timeout: Self.startTimeout)
            return IosBridgeResult(success: true, message: nil)
        } catch {
            return IosBridgeResult(success: false, message: error.localizedDescription)
        }
    }

    func stop() {
        tunnel.stop()
    }

    func isRunning() -> Bool {
        tunnel.isRunning
    }

    func resolveIpv4(host: String) -> String? {
        TunnelController.resolveIPv4(host)
    }

    func checkXray(configJson: String, url: String, timeoutMillis: Int64) -> IosLongResult {
        _ = SwiftOlcRtcManager.assetsReady
        // gomobile exports Check as a C function: Swift does not map its NSError** to throws.
        var value: Int = -1
        var error: NSError?
        if XraymobileCheck(configJson, url, Int(timeoutMillis), &value, &error) {
            return IosLongResult(success: true, valueMillis: Int64(value), message: nil)
        }
        return IosLongResult(success: false, valueMillis: -1, message: error?.localizedDescription ?? "Xray check failed")
    }

    func ping(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        do {
            var value: Int64 = -1
            try MobileNew()!.ping(
                request.carrierName,
                transportName: request.transportName,
                roomID: request.roomId,
                deviceID: request.clientId,
                keyHex: request.keyHex,
                socksPort: port,
                timeoutMillis: Int(request.timeoutMillis),
                pingURL: request.pingUrl,
                vp8FPS: Int(request.vp8Fps),
                vp8BatchSize: Int(request.vp8BatchSize),
                ret0_: &value
            )
            return IosLongResult(success: true, valueMillis: value, message: nil)
        } catch {
            return IosLongResult(success: false, valueMillis: -1, message: error.localizedDescription)
        }
    }

    func check(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        do {
            var value: Int64 = -1
            try MobileNew()!.check(
                request.carrierName,
                transportName: request.transportName,
                roomID: request.roomId,
                deviceID: request.clientId,
                keyHex: request.keyHex,
                socksPort: port,
                timeoutMillis: Int(request.timeoutMillis),
                vp8FPS: Int(request.vp8Fps),
                vp8BatchSize: Int(request.vp8BatchSize),
                ret0_: &value
            )
            return IosLongResult(success: true, valueMillis: value, message: nil)
        } catch {
            return IosLongResult(success: false, valueMillis: -1, message: error.localizedDescription)
        }
    }

    private func allocateLocalPort() -> Int {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let bindResult = withUnsafePointer(to: &addr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { return -1 }

        var boundAddr = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &boundAddr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard nameResult == 0 else { return -1 }

        return Int(UInt16(bigEndian: boundAddr.sin_port))
    }

    private func log(_ message: String) {
        logLock.lock()
        let writer = logWriter
        logLock.unlock()
        writer?.writeLog(message: message)
    }
}
