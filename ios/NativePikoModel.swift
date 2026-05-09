import CryptoKit
import Foundation
import Network
import Photos

enum NativeMediaSaveLocation: String, CaseIterable, Identifiable {
    case folder
    case album

    static let userDefaultsKey = "piko.mediaSaveLocation"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .folder:
            return "文件夹"
        case .album:
            return "相册"
        }
    }

    func destination(for fileType: NativeFileType) -> NativeReceiveSaveDestination {
        if self == .album && (fileType == .image || fileType == .video) {
            return .album
        }
        return .folder
    }
}

enum NativeReceiveSaveDestination {
    case folder
    case album
}

final class NativePikoModel: ObservableObject {
    @Published var lanDevices: [NativeSendDevice] = []
    @Published var selectedDeviceIds: Set<String> = []
    @Published var items: [NativeTransferItem] = []
    @Published var selectedItemIds: Set<String> = []
    @Published var receiveHistory: [NativeReceiveHistoryItem] = []
    @Published var activeReceive: NativeReceiveTransferState?
    @Published var transferLabel = "等待发送"
    @Published var transferProgress: Double?
    @Published var discoveryLabel = "正在搜索"
    @Published var imageSectionExpanded = false
    @Published var mediaSaveLocation: NativeMediaSaveLocation

    @Published private(set) var nickname: NativeDeviceNickname

    private let queue = DispatchQueue(label: "piko.native.network")
    private var listener: NWListener?
    private var multicastDiscovery: NativeLocalSendMulticast?
    private var browser: NWBrowser?
    private var activeSendConnection: NWConnection?
    private var activeReceiveConnection: NWConnection?
    private var localSendSessions: [String: NativeLocalSendSession] = [:]

    init() {
        self.nickname = NativeDeviceNicknameStore.loadOrCreate()
        self.mediaSaveLocation = NativeMediaSaveLocation(
            rawValue: UserDefaults.standard.string(forKey: NativeMediaSaveLocation.userDefaultsKey) ?? ""
        ) ?? .folder
    }

    var currentDeviceName: String {
        nickname.fullName
    }

    private var currentServiceName: String {
        "Piko-\(nickname.fullName)"
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
        transferProgress != nil
    }

    var transferTitle: String {
        selectedItems.transferTitle
    }

    var transferSubtitle: String {
        ByteCountFormatter.string(fromByteCount: Int64(selectedItems.reduce(0) { $0 + $1.data.count }), countStyle: .file)
    }

    var transferPrimaryFileType: NativeFileType {
        selectedItems.first?.fileType ?? .other
    }

    func startPresence() {
        guard listener == nil else {
            return
        }

        do {
            let listener = try NativeLocalSendListenerFactory.makeListener()
            listener.service = NWListener.Service(
                name: currentServiceName,
                type: "_piko-share._tcp",
                domain: nil,
                txtRecord: Self.txtRecordData(for: nickname)
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.receiveIncoming(connection)
            }
            listener.start(queue: queue)
            self.listener = listener
            self.startLocalSendMulticast()
        } catch {
            DispatchQueue.main.async {
                self.transferLabel = "接收服务启动失败"
            }
        }
    }

    func startDiscovery() {
        browser?.cancel()
        discoveryLabel = "正在搜索"

        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_piko-share._tcp", domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else {
                return
            }

            let devices = results.compactMap { result -> NativeSendDevice? in
                guard case let .service(name, type, domain, _) = result.endpoint else {
                    return nil
                }
                let nickname = Self.nickname(fromServiceName: name, metadata: result.metadata)
                guard name != self.currentServiceName, nickname.fingerprint != self.nickname.fingerprint else {
                    return nil
                }
                return NativeSendDevice(
                    id: "\(name).\(type).\(domain)",
                    name: nickname.title,
                    subtitle: nickname.code,
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
        multicastDiscovery?.announce()
    }

    func resetDeviceNickname() {
        nickname = NativeDeviceNicknameStore.regenerate(keeping: nickname.fingerprint)
        let hadListener = listener != nil
        listener?.cancel()
        listener = nil
        if hadListener {
            startPresence()
        }
        if browser != nil {
            startDiscovery()
        }
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

    func updateMediaSaveLocation(_ location: NativeMediaSaveLocation) {
        mediaSaveLocation = location
        UserDefaults.standard.set(location.rawValue, forKey: NativeMediaSaveLocation.userDefaultsKey)
    }

    func sendSelectedItems() {
        let targets = selectedLanTargets
        let payloadItems = selectedItems
        guard !targets.isEmpty, !payloadItems.isEmpty else {
            return
        }

        transferLabel = transferTitle
        transferProgress = 0

        queue.async {
            let totalBytes = max(payloadItems.reduce(0) { $0 + $1.data.count } * targets.count, 1)
            var sentBytes = 0

            for target in targets {
                if let localSendBytes = self.sendLocalSendItems(
                    payloadItems,
                    to: target,
                    totalCompletedBeforeTarget: sentBytes,
                    totalBytes: totalBytes
                ) {
                    sentBytes += localSendBytes
                    continue
                }

                guard let legacyBytes = self.sendLegacyItems(
                    payloadItems,
                    to: target,
                    totalCompletedBeforeTarget: sentBytes,
                    totalBytes: totalBytes
                ) else {
                    DispatchQueue.main.async {
                        self.transferLabel = "等待发送"
                        self.transferProgress = nil
                        self.activeSendConnection = nil
                    }
                    return
                }
                sentBytes += legacyBytes
            }

            DispatchQueue.main.async {
                self.transferLabel = "等待发送"
                self.transferProgress = nil
                self.activeSendConnection = nil
            }
        }
    }

    private func sendLocalSendItems(
        _ payloadItems: [NativeTransferItem],
        to target: NativeSendDevice,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int
    ) -> Int? {
        let indexedItems = payloadItems.enumerated().map { index, item in
            NativeLocalSendIndexedItem(
                fileId: "file-\(index)",
                item: item,
                metadata: NativeLocalSendFileMetadata(
                    id: "file-\(index)",
                    fileName: item.displayName,
                    size: item.data.count,
                    fileType: item.fileType.mimeType,
                    sha256: nil,
                    preview: item.fileType == .image ? item.data.base64EncodedString() : nil,
                    relativePath: item.displayName
                )
            )
        }
        let prepareBody = NativeLocalSendProtocol.prepareUploadRequest(
            info: localSendDeviceInfo(port: 0),
            files: indexedItems.map(\.metadata)
        )
        guard let prepareResponse = sendHttpRequest(
            to: target.endpoint,
            method: "POST",
            path: "/api/localsend/v2/prepare-upload",
            body: prepareBody
        ), prepareResponse.statusCode == 200,
            let session = NativeLocalSendProtocol.decodePrepareUploadResponse(prepareResponse.body) else {
            return nil
        }

        var sentBytes = 0
        for indexed in indexedItems {
            guard let token = session.fileTokens[indexed.fileId] else {
                return nil
            }
            let path = "/api/localsend/v2/upload" +
                "?sessionId=\(session.sessionId.urlEncoded)" +
                "&fileId=\(indexed.fileId.urlEncoded)" +
                "&token=\(token.urlEncoded)"
            guard let uploadResponse = sendHttpRequest(
                to: target.endpoint,
                method: "POST",
                path: path,
                body: indexed.item.data,
                contentType: indexed.metadata.fileType
            ), (200..<300).contains(uploadResponse.statusCode) else {
                return nil
            }
            sentBytes += indexed.item.data.count
            DispatchQueue.main.async {
                self.transferProgress = Double(totalCompletedBeforeTarget + sentBytes) / Double(totalBytes)
            }
        }
        return sentBytes
    }

    private func sendLegacyItems(
        _ payloadItems: [NativeTransferItem],
        to target: NativeSendDevice,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int
    ) -> Int? {
                let connection = NWConnection(to: target.endpoint, using: .tcp)
                DispatchQueue.main.async {
                    self.activeSendConnection = connection
                }
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
                        self.transferLabel = "等待发送"
                        self.transferProgress = nil
                        self.activeSendConnection = nil
                    }
                    return nil
                }

                let header = NativeTransferProtocol.encodeHeader(items: payloadItems, senderName: self.nickname.title)
                if !self.send(header, over: connection) {
                    connection.cancel()
                    DispatchQueue.main.async {
                        self.transferLabel = "等待发送"
                        self.transferProgress = nil
                        self.activeSendConnection = nil
                    }
                    return nil
                }

                var sentBytes = 0
                for item in payloadItems {
                    guard self.send(item.data, over: connection) else {
                        connection.cancel()
                        DispatchQueue.main.async {
                            self.transferLabel = "等待发送"
                            self.transferProgress = nil
                            self.activeSendConnection = nil
                        }
                        return nil
                    }
                    sentBytes += item.data.count
                    DispatchQueue.main.async {
                        self.transferProgress = Double(totalCompletedBeforeTarget + sentBytes) / Double(totalBytes)
                    }
                }
                connection.cancel()
        return sentBytes
    }

    func pauseTransfer() {
        activeSendConnection?.cancel()
        transferLabel = "等待发送"
        transferProgress = nil
        activeSendConnection = nil
    }

    func cancelTransfer() {
        activeSendConnection?.cancel()
        transferLabel = "等待发送"
        transferProgress = nil
        activeSendConnection = nil
    }

    func cancelReceiveTransfer() {
        activeReceiveConnection?.cancel()
        activeReceiveConnection = nil
        activeReceive = nil
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

    private func sendHttpRequest(
        to endpoint: NWEndpoint,
        method: String,
        path: String,
        body: Data,
        contentType: String = "application/json; charset=utf-8"
    ) -> NativeHttpResponse? {
        let connection = NWConnection(to: endpoint, using: .tcp)
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
        let connectionQueue = DispatchQueue(label: "piko.native.http.\(UUID().uuidString)")
        connection.start(queue: connectionQueue)
        ready.wait()
        guard !failed else {
            connection.cancel()
            return nil
        }

        var request = Data()
        request.append("\(method) \(path) HTTP/1.1\r\n")
        request.append("Host: piko.local\r\n")
        request.append("Content-Type: \(contentType)\r\n")
        request.append("Content-Length: \(body.count)\r\n")
        request.append("Connection: close\r\n")
        request.append("\r\n")
        request.append(body)

        guard send(request, over: connection) else {
            connection.cancel()
            return nil
        }

        let finished = DispatchSemaphore(value: 0)
        var response = Data()
        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
                if let data {
                    response.append(data)
                }
                if isComplete {
                    finished.signal()
                } else {
                    receiveNext()
                }
            }
        }
        receiveNext()
        finished.wait()
        connection.cancel()
        return NativeHttpResponse.parse(response)
    }

    private func receiveIncoming(_ connection: NWConnection) {
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                self.receiveIncomingPrefix(from: connection, buffer: Data())
            }
        }
        connection.start(queue: queue)
    }

    private func receiveIncomingPrefix(from connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            var nextBuffer = buffer
            if let data {
                nextBuffer.append(data)
            }
            if nextBuffer.starts(with: NativeTransferProtocol.magic) {
                let transferId = UUID().uuidString
                DispatchQueue.main.async {
                    self.activeReceiveConnection = connection
                }
                self.receiveNextChunk(from: connection, transferId: transferId, buffer: nextBuffer)
                return
            }
            if let range = nextBuffer.range(of: Data("\r\n\r\n".utf8)) {
                let headerEnd = range.upperBound
                let headerData = nextBuffer.subdata(in: 0..<headerEnd)
                let bodyPrefix = nextBuffer.subdata(in: headerEnd..<nextBuffer.count)
                self.handleHttpRequest(connection: connection, headerData: headerData, bodyPrefix: bodyPrefix)
                return
            }
            if isComplete {
                connection.cancel()
            } else {
                self.receiveIncomingPrefix(from: connection, buffer: nextBuffer)
            }
        }
    }

    private func receive(_ connection: NWConnection) {
        let transferId = UUID().uuidString
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                DispatchQueue.main.async {
                    self.activeReceiveConnection = connection
                }
                self.receiveNextChunk(from: connection, transferId: transferId, buffer: Data())
            }
        }
        connection.start(queue: queue)
    }

    private func handleHttpRequest(
        connection: NWConnection,
        headerData: Data,
        bodyPrefix: Data
    ) {
        guard let request = NativeHttpRequest.parse(headerData) else {
            sendHttpResponse(connection, statusCode: 400, body: Data(#"{"error":"bad request"}"#.utf8))
            return
        }
        switch (request.method, request.path) {
        case ("GET", "/api/localsend/v2/info"):
            sendHttpResponse(connection, statusCode: 200, body: localSendDeviceInfo(port: listenerPort).jsonData)
        case ("POST", "/api/localsend/v2/register"):
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                self.sendHttpResponse(connection, statusCode: 200, body: self.localSendDeviceInfo(port: self.listenerPort).jsonData)
            }
        case ("POST", "/api/localsend/v2/prepare-upload"):
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { body in
                guard let request = NativeLocalSendProtocol.decodePrepareUploadRequest(body) else {
                    self.sendHttpResponse(connection, statusCode: 400, body: Data(#"{"error":"invalid prepare-upload"}"#.utf8))
                    return
                }
                let response = self.prepareLocalSendSession(request)
                self.sendHttpResponse(connection, statusCode: 200, body: response.jsonData)
            }
        case ("POST", "/api/localsend/v2/upload"):
            handleLocalSendUpload(connection: connection, request: request, bodyPrefix: bodyPrefix)
        case ("POST", "/api/localsend/v2/cancel"):
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                if let sessionId = request.query["sessionId"] {
                    self.localSendSessions.removeValue(forKey: sessionId)
                }
                self.sendHttpResponse(connection, statusCode: 200, body: Data(#"{"success":true}"#.utf8))
            }
        default:
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                self.sendHttpResponse(connection, statusCode: 404, body: Data(#"{"error":"not found"}"#.utf8))
            }
        }
    }

    private var listenerPort: Int {
        Int(listener?.port?.rawValue ?? 53317)
    }

    private func localSendDeviceInfo(port: Int) -> NativeLocalSendDeviceInfo {
        NativeLocalSendDeviceInfo(
            alias: nickname.title,
            version: "2.0",
            deviceModel: "iPhone",
            deviceType: "mobile",
            fingerprint: nickname.fingerprint,
            port: port,
            protocolName: "http",
            download: false
        )
    }

    private func prepareLocalSendSession(_ request: NativeLocalSendPrepareUploadRequest) -> NativeLocalSendPrepareUploadResponse {
        let sessionId = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        var fileTokens: [String: String] = [:]
        var files: [String: NativeLocalSendSessionFile] = [:]
        for file in request.files {
            let token = UUID().uuidString.replacingOccurrences(of: "-", with: "")
            fileTokens[file.id] = token
            files[file.id] = NativeLocalSendSessionFile(metadata: file, token: token)
        }
        localSendSessions[sessionId] = NativeLocalSendSession(sender: request.info, files: files)
        let transferFiles = request.files.map {
            NativeTransferFileMetadata(
                displayName: $0.fileName,
                fileType: NativeFileType(mimeType: $0.fileType),
                sizeBytes: $0.size
            )
        }
        DispatchQueue.main.async {
            self.activeReceive = NativeReceiveTransferState(
                id: sessionId,
                senderName: request.info.alias,
                files: transferFiles,
                totalBytes: request.files.reduce(0) { $0 + $1.size },
                receivedBytes: 0
            )
        }
        return NativeLocalSendPrepareUploadResponse(sessionId: sessionId, fileTokens: fileTokens)
    }

    private func handleLocalSendUpload(
        connection: NWConnection,
        request: NativeHttpRequest,
        bodyPrefix: Data
    ) {
        guard let sessionId = request.query["sessionId"],
              let fileId = request.query["fileId"],
              let token = request.query["token"],
              let file = localSendSessions[sessionId]?.files[fileId],
              file.token == token else {
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                self.sendHttpResponse(connection, statusCode: 403, body: Data(#"{"error":"invalid upload token"}"#.utf8))
            }
            return
        }

        let directory = receivedDirectory()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let finalURL = directory.appendingPathComponent(file.metadata.fileName.sanitizedFileName)
        let temporaryURL = temporaryReceivedURL(fileName: file.metadata.fileName, directory: directory)
        FileManager.default.createFile(atPath: temporaryURL.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: temporaryURL) else {
            sendHttpResponse(connection, statusCode: 500, body: Data(#"{"error":"cannot create file"}"#.utf8))
            return
        }

        var remaining = request.contentLength
        var hasher = SHA256()
        func write(_ chunk: Data) {
            guard remaining > 0 else {
                return
            }
            let writableCount = min(chunk.count, remaining)
            guard writableCount > 0 else {
                return
            }
            let writable = chunk.prefix(writableCount)
            handle.write(Data(writable))
            hasher.update(data: writable)
            remaining -= writableCount
            DispatchQueue.main.async {
                if let active = self.activeReceive, active.id == sessionId {
                    self.activeReceive = NativeReceiveTransferState(
                        id: active.id,
                        senderName: active.senderName,
                        files: active.files,
                        totalBytes: active.totalBytes,
                        receivedBytes: min(active.receivedBytes + writableCount, active.totalBytes)
                    )
                }
            }
        }

        func finishUpload() {
            try? handle.close()
            if let expectedHash = file.metadata.sha256 {
                let actualHash = hasher.finalize().hexString
                guard actualHash.caseInsensitiveCompare(expectedHash) == .orderedSame else {
                    try? FileManager.default.removeItem(at: temporaryURL)
                    sendHttpResponse(connection, statusCode: 400, body: Data(#"{"error":"sha256 mismatch"}"#.utf8))
                    return
                }
            }
            saveReceivedTemporaryFile(
                temporaryURL,
                finalURL: finalURL,
                fileType: NativeFileType(mimeType: file.metadata.fileType)
            ) { saved in
                guard saved else {
                    try? FileManager.default.removeItem(at: temporaryURL)
                    self.sendHttpResponse(connection, statusCode: 500, body: Data(#"{"error":"cannot save file"}"#.utf8))
                    return
                }
                let received = NativeReceivedFile(
                    displayName: file.metadata.fileName,
                    fileType: NativeFileType(mimeType: file.metadata.fileType),
                    data: (try? Data(contentsOf: finalURL)) ?? Data()
                )
                DispatchQueue.main.async {
                    self.receiveHistory.insert(
                        NativeReceiveHistoryItem(
                            title: received.displayName,
                            subtitle: ByteCountFormatter.string(fromByteCount: Int64(file.metadata.size), countStyle: .file),
                            fileCount: 1,
                            primaryFileType: received.fileType,
                            imagePreviewData: received.fileType == .image ? received.data : nil
                        ),
                        at: 0
                    )
                    self.activeReceive = nil
                }
                self.sendHttpResponse(connection, statusCode: 200, body: Data(#"{"success":true}"#.utf8))
            }
        }

        write(bodyPrefix)
        guard remaining > 0 else {
            finishUpload()
            return
        }
        receiveHttpStream(connection: connection, remaining: remaining, write: write, finish: finishUpload)
    }

    private func receiveHttpBody(
        connection: NWConnection,
        initialBody: Data,
        expectedLength: Int,
        completion: @escaping (Data) -> Void
    ) {
        var body = initialBody
        let remaining = expectedLength - body.count
        guard remaining > 0 else {
            completion(Data(body.prefix(expectedLength)))
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            if let data {
                body.append(data)
            }
            if body.count >= expectedLength || isComplete {
                completion(Data(body.prefix(expectedLength)))
            } else {
                self.receiveHttpBody(connection: connection, initialBody: body, expectedLength: expectedLength, completion: completion)
            }
        }
    }

    private func receiveHttpStream(
        connection: NWConnection,
        remaining: Int,
        write: @escaping (Data) -> Void,
        finish: @escaping () -> Void
    ) {
        var nextRemaining = remaining
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            if let data {
                let writable = data.prefix(nextRemaining)
                write(Data(writable))
                nextRemaining -= writable.count
            }
            if nextRemaining <= 0 || isComplete {
                finish()
            } else {
                self.receiveHttpStream(connection: connection, remaining: nextRemaining, write: write, finish: finish)
            }
        }
    }

    private func sendHttpResponse(
        _ connection: NWConnection,
        statusCode: Int,
        body: Data
    ) {
        let reason: String
        switch statusCode {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 403: reason = "Forbidden"
        case 404: reason = "Not Found"
        default: reason = "Error"
        }
        var response = Data()
        response.append("HTTP/1.1 \(statusCode) \(reason)\r\n")
        response.append("Content-Type: application/json; charset=utf-8\r\n")
        response.append("Content-Length: \(body.count)\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func receiveNextChunk(from connection: NWConnection, transferId: String, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            var nextBuffer = buffer
            if let data {
                nextBuffer.append(data)
            }

            if let envelope = NativeTransferProtocol.inspectTransfer(nextBuffer) {
                DispatchQueue.main.async {
                    self.activeReceive = NativeReceiveTransferState(
                        id: transferId,
                        senderName: envelope.senderName,
                        files: envelope.files,
                        totalBytes: envelope.totalBytes,
                        receivedBytes: envelope.receivedBytes
                    )
                }
                if let transfer = envelope.transfer {
                    DispatchQueue.main.async {
                        if self.activeReceiveConnection === connection {
                            self.activeReceiveConnection = nil
                        }
                    }
                    self.saveReceivedTransfer(transfer)
                    connection.cancel()
                    return
                }
            }

            if isComplete {
                DispatchQueue.main.async {
                    if self.activeReceive?.id == transferId {
                        self.activeReceive = nil
                    }
                    if self.activeReceiveConnection === connection {
                        self.activeReceiveConnection = nil
                    }
                }
                connection.cancel()
            } else {
                self.receiveNextChunk(from: connection, transferId: transferId, buffer: nextBuffer)
            }
        }
    }

    private func saveReceivedTransfer(_ transfer: NativeReceivedTransfer) {
        let directory = receivedDirectory()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let group = DispatchGroup()
        let lock = NSLock()
        var savedFiles: [NativeReceivedFile] = []
        for file in transfer.files {
            let finalURL = directory.appendingPathComponent(file.displayName.sanitizedFileName)
            let temporaryURL = temporaryReceivedURL(fileName: file.displayName, directory: directory)
            guard (try? file.data.write(to: temporaryURL, options: .atomic)) != nil else {
                continue
            }
            group.enter()
            saveReceivedTemporaryFile(temporaryURL, finalURL: finalURL, fileType: file.fileType) { saved in
                if saved {
                    lock.lock()
                    savedFiles.append(file)
                    lock.unlock()
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            guard let firstFile = savedFiles.first else {
                self.activeReceive = nil
                return
            }
            let names = savedFiles.map(\.displayName)
            self.receiveHistory.insert(
                NativeReceiveHistoryItem(
                    title: names.count == 1 ? names[0] : "\(names[0]) + \(names.count - 1) 个文件",
                    subtitle: ByteCountFormatter.string(
                        fromByteCount: Int64(savedFiles.reduce(0) { $0 + $1.data.count }),
                        countStyle: .file
                    ),
                    fileCount: savedFiles.count,
                    primaryFileType: firstFile.fileType,
                    imagePreviewData: firstFile.fileType == .image ? firstFile.data : nil
                ),
                at: 0
            )
            self.activeReceive = nil
        }
    }

    private func saveReceivedTemporaryFile(
        _ temporaryURL: URL,
        finalURL: URL,
        fileType: NativeFileType,
        completion: @escaping (Bool) -> Void
    ) {
        switch mediaSaveLocation.destination(for: fileType) {
        case .folder:
            try? FileManager.default.removeItem(at: finalURL)
            do {
                try FileManager.default.moveItem(at: temporaryURL, to: finalURL)
                completion(true)
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                completion(false)
            }
        case .album:
            saveMediaToPhotoLibrary(fileURL: temporaryURL, fileType: fileType) { saved in
                try? FileManager.default.removeItem(at: temporaryURL)
                completion(saved)
            }
        }
    }

    private func saveMediaToPhotoLibrary(
        fileURL: URL,
        fileType: NativeFileType,
        completion: @escaping (Bool) -> Void
    ) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion(false)
                return
            }
            PHPhotoLibrary.shared().performChanges {
                if fileType == .video {
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
                } else {
                    PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
                }
            } completionHandler: { saved, _ in
                completion(saved)
            }
        }
    }

    private func receivedDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Piko", isDirectory: true)
    }

    private func temporaryReceivedURL(fileName: String, directory: URL) -> URL {
        directory.appendingPathComponent(".\(UUID().uuidString)-\(fileName.sanitizedFileName)")
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
                return self.localSendDeviceInfo(port: self.listenerPort)
            },
            onDevice: { [weak self] host, info in
                guard let self, info.fingerprint != self.nickname.fingerprint, info.port > 0 else {
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
                DispatchQueue.main.async {
                    var devicesById = Dictionary(uniqueKeysWithValues: self.lanDevices.map { ($0.id, $0) })
                    devicesById[device.id] = device
                    self.lanDevices = devicesById.values.sorted { $0.name < $1.name }
                    self.selectedDeviceIds = self.selectedDeviceIds.intersection(Set(self.lanDevices.map(\.id)))
                    self.discoveryLabel = self.lanDevices.isEmpty ? "暂无设备" : "已发现 \(self.lanDevices.count) 台"
                }
            }
        )
        discovery.start()
        multicastDiscovery = discovery
    }

    private static var placeholderEndpoint: NWEndpoint {
        .hostPort(host: NWEndpoint.Host("127.0.0.1"), port: NWEndpoint.Port(rawValue: 9)!)
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

struct NativeDeviceNickname: Equatable {
    let title: String
    let code: String
    let fingerprint: String

    var fullName: String {
        "\(title)@\(code)"
    }

    static func from(serviceName: String) -> NativeDeviceNickname {
        let rawName = serviceName.hasPrefix("Piko-") ? String(serviceName.dropFirst("Piko-".count)) : serviceName
        let pieces = rawName.split(separator: "@", maxSplits: 1).map(String.init)
        return NativeDeviceNickname(
            title: pieces.first?.nilIfBlank ?? rawName,
            code: pieces.dropFirst().first?.fourDigitCode ?? "0000",
            fingerprint: ""
        )
    }
}

private enum NativeDeviceNicknameStore {
    private static let titleKey = "piko.deviceNickname.title"
    private static let codeKey = "piko.deviceNickname.code"
    private static let fingerprintKey = "piko.deviceNickname.fingerprint"

    static func loadOrCreate() -> NativeDeviceNickname {
        if let existing = load() {
            return existing
        }
        let fingerprint = UserDefaults.standard.string(forKey: fingerprintKey)?.nilIfBlank ?? UUID().uuidString
        let nickname = generate(fingerprint: fingerprint)
        save(nickname)
        return nickname
    }

    static func regenerate(keeping fingerprint: String) -> NativeDeviceNickname {
        let nickname = generate(fingerprint: fingerprint.nilIfBlank ?? UUID().uuidString)
        save(nickname)
        return nickname
    }

    private static func load() -> NativeDeviceNickname? {
        guard
            let title = UserDefaults.standard.string(forKey: titleKey)?.nilIfBlank,
            let code = UserDefaults.standard.string(forKey: codeKey)?.fourDigitCode,
            let fingerprint = UserDefaults.standard.string(forKey: fingerprintKey)?.nilIfBlank
        else {
            return nil
        }
        return NativeDeviceNickname(title: title, code: code, fingerprint: fingerprint)
    }

    private static func save(_ nickname: NativeDeviceNickname) {
        UserDefaults.standard.set(nickname.title, forKey: titleKey)
        UserDefaults.standard.set(nickname.code, forKey: codeKey)
        UserDefaults.standard.set(nickname.fingerprint, forKey: fingerprintKey)
    }

    private static func generate(fingerprint: String) -> NativeDeviceNickname {
        NativeDeviceNickname(
            title: "\(adjectives.randomElement() ?? "赤色")\(nouns.randomElement() ?? "星河")",
            code: String(format: "%04d", Int.random(in: 0...9999)),
            fingerprint: fingerprint
        )
    }

    private static let adjectives = [
        "赤色", "清亮", "轻快", "温柔", "安静", "明朗", "灵巧", "松弛", "锋利", "柔软",
        "澄澈", "灿烂", "沉稳", "敏捷", "悠然", "热烈", "青蓝", "晴朗", "微光", "薄荷",
        "银白", "琥珀", "翠绿", "深空", "流云", "暖阳", "星辉", "锦瑟", "远山", "新雪",
        "晨雾", "暮色", "海盐", "月白", "花火", "竹青", "霜蓝", "橙光", "静好", "飞扬",
    ]

    private static let nouns = [
        "星河", "山谷", "竹影", "海湾", "云帆", "月台", "风铃", "灯塔", "溪流", "花园",
        "书页", "岛屿", "晨星", "松林", "港口", "旅人", "音符", "纸鹤", "晴空", "贝壳",
        "锦鲤", "银杏", "山岚", "雪线", "茶盏", "木舟", "麦田", "星尘", "雨巷", "南风",
        "北辰", "长桥", "清泉", "花径", "云雀", "青石", "灯火", "白鹭", "秋水", "春山",
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
    let imagePreviewData: Data?
}

struct NativeReceiveTransferState: Identifiable {
    let id: String
    let senderName: String
    let files: [NativeTransferFileMetadata]
    let totalBytes: Int
    let receivedBytes: Int

    var title: String {
        "正在从\(senderName.visibleDeviceName)接收\(files.count)个文件"
    }

    var subtitle: String {
        "\(ByteCountFormatter.string(fromByteCount: Int64(receivedBytes), countStyle: .file))/\(ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file))"
    }

    var progress: Double {
        guard totalBytes > 0 else {
            return 0
        }
        return min(max(Double(receivedBytes) / Double(totalBytes), 0), 1)
    }

    var primaryFileType: NativeFileType {
        files.first?.fileType ?? .other
    }
}

enum NativeFileType: Int {
    case document = 0
    case spreadsheet = 1
    case image = 2
    case video = 3
    case archive = 4
    case other = 5

    init(mimeType: String) {
        if mimeType.hasPrefix("image/") {
            self = .image
        } else if mimeType.hasPrefix("video/") {
            self = .video
        } else if mimeType.contains("zip") || mimeType.contains("archive") {
            self = .archive
        } else if mimeType.contains("spreadsheet") || mimeType.contains("excel") {
            self = .spreadsheet
        } else if mimeType.contains("pdf") || mimeType.contains("document") || mimeType.hasPrefix("text/") {
            self = .document
        } else {
            self = .other
        }
    }

    var mimeType: String {
        switch self {
        case .image:
            return "image/*"
        case .video:
            return "video/*"
        case .archive:
            return "application/zip"
        case .document, .spreadsheet, .other:
            return "application/octet-stream"
        }
    }

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
    let senderName: String
    let files: [NativeReceivedFile]
}

extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    var fourDigitCode: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.range(of: #"^\d{4}$"#, options: .regularExpression) == nil ? nil : trimmed
    }

    var sanitizedFileName: String {
        let invalid = CharacterSet(charactersIn: "/\\?%*|\"<>:")
        return components(separatedBy: invalid).joined(separator: "_")
    }

    var visibleDeviceName: String {
        let name = components(separatedBy: "@").first?.nilIfBlank ?? self
        return name.nilIfBlank ?? "局域网设备"
    }

    var urlEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? self
    }
}

extension SHA256.Digest {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}

private extension Array where Element == NativeTransferItem {
    var transferTitle: String {
        guard let first else {
            return ""
        }
        return count == 1 ? first.displayName : "\(first.displayName) + \(count - 1) 个文件"
    }
}
