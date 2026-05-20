import CryptoKit
import Combine
import Foundation
import Network
import OSLog

private let nativeReceiveModelLogger = Logger(subsystem: "com.juren233.piko", category: "receive-list")

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

private struct NativeP2PFailureDiagnostic {
    let stage: String
    let sessionId: String
    let originalReason: String
    let directAttemptPlan: String
    let directEndpointCount: String
    let directEndpoints: String
    let directCandidates: String
    let directNatDiagnostic: String
    let directSelected: String
    let directAttemptResult: String
    let directLastError: String
    let offerSent: String
    let answerReceived: String
    let localIceCount: String
    let remoteIceCount: String
    let iceServerUrls: String
    let localCandidateTypes: String
    let remoteCandidateTypes: String
    let localCandidateDetails: String
    let remoteCandidateDetails: String
    let iceConnectionState: String
    let peerConnectionState: String
    let iceGatheringState: String
    let signalingState: String
    let dataChannelState: String
    let sendFailure: String
    let iceCandidateErrors: String
    let selectedCandidatePair: String
    let iceCandidatePairStats: String
    let stunErrorRate: String
    let gatheringIncomplete: String
    let symmetricNatSuspect: String
    let remoteOnlyMdns: String
    let failureReasonCode: String
    let failureReasonTitle: String?
    let failureReasonSuggestion: String?
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
    @Published var transferFailureMessage: String?
    @Published var transferToastMessage: String?
    @Published var discoveryLabel = "正在搜索"
    @Published var imageSectionExpanded = false
    @Published var mediaSaveLocation: NativeMediaSaveLocation

    @Published private(set) var nickname: NativeDeviceNickname

    let authStore: NativeAuthStore
    let friendStore: NativeFriendStore

    private let queue = DispatchQueue(label: "piko.native.network")
    private let transferClient = NativeTransferClient()
    private let deviceIdentityStore = NativeDeviceIdentityStore()
    private let deviceApiClient = NativeDeviceApiClient()
    private let signalingClient = NativeSignalingClient()
    private let receiveFileStore = NativeReceiveFileStore()
    @MainActor
    private lazy var p2pTransferClient = NativeP2PTransferClient(
        authStore: authStore,
        identityStore: deviceIdentityStore,
        sessionApi: NativeTransferSessionApiClient(),
        signalingClient: signalingClient,
        receiveFileStore: receiveFileStore,
        destinationFor: { [weak self] fileType in
            self?.mediaSaveLocation.destination(for: fileType) ?? .folder
        },
        onReceiveState: { [weak self] state in
            if let state {
                self?.transferStateMachine.updateActiveReceive(state)
            } else {
                self?.transferStateMachine.clearActiveReceive()
            }
        },
        onReceiveCompleted: { [weak self] item in
            self?.prependReceiveHistory(item)
        },
        onReceiverNotice: { [weak self] message in
            self?.transferToastMessage = message
        }
    )
    private let localSendSessionStore = NativeLocalSendSessionStore()
    private let presenceTicker: NativePresenceTicker
    private var friendStoreCancellable: AnyCancellable?
    private lazy var lanDiscovery = NativeLanDiscoveryService(
        queue: queue,
        nickname: { [unowned self] in self.nickname },
        localInfo: { [unowned self] port in self.localSendDeviceInfo(port: port) },
        onIncomingConnection: { [weak self] connection in self?.receiveIncoming(connection) },
        onDevicesChanged: { [weak self] devices in
            Task { @MainActor in
                self?.applyDiscoveredDevices(devices)
            }
        },
        onDiscoveryFailed: { [weak self] in self?.discoveryLabel = "搜索失败" }
    )
    private lazy var transferStateMachine = NativeTransferStateMachine { [weak self] snapshot in
        self?.applyTransferState(snapshot)
    }

    @MainActor
    init() {
        self.authStore = NativeAuthStore()
        self.friendStore = NativeFriendStore(api: NativeFriendApiClient(), authStore: authStore)
        self.presenceTicker = NativePresenceTicker(authStore: authStore, api: NativeFriendApiClient())
        self.nickname = NativeDeviceNicknameStore.loadOrCreate()
        self.mediaSaveLocation = NativeMediaSaveLocation(
            rawValue: UserDefaults.standard.string(forKey: NativeMediaSaveLocation.userDefaultsKey) ?? ""
        ) ?? .folder
        self.receiveHistory = NativeReceiveHistoryStore.load()
        self.friendStoreCancellable = friendStore.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
        nativeReceiveModelLogger.notice("[ReceiveList] model init history=\(self.receiveHistory.count, privacy: .public) items=\(self.receiveHistory.receiveListDiagnosticDescription, privacy: .public)")
        Task { await authStore.bootstrap() }
    }

    var currentDeviceName: String {
        nickname.fullName
    }

    @MainActor
    var canSend: Bool {
        !selectedSendTargets.isEmpty && !selectedItems.isEmpty && transferProgress == nil
    }

    private var selectedItems: [NativeTransferItem] {
        items.filter { selectedItemIds.contains($0.id) }
    }

    @MainActor
    private var selectedSendTargets: [NativeSendDevice] {
        (lanDevices + friendDevices).filter { selectedDeviceIds.contains($0.id) && $0.isConnectable }
    }

    var myDevices: [NativeSendDevice] { [] }

    @MainActor
    var friendDevices: [NativeSendDevice] {
        friendStore.friendDevices.values.flatMap { $0 }.map { device in
            NativeSendDevice(
                id: "friend-\(device.deviceId)",
                name: device.deviceName,
                subtitle: device.subtitle,
                endpoint: Self.placeholderEndpoint,
                transportPath: .p2p,
                receiverUserId: device.ownerUserId,
                receiverDeviceId: device.deviceId,
                receiverEd25519PubB64: device.ed25519PubB64,
                receiverX25519PubB64: device.x25519PubB64,
                platformHint: device.platform,
                online: device.online
            )
        }
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
        ByteCountFormatter.string(fromByteCount: Int64(selectedItems.reduce(0) { $0 + $1.sizeBytes }), countStyle: .file)
    }

    var transferPrimaryFileType: NativeFileType {
        selectedItems.first?.fileType ?? .other
    }

    @MainActor
    private func applyDiscoveredDevices(_ devices: [NativeSendDevice]) {
        lanDevices = devices
        selectedDeviceIds = selectedDeviceIds.intersection(Set((devices + friendDevices).map(\.id)))
        discoveryLabel = devices.isEmpty ? "暂无设备" : "已发现 \(devices.count) 台"
    }

    private func applyTransferState(_ snapshot: NativeTransferStateSnapshot) {
        transferLabel = snapshot.transferLabel
        transferProgress = snapshot.transferProgress
        activeReceive = snapshot.activeReceive
    }

    @MainActor
    func refreshFriendsPresence() {
        Task {
            await friendStore.refreshAll()
        }
    }

    @MainActor
    func startPresence() {
        if !lanDiscovery.startPresence() {
            transferLabel = "接收服务启动失败"
        }
        _ = p2pTransferClient
        if let token = authStore.currentToken() {
            Task {
                do {
                    let identity = try deviceIdentityStore.loadOrCreate()
                    _ = await deviceApiClient.registerDevice(
                        identity: identity,
                        deviceName: currentDeviceName,
                        appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String,
                        token: token
                    )
                    signalingClient.connect(token: token, deviceId: identity.deviceId)
                } catch {
                    transferLabel = "设备注册失败"
                }
            }
        }
        presenceTicker.start(friendStore: friendStore)
    }

    func startDiscovery() {
        discoveryLabel = "正在搜索"
        lanDiscovery.startDiscovery()
    }

    func resetDeviceNickname() {
        nickname = NativeDeviceNicknameStore.regenerate(keeping: nickname.fingerprint)
        lanDiscovery.restartAfterNicknameChange()
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
        if let item = items.first(where: { $0.id == id }) {
            try? FileManager.default.removeItem(at: item.fileURL)
        }
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

    @MainActor
    func sendSelectedItems() {
        let targets = selectedSendTargets
        let payloadItems = selectedItems
        guard !targets.isEmpty, !payloadItems.isEmpty else {
            return
        }

        transferStateMachine.beginSend(title: transferTitle)

        Task {
            let totalBytes = max(payloadItems.reduce(0) { $0 + $1.sizeBytes } * targets.count, 1)
            var sentBytes = 0

            for target in targets {
                if target.transportPath == .p2p {
                    let transferId = UUID().uuidString
                    switch await self.p2pTransferClient.send(
                        payloadItems,
                        to: target,
                        senderName: self.nickname.title,
                        transferId: transferId,
                        totalCompletedBeforeTarget: sentBytes,
                        totalBytes: totalBytes,
                        progressUpdate: { progress in
                            self.transferStateMachine.updateSendProgress(progress)
                        },
                        transportNotice: { message in
                            self.transferToastMessage = message
                        }
                    ) {
                    case .success(let sentTargetBytes):
                        sentBytes += sentTargetBytes
                    case .failure(let error):
                        self.transferFailureMessage = self.p2pFailureMessage(target: target, transferId: transferId, error: error)
                        self.transferLabel = self.p2pFailureOriginalReason(error)
                        self.transferStateMachine.finishSend()
                        return
                    }
                    continue
                }
                guard let sentTargetBytes = await self.transferClient.send(
                    payloadItems,
                    to: target,
                    sender: self.nickname,
                    localInfo: self.localSendDeviceInfo(port: 0),
                    totalCompletedBeforeTarget: sentBytes,
                    totalBytes: totalBytes,
                    progressUpdate: { progress in
                        self.transferStateMachine.updateSendProgress(progress)
                    },
                    activeConnectionUpdate: { connection in
                        self.transferStateMachine.setActiveSendConnection(connection)
                    }
                ) else {
                    self.transferStateMachine.finishSend()
                    return
                }
                sentBytes += sentTargetBytes
            }

            self.transferStateMachine.finishSend()
        }
    }

    private func p2pFailureMessage(target: NativeSendDevice, transferId: String, error: NativeAccountError) -> String {
        let diagnostic = p2pDiagnostic(error)
        let deviceId = target.receiverDeviceId?.nilIfBlank ?? "未知设备"
        let userId = target.receiverUserId?.nilIfBlank ?? "未知用户"
        let receiverPlatform = target.platformHint?.nilIfBlank ?? "未知"
        let onlineSnapshot = target.online ? "在线" : "离线"
        var lines: [String] = []
        if let title = diagnostic.failureReasonTitle, let suggestion = diagnostic.failureReasonSuggestion {
            lines.append("失败原因：\(title)")
            lines.append("建议：\(suggestion)")
            lines.append("")
        }
        lines.append("目标：\(target.name)")
        lines.append("用户：\(userId)")
        lines.append("设备：\(deviceId)")
        lines.append("传输：\(transferId)")
        lines.append("会话：\(diagnostic.sessionId)")
        lines.append("路径：P2P")
        lines.append("发送端：iOS")
        lines.append("接收端：\(receiverPlatform)")
        lines.append("在线快照：\(onlineSnapshot)")
        lines.append("阶段：\(diagnostic.stage)")
        lines.append("原始原因：\(diagnostic.originalReason)")
        lines.append("direct_attempt_plan：\(diagnostic.directAttemptPlan)")
        lines.append("direct_endpoint_count：\(diagnostic.directEndpointCount)")
        lines.append("direct_endpoints：\(diagnostic.directEndpoints)")
        lines.append("direct_candidates：\(diagnostic.directCandidates)")
        lines.append("direct_nat_diagnostic：\(diagnostic.directNatDiagnostic)")
        lines.append("direct_selected：\(diagnostic.directSelected)")
        lines.append("direct_attempt_result：\(diagnostic.directAttemptResult)")
        lines.append("direct_last_error：\(diagnostic.directLastError)")
        lines.append("offer_sent：\(diagnostic.offerSent)")
        lines.append("answer_received：\(diagnostic.answerReceived)")
        lines.append("local_ice_count：\(diagnostic.localIceCount)")
        lines.append("remote_ice_count：\(diagnostic.remoteIceCount)")
        lines.append("ice_server_urls：\(diagnostic.iceServerUrls)")
        lines.append("local_candidate_types：\(diagnostic.localCandidateTypes)")
        lines.append("remote_candidate_types：\(diagnostic.remoteCandidateTypes)")
        lines.append("local_candidate_details：\(diagnostic.localCandidateDetails)")
        lines.append("remote_candidate_details：\(diagnostic.remoteCandidateDetails)")
        lines.append("ice_connection_state：\(diagnostic.iceConnectionState)")
        lines.append("peer_connection_state：\(diagnostic.peerConnectionState)")
        lines.append("ice_gathering_state：\(diagnostic.iceGatheringState)")
        lines.append("signaling_state：\(diagnostic.signalingState)")
        lines.append("data_channel_state：\(diagnostic.dataChannelState)")
        lines.append("send_failure：\(diagnostic.sendFailure)")
        lines.append("ice_candidate_errors：\(diagnostic.iceCandidateErrors)")
        lines.append("selected_candidate_pair：\(diagnostic.selectedCandidatePair)")
        lines.append("ice_candidate_pair_stats：\(diagnostic.iceCandidatePairStats)")
        lines.append("stun_error_rate：\(diagnostic.stunErrorRate)")
        lines.append("gathering_incomplete：\(diagnostic.gatheringIncomplete)")
        lines.append("symmetric_nat_suspect：\(diagnostic.symmetricNatSuspect)")
        lines.append("remote_only_mdns：\(diagnostic.remoteOnlyMdns)")
        lines.append("failure_reason_code：\(diagnostic.failureReasonCode)")
        return lines.joined(separator: "\n")
    }

    private func p2pFailureOriginalReason(_ error: NativeAccountError) -> String {
        p2pDiagnostic(error).originalReason
    }

    private func p2pDiagnostic(_ error: NativeAccountError) -> NativeP2PFailureDiagnostic {
        switch error {
        case .server(let code, let message):
            let fields = message.p2pDiagnosticFields
            let reasonCode = fields["failure_reason_code"]?.nilIfBlank ?? "unknown"
            let reason = NativeP2PFailureReason(rawValue: reasonCode)
            return NativeP2PFailureDiagnostic(
                stage: fields["阶段"]?.nilIfBlank ?? "unknown",
                sessionId: fields["会话"]?.nilIfBlank ?? "未创建/未知",
                originalReason: fields["原始原因"]?.nilIfBlank ?? "\(message.nilIfBlank ?? "服务端错误")（\(code)）",
                directAttemptPlan: fields["direct_attempt_plan"]?.nilIfBlank ?? "unknown",
                directEndpointCount: fields["direct_endpoint_count"]?.nilIfBlank ?? "0",
                directEndpoints: fields["direct_endpoints"]?.nilIfBlank ?? "none",
                directCandidates: fields["direct_candidates"]?.nilIfBlank ?? "none",
                directNatDiagnostic: fields["direct_nat_diagnostic"]?.nilIfBlank ?? "none",
                directSelected: fields["direct_selected"]?.nilIfBlank ?? "none",
                directAttemptResult: fields["direct_attempt_result"]?.nilIfBlank ?? "not_attempted",
                directLastError: fields["direct_last_error"]?.nilIfBlank ?? "none",
                offerSent: fields["offer_sent"]?.nilIfBlank ?? "false",
                answerReceived: fields["answer_received"]?.nilIfBlank ?? "false",
                localIceCount: fields["local_ice_count"]?.nilIfBlank ?? "0",
                remoteIceCount: fields["remote_ice_count"]?.nilIfBlank ?? "0",
                iceServerUrls: fields["ice_server_urls"]?.nilIfBlank ?? "unknown",
                localCandidateTypes: fields["local_candidate_types"]?.nilIfBlank ?? "none",
                remoteCandidateTypes: fields["remote_candidate_types"]?.nilIfBlank ?? "none",
                localCandidateDetails: fields["local_candidate_details"]?.nilIfBlank ?? "none",
                remoteCandidateDetails: fields["remote_candidate_details"]?.nilIfBlank ?? "none",
                iceConnectionState: fields["ice_connection_state"]?.nilIfBlank ?? "unknown",
                peerConnectionState: fields["peer_connection_state"]?.nilIfBlank ?? "unknown",
                iceGatheringState: fields["ice_gathering_state"]?.nilIfBlank ?? "unknown",
                signalingState: fields["signaling_state"]?.nilIfBlank ?? "unknown",
                dataChannelState: fields["data_channel_state"]?.nilIfBlank ?? "unknown",
                sendFailure: fields["send_failure"]?.nilIfBlank ?? "none",
                iceCandidateErrors: fields["ice_candidate_errors"]?.nilIfBlank ?? "none",
                selectedCandidatePair: fields["selected_candidate_pair"]?.nilIfBlank ?? "none",
                iceCandidatePairStats: fields["ice_candidate_pair_stats"]?.nilIfBlank ?? "none",
                stunErrorRate: fields["stun_error_rate"]?.nilIfBlank ?? "0.00",
                gatheringIncomplete: fields["gathering_incomplete"]?.nilIfBlank ?? "false",
                symmetricNatSuspect: fields["symmetric_nat_suspect"]?.nilIfBlank ?? "false",
                remoteOnlyMdns: fields["remote_only_mdns"]?.nilIfBlank ?? "false",
                failureReasonCode: reasonCode,
                failureReasonTitle: reason?.title ?? fields["失败原因"]?.nilIfBlank,
                failureReasonSuggestion: reason?.suggestion ?? fields["建议"]?.nilIfBlank
            )
        default:
            return NativeP2PFailureDiagnostic(
                stage: "unknown",
                sessionId: "未创建/未知",
                originalReason: error.displayMessage,
                directAttemptPlan: "unknown",
                directEndpointCount: "0",
                directEndpoints: "none",
                directCandidates: "none",
                directNatDiagnostic: "none",
                directSelected: "none",
                directAttemptResult: "not_attempted",
                directLastError: "none",
                offerSent: "false",
                answerReceived: "false",
                localIceCount: "0",
                remoteIceCount: "0",
                iceServerUrls: "unknown",
                localCandidateTypes: "none",
                remoteCandidateTypes: "none",
                localCandidateDetails: "none",
                remoteCandidateDetails: "none",
                iceConnectionState: "unknown",
                peerConnectionState: "unknown",
                iceGatheringState: "unknown",
                signalingState: "unknown",
                dataChannelState: "unknown",
                sendFailure: "none",
                iceCandidateErrors: "none",
                selectedCandidatePair: "none",
                iceCandidatePairStats: "none",
                stunErrorRate: "0.00",
                gatheringIncomplete: "false",
                symmetricNatSuspect: "false",
                remoteOnlyMdns: "false",
                failureReasonCode: "unknown",
                failureReasonTitle: nil,
                failureReasonSuggestion: nil
            )
        }
    }

    func pauseTransfer() {
        transferStateMachine.pauseSend()
    }

    func cancelTransfer() {
        transferStateMachine.cancelSend()
    }

    @MainActor
    func cancelReceiveTransfer() {
        if let activeReceive {
            p2pTransferClient.cancelReceiveTransfer(activeReceive.id)
        }
        transferStateMachine.cancelReceive()
    }

    @MainActor
    func acceptReceiveTransfer() {
        guard let activeReceive else {
            return
        }
        p2pTransferClient.acceptReceiveTransfer(activeReceive.id)
    }

    func deleteReceiveHistory(_ item: NativeReceiveHistoryItem, deleteFiles: Bool, completion: @escaping (Int) -> Void) {
        let beforeCount = receiveHistory.count
        nativeReceiveModelLogger.notice("[ReceiveList] model delete begin id=\(String(item.id.uuidString.prefix(8)), privacy: .public) deleteFiles=\(deleteFiles ? 1 : 0, privacy: .public) before=\(beforeCount, privacy: .public)")
        receiveHistory.removeAll { $0.id == item.id }
        NativeReceiveHistoryStore.save(receiveHistory)
        nativeReceiveModelLogger.notice("[ReceiveList] model delete saved id=\(String(item.id.uuidString.prefix(8)), privacy: .public) before=\(beforeCount, privacy: .public) after=\(self.receiveHistory.count, privacy: .public) items=\(self.receiveHistory.receiveListDiagnosticDescription, privacy: .public)")
        guard deleteFiles else {
            completion(0)
            return
        }
        receiveFileStore.deleteFiles(item.files, completion: completion)
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
                self.transferStateMachine.setActiveReceiveConnection(connection)
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
                self.transferStateMachine.setActiveReceiveConnection(connection)
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
                let response = self.localSendSessionStore.prepare(request)
                self.updateActiveReceiveForLocalSendPrepare(request, sessionId: response.sessionId)
                self.sendHttpResponse(connection, statusCode: 200, body: response.jsonData)
            }
        case ("POST", "/api/localsend/v2/upload"):
            handleLocalSendUpload(connection: connection, request: request, bodyPrefix: bodyPrefix)
        case ("POST", "/api/localsend/v2/cancel"):
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                if let sessionId = request.query["sessionId"] {
                    self.localSendSessionStore.cancel(sessionId)
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
        lanDiscovery.listenerPort
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

    private func updateActiveReceiveForLocalSendPrepare(_ request: NativeLocalSendPrepareUploadRequest, sessionId: String) {
        let transferFiles = request.files.map {
            NativeTransferFileMetadata(
                displayName: $0.fileName,
                fileType: NativeFileType(mimeType: $0.fileType),
                sizeBytes: $0.size
            )
        }
        transferStateMachine.updateActiveReceive(
            NativeReceiveTransferState(
                id: sessionId,
                senderName: request.info.alias,
                files: transferFiles,
                totalBytes: request.files.reduce(0) { $0 + $1.size },
                receivedBytes: 0,
                requiresConfirmation: false
            )
        )
    }

    private func handleLocalSendUpload(
        connection: NWConnection,
        request: NativeHttpRequest,
        bodyPrefix: Data
    ) {
        guard let sessionId = request.query["sessionId"],
              let fileId = request.query["fileId"],
              let token = request.query["token"],
              let file = localSendSessionStore.sessionFile(sessionId: sessionId, fileId: fileId, token: token) else {
            receiveHttpBody(connection: connection, initialBody: bodyPrefix, expectedLength: request.contentLength) { _ in
                self.sendHttpResponse(connection, statusCode: 403, body: Data(#"{"error":"invalid upload token"}"#.utf8))
            }
            return
        }

        guard let preparedFile = receiveFileStore.prepareTemporaryFile(fileName: file.metadata.fileName),
              let handle = try? FileHandle(forWritingTo: preparedFile.temporaryURL) else {
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
            self.transferStateMachine.incrementActiveReceive(id: sessionId, receivedBytes: writableCount)
        }

        func finishUpload() {
            try? handle.close()
            if let expectedHash = file.metadata.sha256 {
                let actualHash = hasher.finalize().hexString
                guard actualHash.caseInsensitiveCompare(expectedHash) == .orderedSame else {
                    try? FileManager.default.removeItem(at: preparedFile.temporaryURL)
                    sendHttpResponse(connection, statusCode: 400, body: Data(#"{"error":"sha256 mismatch"}"#.utf8))
                    return
                }
            }
            let fileType = NativeFileType(mimeType: file.metadata.fileType)
            let saveDestination = mediaSaveLocation.destination(for: fileType)
            receiveFileStore.saveUploadedFile(
                preparedFile,
                displayName: file.metadata.fileName,
                fileType: fileType,
                sizeBytes: file.metadata.size,
                destination: saveDestination
            ) { received in
                guard let received else {
                    self.sendHttpResponse(connection, statusCode: 500, body: Data(#"{"error":"cannot save file"}"#.utf8))
                    return
                }
                DispatchQueue.main.async {
                    self.prependReceiveHistory(
                        NativeReceiveHistoryItem(
                            title: received.displayName,
                            subtitle: ByteCountFormatter.string(fromByteCount: Int64(file.metadata.size), countStyle: .file),
                            fileCount: 1,
                            primaryFileType: received.fileType,
                            mediaPreviewData: received.mediaPreviewData,
                            files: [NativeReceiveHistoryFile(file: received)]
                        )
                    )
                    self.transferStateMachine.clearActiveReceive(id: sessionId)
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
                self.transferStateMachine.updateActiveReceive(
                    NativeReceiveTransferState(
                        id: transferId,
                        senderName: envelope.senderName,
                        files: envelope.files,
                        totalBytes: envelope.totalBytes,
                        receivedBytes: envelope.receivedBytes,
                        requiresConfirmation: false
                    )
                )
                if let transfer = envelope.transfer {
                    self.transferStateMachine.clearActiveReceiveConnection(ifSame: connection)
                    self.receiveFileStore.save(
                        transfer,
                        destinationFor: { self.mediaSaveLocation.destination(for: $0) }
                    ) { item in
                        guard let item else {
                            self.transferStateMachine.clearActiveReceive(id: transferId)
                            return
                        }
                        self.prependReceiveHistory(item)
                        self.transferStateMachine.clearActiveReceive(id: transferId)
                    }
                    connection.cancel()
                    return
                }
            }

            if isComplete {
                self.transferStateMachine.clearActiveReceive(id: transferId)
                self.transferStateMachine.clearActiveReceiveConnection(ifSame: connection)
                connection.cancel()
            } else {
                self.receiveNextChunk(from: connection, transferId: transferId, buffer: nextBuffer)
            }
        }
    }

    private func prependReceiveHistory(_ item: NativeReceiveHistoryItem) {
        let beforeCount = receiveHistory.count
        nativeReceiveModelLogger.notice("[ReceiveList] model prepend begin id=\(String(item.id.uuidString.prefix(8)), privacy: .public) before=\(beforeCount, privacy: .public) files=\(item.fileCount, privacy: .public)")
        receiveHistory.insert(item, at: 0)
        NativeReceiveHistoryStore.save(receiveHistory)
        nativeReceiveModelLogger.notice("[ReceiveList] model prepend saved id=\(String(item.id.uuidString.prefix(8)), privacy: .public) before=\(beforeCount, privacy: .public) after=\(self.receiveHistory.count, privacy: .public) items=\(self.receiveHistory.receiveListDiagnosticDescription, privacy: .public)")
    }

    private static var placeholderEndpoint: NWEndpoint {
        .hostPort(host: NWEndpoint.Host("127.0.0.1"), port: NWEndpoint.Port(rawValue: 9)!)
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
    var transportPath: NativeSendTransportPath = .lan
    var receiverUserId: String?
    var receiverDeviceId: String?
    var receiverEd25519PubB64: String?
    var receiverX25519PubB64: String?
    var platformHint: String? = nil
    var online: Bool = true

    var isConnectable: Bool {
        switch transportPath {
        case .lan:
            return true
        case .p2p:
            return receiverUserId?.nilIfBlank != nil &&
                receiverDeviceId?.nilIfBlank != nil &&
                receiverEd25519PubB64?.nilIfBlank != nil &&
                receiverX25519PubB64?.nilIfBlank != nil
        }
    }
}

enum NativeSendTransportPath {
    case lan
    case p2p
}

extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    var p2pDiagnosticFields: [String: String] {
        split(separator: "\n").reduce(into: [:]) { result, line in
            let parts = line.split(separator: "：", maxSplits: 1).map(String.init)
            if parts.count == 2 {
                result[parts[0]] = parts[1]
            }
        }
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
        return name.nilIfBlank ?? "对方"
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

private extension Array where Element == NativeReceiveHistoryItem {
    var receiveListDiagnosticDescription: String {
        map { item in
            "history(id:\(String(item.id.uuidString.prefix(8))),files:\(item.fileCount),type:\(item.primaryFileType.rawValue))"
        }.joined(separator: "|")
    }
}
