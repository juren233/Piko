import Foundation
import Network

final class NativeLanDiscoveryService {
    private let queue: DispatchQueue
    private let nickname: () -> NativeDeviceNickname
    private let localInfo: (Int) -> NativeLocalSendDeviceInfo
    private let onIncomingConnection: (NWConnection) -> Void
    private let onDevicesChanged: ([NativeSendDevice]) -> Void
    private let onDiscoveryFailed: () -> Void
    private var listener: NWListener?
    private var multicastDiscovery: NativeLocalSendMulticast?
    private var browser: NWBrowser?
    private var bonjourDevicesById: [String: NativeSendDevice] = [:]
    private var multicastDevicesById: [String: NativeSendDevice] = [:]

    init(
        queue: DispatchQueue,
        nickname: @escaping () -> NativeDeviceNickname,
        localInfo: @escaping (Int) -> NativeLocalSendDeviceInfo,
        onIncomingConnection: @escaping (NWConnection) -> Void,
        onDevicesChanged: @escaping ([NativeSendDevice]) -> Void,
        onDiscoveryFailed: @escaping () -> Void
    ) {
        self.queue = queue
        self.nickname = nickname
        self.localInfo = localInfo
        self.onIncomingConnection = onIncomingConnection
        self.onDevicesChanged = onDevicesChanged
        self.onDiscoveryFailed = onDiscoveryFailed
    }

    var listenerPort: Int {
        Int(listener?.port?.rawValue ?? 53317)
    }

    var isDiscoveryActive: Bool {
        browser != nil
    }

    func startPresence() -> Bool {
        guard listener == nil else {
            return true
        }

        do {
            let listener = try NativeLocalSendListenerFactory.makeListener()
            listener.service = NWListener.Service(
                name: currentServiceName,
                type: "_piko-share._tcp",
                domain: nil,
                txtRecord: Self.txtRecordData(for: nickname())
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.onIncomingConnection(connection)
            }
            listener.start(queue: queue)
            self.listener = listener
            startLocalSendMulticast()
            return true
        } catch {
            return false
        }
    }

    func startDiscovery() {
        browser?.cancel()
        bonjourDevicesById = [:]

        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_piko-share._tcp", domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else {
                return
            }

            self.bonjourDevicesById = Dictionary(
                uniqueKeysWithValues: results.compactMap { result -> (String, NativeSendDevice)? in
                    guard case let .service(name, type, domain, _) = result.endpoint else {
                        return nil
                    }
                    let serviceNickname = Self.nickname(fromServiceName: name, metadata: result.metadata)
                    let currentNickname = self.nickname()
                    guard name != self.currentServiceName, serviceNickname.fingerprint != currentNickname.fingerprint else {
                        return nil
                    }
                    let device = NativeSendDevice(
                        id: "\(name).\(type).\(domain)",
                        name: serviceNickname.title,
                        subtitle: serviceNickname.code,
                        endpoint: result.endpoint
                    )
                    return (device.id, device)
                }
            )
            self.publishDevices()
        }
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                self?.bonjourDevicesById = [:]
                DispatchQueue.main.async {
                    self?.onDiscoveryFailed()
                }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
        multicastDiscovery?.announce()
    }

    func restartAfterNicknameChange() {
        let hadListener = listener != nil
        let hadBrowser = browser != nil
        listener?.cancel()
        listener = nil
        multicastDiscovery?.stop()
        multicastDiscovery = nil
        if hadListener {
            _ = startPresence()
        }
        if hadBrowser {
            startDiscovery()
        }
    }

    private var currentServiceName: String {
        "Piko-\(nickname().fullName)"
    }

    private func startLocalSendMulticast() {
        guard multicastDiscovery == nil else {
            return
        }
        let discovery = NativeLocalSendMulticast(
            queue: queue,
            localInfo: { [weak self] in
                guard let self else {
                    return NativeLocalSendDeviceInfo(
                        alias: "Piko",
                        version: "2.0",
                        deviceModel: "iPhone",
                        deviceType: "mobile",
                        fingerprint: "",
                        port: 53317,
                        protocolName: "http",
                        download: false
                    )
                }
                return self.localInfo(self.listenerPort)
            },
            onDevice: { [weak self] host, info in
                guard let self, info.fingerprint != self.nickname().fingerprint, info.port > 0 else {
                    return
                }
                let port = NWEndpoint.Port(rawValue: UInt16(info.port)) ?? NWEndpoint.Port(rawValue: 53317)!
                let endpoint = NWEndpoint.hostPort(
                    host: NWEndpoint.Host(host),
                    port: port
                )
                let device = NativeSendDevice(
                    id: "localsend-\(info.fingerprint)-\(host)-\(info.port)",
                    name: info.alias,
                    subtitle: "LocalSend",
                    endpoint: endpoint
                )
                self.multicastDevicesById[device.id] = device
                self.publishDevices()
            }
        )
        discovery.start()
        multicastDiscovery = discovery
    }

    private func publishDevices() {
        let devicesById = bonjourDevicesById.merging(multicastDevicesById) { _, multicast in multicast }
        let devices = devicesById.values.sorted { $0.name < $1.name }
        DispatchQueue.main.async {
            self.onDevicesChanged(devices)
        }
    }

    private static func nickname(fromServiceName serviceName: String, metadata: NWBrowser.Result.Metadata) -> NativeDeviceNickname {
        let fallback = NativeDeviceNickname.from(serviceName: serviceName)
        guard case let .bonjour(txtRecord) = metadata else {
            return fallback
        }
        let dictionary = txtRecord.dictionary
        return NativeDeviceNickname(
            title: dictionary["title"]?.nilIfBlank ?? fallback.title,
            code: dictionary["code"]?.fourDigitCode ?? fallback.code,
            fingerprint: dictionary["fp"]?.nilIfBlank ?? ""
        )
    }

    private static func txtRecordData(for nickname: NativeDeviceNickname) -> Data {
        var data = Data()
        [
            "title": nickname.title,
            "code": nickname.code,
            "fp": nickname.fingerprint,
        ].forEach { key, value in
            let entry = "\(key)=\(value)"
            guard let entryData = entry.data(using: .utf8), entryData.count <= 255 else {
                return
            }
            data.append(contentsOf: [UInt8(entryData.count)])
            data.append(entryData)
        }
        return data
    }
}
