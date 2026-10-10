import Foundation

/// What the app hands the packet tunnel extension: one core and the local SOCKS
/// endpoint that hev-socks5-tunnel forwards the device's traffic to.
struct TunnelOptions: Codable, Equatable {
    enum Core: String, Codable {
        case xray
        case olcrtc
    }

    var core: Core
    var socksPort: Int
    var socksUser: String
    var socksPass: String

    /// Xray: full JSON config with a SOCKS inbound on `socksPort`, server addresses pinned to IPs.
    var xrayConfig: String?

    /// olcRTC (WB Stream room).
    var carrier: String?
    var transport: String?
    var roomId: String?
    var keyHex: String?
    var clientId: String?
    var dnsServer: String?
    var vp8Fps: Int?
    var vp8Batch: Int?

    static let providerKey = "murka.options"

    func providerConfiguration() throws -> [String: Any] {
        [Self.providerKey: try JSONEncoder().encode(self)]
    }

    init(
        core: Core, socksPort: Int, socksUser: String, socksPass: String, xrayConfig: String? = nil,
        carrier: String? = nil, transport: String? = nil, roomId: String? = nil, keyHex: String? = nil,
        clientId: String? = nil, dnsServer: String? = nil, vp8Fps: Int? = nil, vp8Batch: Int? = nil
    ) {
        self.core = core
        self.socksPort = socksPort
        self.socksUser = socksUser
        self.socksPass = socksPass
        self.xrayConfig = xrayConfig
        self.carrier = carrier
        self.transport = transport
        self.roomId = roomId
        self.keyHex = keyHex
        self.clientId = clientId
        self.dnsServer = dnsServer
        self.vp8Fps = vp8Fps
        self.vp8Batch = vp8Batch
    }

    init?(providerConfiguration: [String: Any]?) {
        guard let data = providerConfiguration?[Self.providerKey] as? Data,
              let decoded = try? JSONDecoder().decode(TunnelOptions.self, from: data) else { return nil }
        self = decoded
    }
}

/// App → extension message asking for log lines after a sequence number.
enum TunnelMessage {
    static let logsPrefix = "logs:"

    static func logs(after sequence: Int) -> Data { Data("\(logsPrefix)\(sequence)".utf8) }
}

struct TunnelLogBatch: Codable {
    /// Sequence number to ask for next time.
    var next: Int
    var lines: [String]
}
