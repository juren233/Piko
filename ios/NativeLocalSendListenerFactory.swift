import Network

enum NativeLocalSendListenerFactory {
    static func makeListener() throws -> NWListener {
        if let port = NWEndpoint.Port(rawValue: 53317),
           let listener = try? NWListener(using: .tcp, on: port) {
            return listener
        }
        return try NWListener(using: .tcp)
    }
}
