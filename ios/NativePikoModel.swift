import Foundation
import Network
import UIKit

final class NativePikoModel: ObservableObject {
    @Published var lanDevices: [NativeSendDevice] = []
    @Published var selectedDeviceIds: Set<String> = []
    @Published var items: [NativeTransferItem] = []
    @Published var selectedItemIds: Set<String> = []
    @Published var receiveHistory: [NativeReceiveHistoryItem] = []
    @Published var transferLabel = "等待发送"
    @Published var transferProgress: Double?
    @Published var discoveryLabel = "正在搜索"
    @Published var imageSectionExpanded = false

    let currentDeviceName: String

    private let queue = DispatchQueue(label: "piko.native.network")
    private var listener: NWListener?
    private var browser: NWBrowser?
    private let currentServiceName: String

    init() {
        let device = UIDevice.current
        let currentDeviceName = Self.normalizedCurrentDeviceName(
            rawName: device.name,
            userInterfaceIdiom: device.userInterfaceIdiom,
            identifierForVendor: device.identifierForVendor
        )
        self.currentDeviceName = currentDeviceName
        self.currentServiceName = "Piko-\(currentDeviceName)"
    }

    var canSend: Bool {
        !selectedLanTargets.isEmpty && !selectedItems.isEmpty && transferProgress == nil
    }

    private var selectedItems: [NativeTransferItem] {
        items.filter { selectedItemIds.contains($0.id) }
    }

    private var selectedLanTargets: [NativeSendDevice] {
        lanDevices.filter { selectedDeviceIds.contains($0.id) }
    }

    var myDevices: [NativeSendDevice] { [] }

    var friendDevices: [NativeSendDevice] {
        [
            NativeSendDevice(
                id: "friend-laptop",
                name: "Cavan 的 MacBook",
                subtitle: "最近同步",
                endpoint: Self.placeholderEndpoint
            ),
            NativeSendDevice(
                id: "friend-tablet",
                name: "客厅 iPad",
                subtitle: "可信设备",
                endpoint: Self.placeholderEndpoint
            )
        ]
    }

    var imageItems: [NativeTransferItem] {
        items.filter { $0.fileType == .image }
    }

    var fileItems: [NativeTransferItem] {
        items.filter { $0.fileType != .image }
    }

    var transferProgressLabel: String {
        guard let transferProgress else {
            return "待命"
        }
        return "\(Int(transferProgress * 100))%"
    }

    var transferIsVisible: Bool {
        transferProgress != nil || transferLabel != "等待发送"
    }

    func startPresence() {
        guard listener == nil else {
            return
        }

        do {
            let listener = try NWListener(using: .tcp)
            listener.service = NWListener.Service(
                name: currentServiceName,
                type: "_piko-share._tcp"
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.receive(connection)
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            DispatchQueue.main.async {
                self.transferLabel = "接收服务启动失败"
            }
        }
    }

    func startDiscovery() {
        browser?.cancel()
        discoveryLabel = "正在搜索"

        let browser = NWBrowser(for: .bonjour(type: "_piko-share._tcp", domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else {
                return
            }

            let devices = results.compactMap { result -> NativeSendDevice? in
                guard case let .service(name, type, domain, _) = result.endpoint else {
                    return nil
                }
                guard name != self.currentServiceName else {
                    return nil
                }
                return NativeSendDevice(
                    id: "\(name).\(type).\(domain)",
                    name: Self.displayName(fromServiceName: name),
                    subtitle: domain.isEmpty ? type : domain,
                    endpoint: result.endpoint
                )
            }

            DispatchQueue.main.async {
                self.lanDevices = devices.sorted { $0.name < $1.name }
                self.selectedDeviceIds = self.selectedDeviceIds.intersection(Set(devices.map(\.id)))
                self.discoveryLabel = devices.isEmpty ? "暂无设备" : "已发现 \(devices.count) 台"
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                if case .failed = state {
                    self?.discoveryLabel = "搜索失败"
                }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func toggleDevice(_ id: String) {
        if selectedDeviceIds.contains(id) {
            selectedDeviceIds.remove(id)
        } else {
            selectedDeviceIds.insert(id)
        }
    }

    func addItems(_ newItems: [NativeTransferItem]) {
        let existingIds = Set(items.map(\.id))
        let uniqueItems = newItems.filter { !existingIds.contains($0.id) }
        items.append(contentsOf: uniqueItems)
        selectedItemIds.formUnion(uniqueItems.map(\.id))
    }

    func toggleItem(_ id: String) {
        if selectedItemIds.contains(id) {
            selectedItemIds.remove(id)
        } else {
            selectedItemIds.insert(id)
        }
    }

    func removeItem(_ id: String) {
        items.removeAll { $0.id == id }
        selectedItemIds.remove(id)
    }

    func toggleImageSectionExpanded() {
        imageSectionExpanded.toggle()
    }

    func sendSelectedItems() {
        let targets = selectedLanTargets
        let payloadItems = selectedItems
        guard !targets.isEmpty, !payloadItems.isEmpty else {
            return
        }

        transferLabel = "正在发送"
        transferProgress = 0

        queue.async {
            let totalBytes = max(payloadItems.reduce(0) { $0 + $1.data.count } * targets.count, 1)
            var sentBytes = 0

            for target in targets {
                let connection = NWConnection(to: target.endpoint, using: .tcp)
                let ready = DispatchSemaphore(value: 0)
                var failed = false

                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        ready.signal()
                    case .failed, .cancelled:
                        failed = true
                        ready.signal()
                    default:
                        break
                    }
                }
                let connectionQueue = DispatchQueue(label: "piko.native.connection.\(UUID().uuidString)")
                connection.start(queue: connectionQueue)
                ready.wait()

                guard !failed else {
                    connection.cancel()
                    DispatchQueue.main.async {
                        self.transferLabel = "发送失败"
                        self.transferProgress = nil
                    }
                    return
                }

                let header = NativeTransferProtocol.encodeHeader(items: payloadItems)
                if !self.send(header, over: connection) {
                    connection.cancel()
                    DispatchQueue.main.async {
                        self.transferLabel = "发送失败"
                        self.transferProgress = nil
                    }
                    return
                }

                for item in payloadItems {
                    guard self.send(item.data, over: connection) else {
                        connection.cancel()
                        DispatchQueue.main.async {
                            self.transferLabel = "发送失败"
                            self.transferProgress = nil
                        }
                        return
                    }
                    sentBytes += item.data.count
                    DispatchQueue.main.async {
                        self.transferProgress = Double(sentBytes) / Double(totalBytes)
                    }
                }
                connection.cancel()
            }

            DispatchQueue.main.async {
                self.transferLabel = "发送完成"
                self.transferProgress = nil
            }
        }
    }

    private func send(_ data: Data, over connection: NWConnection) -> Bool {
        let finished = DispatchSemaphore(value: 0)
        var succeeded = true
        connection.send(content: data, completion: .contentProcessed { error in
            succeeded = error == nil
            finished.signal()
        })
        finished.wait()
        return succeeded
    }

    private func receive(_ connection: NWConnection) {
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                self.receiveNextChunk(from: connection, buffer: Data())
            }
        }
        connection.start(queue: queue)
    }

    private func receiveNextChunk(from connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            var nextBuffer = buffer
            if let data {
                nextBuffer.append(data)
            }

            if let transfer = NativeTransferProtocol.decodeTransfer(nextBuffer) {
                self.saveReceivedTransfer(transfer)
                connection.cancel()
                return
            }

            if isComplete {
                connection.cancel()
            } else {
                self.receiveNextChunk(from: connection, buffer: nextBuffer)
            }
        }
    }

    private func saveReceivedTransfer(_ transfer: NativeReceivedTransfer) {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Piko", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        for file in transfer.files {
            let url = directory.appendingPathComponent(file.displayName.sanitizedFileName)
            try? file.data.write(to: url, options: .atomic)
        }

        DispatchQueue.main.async {
            let names = transfer.files.map(\.displayName)
            self.receiveHistory.insert(
                NativeReceiveHistoryItem(
                    title: names.count == 1 ? names[0] : "\(names[0])+\(names.count - 1)个文件",
                    subtitle: "刚刚 - 来自局域网设备",
                    fileCount: transfer.files.count,
                    primaryFileType: transfer.files.first?.fileType ?? .other,
                    imagePreviewDescription: transfer.files.first?.fileType == .image ? "接收图片缩略图" : nil
                ),
                at: 0
            )
        }
    }

    private static var placeholderEndpoint: NWEndpoint {
        .hostPort(host: NWEndpoint.Host("127.0.0.1"), port: NWEndpoint.Port(rawValue: 9)!)
    }

    private static func normalizedCurrentDeviceName(
        rawName: String,
        userInterfaceIdiom: UIUserInterfaceIdiom,
        identifierForVendor: UUID?
    ) -> String {
        let trimmedName = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedName.isEmpty && !genericDeviceNames.contains(trimmedName) {
            return trimmedName
        }

        let modelName = userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        let suffix = identifierForVendor?
            .uuidString
            .replacingOccurrences(of: "-", with: "")
            .prefix(4)
            .uppercased()

        guard let suffix, !suffix.isEmpty else {
            return "Piko \(modelName)"
        }
        return "\(modelName) \(suffix)"
    }

    private static func displayName(fromServiceName serviceName: String) -> String {
        if serviceName.hasPrefix("Piko-") {
            return String(serviceName.dropFirst("Piko-".count))
        }
        return serviceName
    }

    private static let genericDeviceNames: Set<String> = [
        "iPhone",
        "iPad"
    ]
}

struct NativeSendDevice: Identifiable {
    let id: String
    let name: String
    let subtitle: String?
    let endpoint: NWEndpoint
}

struct NativeTransferItem: Identifiable {
    let id: String
    let displayName: String
    let fileType: NativeFileType
    let data: Data

    var sizeLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file)
    }

    var systemImage: String {
        fileType == .image ? "photo" : "doc"
    }
}

struct NativeReceiveHistoryItem: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let fileCount: Int
    let primaryFileType: NativeFileType
    let imagePreviewDescription: String?
}

enum NativeFileType: Int {
    case document = 0
    case spreadsheet = 1
    case image = 2
    case video = 3
    case archive = 4
    case other = 5

    var previewLabel: String {
        switch self {
        case .document:
            return "DOC"
        case .spreadsheet:
            return "XLS"
        case .image:
            return "IMG"
        case .video:
            return "VID"
        case .archive:
            return "ZIP"
        case .other:
            return "FILE"
        }
    }
}

struct NativeReceivedFile {
    let displayName: String
    let fileType: NativeFileType
    let data: Data
}

struct NativeReceivedTransfer {
    let files: [NativeReceivedFile]
}

extension String {
    var sanitizedFileName: String {
        let invalid = CharacterSet(charactersIn: "/\\?%*|\"<>:")
        return components(separatedBy: invalid).joined(separator: "_")
    }
}
