import CryptoKit
import Foundation

@MainActor
final class NativeP2PTransferClient {
    let authStore: NativeAuthStore
    let identityStore: NativeDeviceIdentityStore
    let sessionApi: NativeTransferSessionApiClient
    private let signalingClient: NativeSignalingClient
    private let receiveFileStore: NativeReceiveFileStore
    private let destinationFor: (NativeFileType) -> NativeReceiveSaveDestination
    private let onReceiveState: (NativeReceiveTransferState?) -> Void
    private let onReceiveCompleted: (NativeReceiveHistoryItem) -> Void
    private var sessions: [String: NativeWebRTCSession] = [:]
    private var receivers: [String: NativeP2PReceiver] = [:]
    private var pendingSignals: [String: [[String: Any]]] = [:]
    private var senderAbortCallbacks: [String: () -> Void] = [:]
    private let progressStore = NativeTransferProgressStore()
    private let maxInFlightChunks = 8

    init(
        authStore: NativeAuthStore,
        identityStore: NativeDeviceIdentityStore,
        sessionApi: NativeTransferSessionApiClient,
        signalingClient: NativeSignalingClient,
        receiveFileStore: NativeReceiveFileStore,
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        onReceiveState: @escaping (NativeReceiveTransferState?) -> Void,
        onReceiveCompleted: @escaping (NativeReceiveHistoryItem) -> Void
    ) {
        self.authStore = authStore
        self.identityStore = identityStore
        self.sessionApi = sessionApi
        self.signalingClient = signalingClient
        self.receiveFileStore = receiveFileStore
        self.destinationFor = destinationFor
        self.onReceiveState = onReceiveState
        self.onReceiveCompleted = onReceiveCompleted
        self.signalingClient.onMessage = { [weak self] message in
            Task { @MainActor in
                self?.handle(message)
            }
        }
    }

    private func createSession(
        target: NativeSendDevice,
        transferId: String,
        manifestFiles: [NativeTransferV3ManifestInput]
    ) async -> Result<NativeP2PSendSession, NativeAccountError> {
        guard let token = authStore.currentToken() else {
            return .failure(p2pError(stage: "create_session", sessionId: nil, code: "SESSION_EXPIRED", message: "登录已过期"))
        }
        guard let receiverUserId = target.receiverUserId,
              let receiverDeviceId = target.receiverDeviceId,
              let receiverEd25519PubB64 = target.receiverEd25519PubB64,
              let receiverX25519PubB64 = target.receiverX25519PubB64 else {
            return .failure(p2pError(stage: "create_session", sessionId: nil, code: "TARGET_DEVICE_INCOMPLETE", message: "目标 P2P 信息缺失"))
        }
        do {
            let identity = try identityStore.loadOrCreate()
            let ephemeral = NativeTransferProtocolV3.generateEphemeralKeyPair()
            let manifestHashB64 = manifestFiles.manifestHashB64
            let inviteSignatureB64 = try NativeTransferProtocolV3.signInvite(
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicKeyB64: ephemeral.publicKeyB64,
                ed25519PrivateKeyB64: identity.ed25519PrivateB64
            )
            switch await sessionApi.createSession(
                receiverUserId: receiverUserId,
                receiverDeviceId: receiverDeviceId,
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderX25519EphPubB64: ephemeral.publicKeyB64,
                senderDeviceId: identity.deviceId,
                senderInviteSignatureB64: inviteSignatureB64,
                token: token
            ) {
            case .success(let config):
                return .success(
                    NativeP2PSendSession(
                        config: config,
                        ephemeral: ephemeral,
                        senderX25519PrivateB64: identity.x25519PrivateB64,
                        receiverEd25519PubB64: receiverEd25519PubB64,
                        receiverX25519PubB64: receiverX25519PubB64,
                        manifestHashB64: manifestHashB64
                    )
                )
            case .failure(let error):
                return .failure(p2pError(stage: "create_session", sessionId: nil, code: error.p2pCode, message: error.p2pOriginalReason))
            }
        } catch {
            return .failure(p2pError(stage: "create_session", sessionId: nil, code: "CREATE_SESSION_FAILED", message: error.localizedDescription))
        }
    }

    private func p2pError(
        stage: String,
        sessionId: String?,
        code: String,
        message: String,
        diagnostic: NativeWebRTCDiagnostic = .empty
    ) -> NativeAccountError {
        .server(
            code: code,
            message: [
                "阶段：\(stage)",
                "会话：\(sessionId?.nilIfBlank ?? "未创建/未知")",
                "原始原因：\(message.nilIfBlank ?? code)",
                "offer_sent：\(diagnostic.offerSent)",
                "answer_received：\(diagnostic.answerReceived)",
                "local_ice_count：\(diagnostic.localIceCount)",
                "remote_ice_count：\(diagnostic.remoteIceCount)",
                "ice_server_urls：\(diagnostic.iceServerUrls)",
                "local_candidate_types：\(diagnostic.localCandidateTypes)",
                "remote_candidate_types：\(diagnostic.remoteCandidateTypes)",
                "local_candidate_details：\(diagnostic.localCandidateDetails)",
                "remote_candidate_details：\(diagnostic.remoteCandidateDetails)",
                "ice_connection_state：\(diagnostic.iceConnectionState)",
                "ice_gathering_state：\(diagnostic.iceGatheringState)",
                "signaling_state：\(diagnostic.signalingState)",
                "data_channel_state：\(diagnostic.dataChannelState)",
                "ice_candidate_errors：\(diagnostic.iceCandidateErrors)",
                "selected_candidate_pair：\(diagnostic.selectedCandidatePair)",
                "ice_candidate_pair_stats：\(diagnostic.iceCandidatePairStats)"
            ].joined(separator: "\n")
        )
    }

    func send(
        _ items: [NativeTransferItem],
        to target: NativeSendDevice,
        senderName: String,
        transferId: String,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int,
        progressUpdate: @escaping (Double) -> Void
    ) async -> Result<Int, NativeAccountError> {
        guard let manifestFiles = items.toManifestInputs() else {
            return .failure(p2pError(stage: "send_manifest", sessionId: nil, code: "MANIFEST_INPUT_FAILED", message: "无法读取待发送文件清单"))
        }
        switch await createSession(target: target, transferId: transferId, manifestFiles: manifestFiles) {
        case .failure(let error):
            return .failure(error)
        case .success(let sessionContext):
            let config = sessionContext.config
            let receiverReadyTracker = NativeReceiverReadyTracker()
            let ackTracker = NativeChunkAckTracker(totalChunks: manifestFiles.reduce(0) { $0 + NativeTransferProtocolV3.chunkCount(sizeBytes: $1.sizeBytes) })
            var sentFrames: [String: Data] = [:]
            var chunkByteCounts: [String: Int] = [:]
            var confirmedBytes = 0
            var queuedChunks = 0
            var negotiatedSessionKey: Data?
            var sessionRef: NativeWebRTCSession?
            let session = makeSession(
                sessionId: config.sessionId,
                iceServers: config.iceServers,
                onBinary: { data in
                    guard let sessionKey = negotiatedSessionKey else {
                        return
                    }
                    guard let frame = try? NativeTransferProtocolV3.decodeFrame(
                        sessionKey: sessionKey,
                        sessionId: config.sessionId,
                        transferId: transferId,
                        frame: data
                    ) else {
                        return
                    }
                    switch frame {
                    case .ready:
                        receiverReadyTracker.markReady()
                    case .ack(let fileIndex, let chunkIndex):
                        let key = chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)
                        if ackTracker.markAck(fileIndex: fileIndex, chunkIndex: chunkIndex) {
                            confirmedBytes += chunkByteCounts[key] ?? 0
                            progressUpdate(Double(min(totalCompletedBeforeTarget + confirmedBytes, totalBytes)) / Double(max(totalBytes, 1)))
                        }
                    case .retry(let fileIndex, let chunkIndex):
                        if let frame = sentFrames[chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)] {
                            Task { @MainActor in
                                _ = await sessionRef?.send(frame)
                            }
                        }
                    case .manifest, .chunk:
                        break
                    }
                }
            )
            sessionRef = session
            sessions[config.sessionId] = session
            senderAbortCallbacks[config.sessionId] = { [weak receiverReadyTracker, weak ackTracker] in
                receiverReadyTracker?.abort()
                ackTracker?.abort()
            }
            guard await session.createOffer() else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                closeSession(config.sessionId)
                return .failure(p2pError(stage: "data_channel_open", sessionId: config.sessionId, code: "OFFER_FAILED", message: "WebRTC offer 创建失败", diagnostic: diagnostic))
            }
            var opened = await session.waitUntilOpen(seconds: 15)
            if !opened && !receiverReadyTracker.isAborted {
                _ = await session.restartIce()
                opened = await session.waitUntilOpen(seconds: 15)
            }
            guard opened else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                let abortedByPeer = receiverReadyTracker.isAborted
                closeSession(config.sessionId)
                let message = abortedByPeer ? "对方已取消接收" : Self.crossNetworkDiagnosis(diagnostic)
                return .failure(p2pError(stage: "data_channel_open", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "DATA_CHANNEL_TIMEOUT", message: message, diagnostic: diagnostic))
            }
            guard let peerHandshake = await session.waitForPeerEphemeralPublic(seconds: 10),
                  NativeTransferProtocolV3.verifyAcceptSignature(
                    sessionId: config.sessionId,
                    transferId: transferId,
                    manifestHashB64: sessionContext.manifestHashB64,
                    senderEphemeralPublicKeyB64: sessionContext.ephemeral.publicKeyB64,
                    receiverEphemeralPublicKeyB64: peerHandshake,
                    signatureB64: session.peerAcceptSignatureB64 ?? "",
                    receiverEd25519PublicKeyB64: sessionContext.receiverEd25519PubB64
                  ),
                  let sessionKey = try? NativeTransferProtocolV3.deriveSessionKey(
                    sessionId: config.sessionId,
                    transferId: transferId,
                    localEphemeralPrivateKeyB64: sessionContext.ephemeral.privateKeyB64,
                    peerEphemeralPublicKeyB64: peerHandshake,
                    localStaticPrivateKeyB64: sessionContext.senderX25519PrivateB64,
                    peerStaticPublicKeyB64: sessionContext.receiverX25519PubB64,
                    role: .sender
                  ) else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                closeSession(config.sessionId)
                return .failure(p2pError(stage: "key_agreement", sessionId: config.sessionId, code: "KEY_AGREEMENT_FAILED", message: "跨网密钥协商失败", diagnostic: diagnostic))
            }
            negotiatedSessionKey = sessionKey
            sessionContext.sessionKey = sessionKey
            let peerCompletedChunks = NativeTransferProgressStore.decodeCompletedBitmap(session.peerCompletedBitmapB64)

            guard let manifestFrame = try? NativeTransferProtocolV3.encodeManifest(
                sessionKey: sessionKey,
                sessionId: config.sessionId,
                transferId: transferId,
                files: manifestFiles,
                senderName: senderName
            ),
            await session.send(manifestFrame) else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                closeSession(config.sessionId)
                return .failure(p2pError(stage: "send_manifest", sessionId: config.sessionId, code: "SEND_MANIFEST_FAILED", message: "传输清单发送失败", diagnostic: diagnostic))
            }
            guard await receiverReadyTracker.wait(seconds: 120) else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                let abortedByPeer = receiverReadyTracker.isAborted
                closeSession(config.sessionId)
                return .failure(p2pError(stage: "receiver_ready", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_RECEIVER_READY_TIMEOUT", message: abortedByPeer ? "对方已取消接收" : "等待接收端确认超时", diagnostic: diagnostic))
            }

            for (fileIndex, item) in items.enumerated() {
                guard let stream = InputStream(url: item.fileURL) else {
                    let diagnostic = await session.diagnosticSnapshotWithStats()
                    closeSession(config.sessionId)
                    return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "OPEN_FILE_FAILED", message: "无法读取待发送文件", diagnostic: diagnostic))
                }
                stream.open()
                defer {
                    stream.close()
                }
                var buffer = [UInt8](repeating: 0, count: NativeTransferProtocolV3.chunkSize)
                var chunkIndex = 0
                while stream.hasBytesAvailable {
                    if Task.isCancelled {
                        let diagnostic = await session.diagnosticSnapshotWithStats()
                        closeSession(config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "TRANSFER_CANCELLED", message: "传输已取消", diagnostic: diagnostic))
                    }
                    let read = stream.read(&buffer, maxLength: buffer.count)
                    guard read >= 0 else {
                        let diagnostic = await session.diagnosticSnapshotWithStats()
                        closeSession(config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "READ_FILE_FAILED", message: "文件分片读取失败", diagnostic: diagnostic))
                    }
                    if read == 0 {
                        break
                    }
                    let key = chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)
                    chunkByteCounts[key] = read
                    if peerCompletedChunks[fileIndex]?.contains(chunkIndex) == true {
                        queuedChunks += 1
                        if ackTracker.markAck(fileIndex: fileIndex, chunkIndex: chunkIndex) {
                            confirmedBytes += read
                            progressUpdate(Double(min(totalCompletedBeforeTarget + confirmedBytes, totalBytes)) / Double(max(totalBytes, 1)))
                        }
                        chunkIndex += 1
                        continue
                    }
                    guard let chunkFrame = try? NativeTransferProtocolV3.encodeChunk(
                        sessionKey: sessionKey,
                        sessionId: config.sessionId,
                        fileIndex: fileIndex,
                        chunkIndex: chunkIndex,
                        plain: Data(buffer.prefix(read))
                    ),
                    await session.send(chunkFrame) else {
                        let diagnostic = await session.diagnosticSnapshotWithStats()
                        closeSession(config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "SEND_CHUNK_FAILED", message: "文件分片发送失败", diagnostic: diagnostic))
                    }
                    sentFrames[key] = chunkFrame
                    queuedChunks += 1
                    chunkIndex += 1
                    let ackTarget = queuedChunks - maxInFlightChunks + 1
                    if ackTarget > 0, !Task.isCancelled {
                        guard await ackTracker.waitForAckedCount(ackTarget, seconds: 30) else {
                            let diagnostic = await session.diagnosticSnapshotWithStats()
                            let abortedByPeer = ackTracker.isAborted
                            closeSession(config.sessionId)
                            return .failure(p2pError(stage: "ack", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_ACK_TIMEOUT", message: abortedByPeer ? "对方已取消接收" : "跨网传输确认超时", diagnostic: diagnostic))
                        }
                    }
                }
            }

            guard await ackTracker.waitForAll(seconds: 30) else {
                let diagnostic = await session.diagnosticSnapshotWithStats()
                let abortedByPeer = ackTracker.isAborted
                closeSession(config.sessionId)
                return .failure(p2pError(stage: "ack", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_ACK_TIMEOUT", message: abortedByPeer ? "对方已取消接收" : "跨网传输确认超时", diagnostic: diagnostic))
            }
            closeSession(config.sessionId)
            if let token = authStore.currentToken() {
                _ = await sessionApi.finishSession(token: token, sessionId: config.sessionId)
            }
            return .success(confirmedBytes)
        }
    }

    private func handle(_ message: [String: Any]) {
        guard let sessionId = message["session_id"] as? String,
              let type = message["type"] as? String else {
            return
        }
        switch type {
        case "invite":
            guard let senderEphemeralPublic = message["sender_x25519_eph_pub_b64"] as? String,
                  let manifestHashB64 = message["manifest_hash_b64"] as? String,
                  let senderInviteSignatureB64 = message["sender_invite_signature_b64"] as? String,
                  let senderEd25519PublicB64 = message["sender_ed25519_pub_b64"] as? String,
                  let senderX25519PublicB64 = message["sender_x25519_pub_b64"] as? String else {
                return
            }
            let transferId = (message["transfer_id"] as? String) ?? sessionId
            guard receiver(
                for: sessionId,
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicB64: senderEphemeralPublic,
                senderInviteSignatureB64: senderInviteSignatureB64,
                senderEd25519PublicB64: senderEd25519PublicB64,
                senderX25519PublicB64: senderX25519PublicB64,
                iceServers: NativeIceServerConfig.parse(message["ice_servers"] as? [[String: Any]]),
                autoAccept: (message["same_account"] as? Bool) ?? false
            ) != nil else {
                return
            }
            onReceiveState(
                NativeReceiveTransferState(
                    id: transferId,
                    senderName: "",
                    files: [],
                    totalBytes: 0,
                    receivedBytes: 0,
                    requiresConfirmation: !((message["same_account"] as? Bool) ?? false)
                )
            )
            flushPendingSignals(for: sessionId)
        case "offer":
            guard let sdp = message["sdp"] as? String else {
                return
            }
            guard let receiver = receivers[sessionId] else {
                bufferSignal(message, for: sessionId)
                return
            }
            let session = sessions[sessionId] ?? makeReceiverSession(sessionId: sessionId, receiver: receiver)
            sessions[sessionId] = session
            flushPendingSignals(for: sessionId)
            Task {
                _ = await session.acceptOffer(sdp)
            }
        case "answer":
            guard let sdp = message["sdp"] as? String else {
                return
            }
            guard let session = sessions[sessionId] else {
                bufferSignal(message, for: sessionId)
                return
            }
            Task {
                await session.acceptAnswer(
                    sdp,
                    peerEphemeralPublicB64: message["receiver_x25519_eph_pub_b64"] as? String,
                    peerAcceptSignatureB64: message["receiver_accept_signature_b64"] as? String,
                    peerCompletedBitmapB64: message["completed_chunks_bitmap_b64"] as? String
                )
            }
        case "ice_candidate":
            guard let session = sessions[sessionId] else {
                bufferSignal(message, for: sessionId)
                return
            }
            Task {
                await session.addCandidate(message)
            }
        case "bye":
            closeSession(sessionId)
        default:
            break
        }
    }

    private func bufferSignal(_ message: [String: Any], for sessionId: String) {
        pendingSignals[sessionId, default: []].append(message)
    }

    private func flushPendingSignals(for sessionId: String) {
        guard let signals = pendingSignals.removeValue(forKey: sessionId) else {
            return
        }
        signals.forEach { handle($0) }
    }

    private func makeReceiverSession(sessionId: String, receiver: NativeP2PReceiver) -> NativeWebRTCSession {
        var sessionRef: NativeWebRTCSession?
        receiver.sendControl = { data in
            Task { @MainActor in
                _ = await sessionRef?.send(data)
            }
        }
        receiver.sendControlBatch = { items in
            Task { @MainActor in
                _ = await sessionRef?.sendBatch(items)
            }
        }
        let session = NativeWebRTCSession(
            sessionId: sessionId,
            iceServers: receiver.iceServers,
            sendSignal: { [weak self] signal in self?.signalingClient.send(signal) },
            onOpen: {},
            onBinary: { data in receiver.receive(data) },
            onClose: { [weak self] in self?.closeSession(sessionId) },
            answerExtras: [
                "receiver_x25519_eph_pub_b64": receiver.receiverEphemeralPublicB64,
                "receiver_accept_signature_b64": receiver.receiverAcceptSignatureB64,
                "completed_chunks_bitmap_b64": receiver.completedBitmapB64 ?? ""
            ]
        )
        sessionRef = session
        return session
    }

    private func makeSession(
        sessionId: String,
        iceServers: [NativeIceServerConfig],
        onBinary: @escaping (Data) -> Void = { _ in }
    ) -> NativeWebRTCSession {
        NativeWebRTCSession(
            sessionId: sessionId,
            iceServers: iceServers,
            sendSignal: { [weak self] signal in self?.signalingClient.send(signal) },
            onOpen: {},
            onBinary: onBinary,
            onClose: { [weak self] in self?.closeSession(sessionId) }
        )
    }

    private func receiver(
        for sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicB64: String,
        senderInviteSignatureB64: String,
        senderEd25519PublicB64: String,
        senderX25519PublicB64: String,
        iceServers: [NativeIceServerConfig],
        autoAccept: Bool
    ) -> NativeP2PReceiver? {
        if let receiver = receivers[sessionId] {
            return receiver
        }
        guard NativeTransferProtocolV3.verifyInviteSignature(
            transferId: transferId,
            manifestHashB64: manifestHashB64,
            senderEphemeralPublicKeyB64: senderEphemeralPublicB64,
            signatureB64: senderInviteSignatureB64,
            senderEd25519PublicKeyB64: senderEd25519PublicB64
        ),
        let identity = try? identityStore.loadOrCreate() else {
            return nil
        }
        let receiverEphemeral = NativeTransferProtocolV3.generateEphemeralKeyPair()
        let completedBitmapB64 = progressStore.completedBitmapB64(transferId: transferId, manifestHashB64: manifestHashB64)
        guard let acceptSignatureB64 = try? NativeTransferProtocolV3.signAccept(
            sessionId: sessionId,
            transferId: transferId,
            manifestHashB64: manifestHashB64,
            senderEphemeralPublicKeyB64: senderEphemeralPublicB64,
            receiverEphemeralPublicKeyB64: receiverEphemeral.publicKeyB64,
            ed25519PrivateKeyB64: identity.ed25519PrivateB64
        ),
        let sessionKey = try? NativeTransferProtocolV3.deriveSessionKey(
            sessionId: sessionId,
            transferId: transferId,
            localEphemeralPrivateKeyB64: receiverEphemeral.privateKeyB64,
            peerEphemeralPublicKeyB64: senderEphemeralPublicB64,
            localStaticPrivateKeyB64: identity.x25519PrivateB64,
            peerStaticPublicKeyB64: senderX25519PublicB64,
            role: .receiver
        ) else {
            return nil
        }
        let receiver = NativeP2PReceiver(
            sessionId: sessionId,
            transferId: transferId,
            sessionKey: sessionKey,
            receiverEphemeralPublicB64: receiverEphemeral.publicKeyB64,
            receiverAcceptSignatureB64: acceptSignatureB64,
            completedBitmapB64: completedBitmapB64,
            iceServers: iceServers,
            manifestHashB64: manifestHashB64,
            progressStore: progressStore,
            autoAccept: autoAccept,
            receiveFileStore: receiveFileStore,
            destinationFor: destinationFor,
            onReceiveState: onReceiveState,
            onReceiveCompleted: { [weak self] item in
                self?.onReceiveCompleted(item)
                self?.receivers.removeValue(forKey: sessionId)
                self?.sessions.removeValue(forKey: sessionId)
            }
        )
        receivers[sessionId] = receiver
        return receiver
    }

    private func closeSession(_ sessionId: String) {
        sessions.removeValue(forKey: sessionId)?.close()
        receivers.removeValue(forKey: sessionId)
        senderAbortCallbacks.removeValue(forKey: sessionId)?()
        onReceiveState(nil)
    }

    func acceptReceiveTransfer(_ transferId: String) {
        receivers.values.first { $0.transferId == transferId }?.accept()
    }

    func cancelReceiveTransfer(_ transferId: String) {
        guard let entry = receivers.first(where: { $0.value.transferId == transferId }) else {
            return
        }
        let sessionId = entry.key
        entry.value.cancel()
        receivers.removeValue(forKey: sessionId)
        sessions.removeValue(forKey: sessionId)?.close()
        pendingSignals.removeValue(forKey: sessionId)
        signalingClient.send([
            "type": "bye",
            "session_id": sessionId,
            "reason": "receiver_canceled",
        ])
    }

    static func crossNetworkDiagnosis(_ diag: NativeWebRTCDiagnostic) -> String {
        let localTypes = Set(diag.localCandidateTypes.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
        let remoteTypes = Set(diag.remoteCandidateTypes.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
        let hasRelay = localTypes.contains("relay") || remoteTypes.contains("relay")
        let allowed: Set<String> = ["host", "srflx", "none"]
        let onlySrflxAndHost = !hasRelay && localTypes.isSubset(of: allowed) && remoteTypes.isSubset(of: allowed)
        if onlySrflxAndHost {
            return "双方 NAT 类型不兼容，直连穿透失败。请尝试连接同一 Wi-Fi"
        }
        if diag.localCandidateTypes == "none" {
            return "本机未能获取任何网络候选，请检查网络连接"
        }
        if diag.remoteCandidateTypes == "none" {
            return "对方未能获取任何网络候选，请确认对方网络正常"
        }
        return "跨网直连超时"
    }
}

@MainActor
private final class NativeP2PReceiver {
    private let sessionId: String
    let transferId: String
    let receiverEphemeralPublicB64: String
    let receiverAcceptSignatureB64: String
    let completedBitmapB64: String?
    let iceServers: [NativeIceServerConfig]
    private let receiveFileStore: NativeReceiveFileStore
    private let manifestHashB64: String
    private let progressStore: NativeTransferProgressStore
    private let autoAccept: Bool
    private let destinationFor: (NativeFileType) -> NativeReceiveSaveDestination
    private let onReceiveState: (NativeReceiveTransferState?) -> Void
    private let onReceiveCompleted: (NativeReceiveHistoryItem) -> Void
    private let sessionKey: Data
    var sendControl: (Data) -> Void = { _ in }
    var sendControlBatch: (([Data]) -> Void)?
    private var senderName = "跨网设备"
    private var files: [NativeTransferV3File] = []
    private var fileChunks: [Int: [Int: Data]] = [:]
    private var completedChunks: [Int: Set<Int>] = [:]
    private var hashRetryCount: [Int: Int] = [:]
    private var pendingChunkFrames: [Data] = []
    private var pendingAckFrames: [Data] = []
    private var ackFlushScheduled = false
    private var confirmed = false
    private var canceled = false
    private var readySent = false
    private var totalBytes = 0
    private var receivedBytes = 0

    init(
        sessionId: String,
        transferId: String,
        sessionKey: Data,
        receiverEphemeralPublicB64: String,
        receiverAcceptSignatureB64: String,
        completedBitmapB64: String?,
        iceServers: [NativeIceServerConfig],
        manifestHashB64: String,
        progressStore: NativeTransferProgressStore,
        autoAccept: Bool,
        receiveFileStore: NativeReceiveFileStore,
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        onReceiveState: @escaping (NativeReceiveTransferState?) -> Void,
        onReceiveCompleted: @escaping (NativeReceiveHistoryItem) -> Void
    ) {
        self.sessionId = sessionId
        self.transferId = transferId
        self.sessionKey = sessionKey
        self.receiverEphemeralPublicB64 = receiverEphemeralPublicB64
        self.receiverAcceptSignatureB64 = receiverAcceptSignatureB64
        self.completedBitmapB64 = completedBitmapB64
        self.iceServers = iceServers
        self.manifestHashB64 = manifestHashB64
        self.progressStore = progressStore
        self.autoAccept = autoAccept
        self.receiveFileStore = receiveFileStore
        self.destinationFor = destinationFor
        self.onReceiveState = onReceiveState
        self.onReceiveCompleted = onReceiveCompleted
    }

    func receive(_ data: Data) {
        if canceled {
            return
        }
        let decoded = try? NativeTransferProtocolV3.decodeFrame(
            sessionKey: sessionKey,
            sessionId: sessionId,
            transferId: transferId,
            frame: data
        )
        guard let frame = decoded else {
            if let header = NativeTransferProtocolV3.peekFrameHeader(data), header.isChunk {
                sendRetry(fileIndex: header.fileIndex, chunkIndex: header.chunkIndex)
            }
            return
        }
        switch frame {
        case .manifest(let manifest):
            senderName = manifest.senderName
            files = manifest.files
            totalBytes = files.reduce(0) { $0 + $1.sizeBytes }
            let restored = progressStore.completedChunks(transferId: transferId, manifestHashB64: manifestHashB64, manifest: files)
            fileChunks = Dictionary(uniqueKeysWithValues: files.map { ($0.index, [:]) })
            for file in files {
                let restoredSet = restored[file.index] ?? []
                completedChunks[file.index] = restoredSet
                for chunkIndex in restoredSet {
                    if let data = progressStore.chunkData(transferId: transferId, fileIndex: file.index, chunkIndex: chunkIndex) {
                        fileChunks[file.index, default: [:]][chunkIndex] = data
                    }
                }
            }
            receivedBytes = files.reduce(0) { total, file in
                total + (completedChunks[file.index] ?? []).reduce(0) { $0 + expectedChunkLength(file: file, chunkIndex: $1) }
            }
            progressStore.save(transferId: transferId, manifestHashB64: manifestHashB64, manifest: files, completedChunks: completedChunks)
            let wasAlreadyConfirmed = confirmed
            confirmed = confirmed || autoAccept
            if wasAlreadyConfirmed {
                publishReceiveState(requiresConfirmation: false)
            } else {
                publishReceiveState(requiresConfirmation: !autoAccept)
            }
            if confirmed {
                sendReadyAndDrainPending()
            }
        case .chunk(let fileIndex, let chunkIndex, let bytes):
            guard confirmed else {
                pendingChunkFrames.append(data)
                return
            }
            guard let file = files.first(where: { $0.index == fileIndex }),
                  chunkIndex >= 0,
                  chunkIndex < file.chunkCount,
                  bytes.count == expectedChunkLength(file: file, chunkIndex: chunkIndex) else {
                sendRetry(fileIndex: fileIndex, chunkIndex: chunkIndex)
                return
            }
            if completedChunks[fileIndex]?.contains(chunkIndex) != true {
                fileChunks[fileIndex, default: [:]][chunkIndex] = bytes
                completedChunks[fileIndex, default: []].insert(chunkIndex)
                receivedBytes += bytes.count
                progressStore.saveChunk(transferId: transferId, fileIndex: fileIndex, chunkIndex: chunkIndex, data: bytes)
                progressStore.save(transferId: transferId, manifestHashB64: manifestHashB64, manifest: files, completedChunks: completedChunks)
            }
            sendAck(fileIndex: fileIndex, chunkIndex: chunkIndex)
            publishReceiveState()
            finishIfComplete()
        case .ready, .ack, .retry:
            break
        }
    }

    func accept() {
        guard !confirmed, !canceled else {
            return
        }
        confirmed = true
        if files.isEmpty {
            onReceiveState(
                NativeReceiveTransferState(
                    id: transferId,
                    senderName: "",
                    files: [],
                    totalBytes: 0,
                    receivedBytes: 0,
                    requiresConfirmation: false
                )
            )
            return
        }
        publishReceiveState(requiresConfirmation: false)
        sendReadyAndDrainPending()
    }

    func cancel() {
        canceled = true
        pendingChunkFrames.removeAll(keepingCapacity: false)
        pendingAckFrames.removeAll(keepingCapacity: false)
        fileChunks.removeAll(keepingCapacity: false)
        completedChunks.removeAll(keepingCapacity: false)
        progressStore.clear(transferId: transferId)
        onReceiveState(nil)
    }

    private func publishReceiveState(requiresConfirmation: Bool = false) {
        onReceiveState(
            NativeReceiveTransferState(
                id: transferId,
                senderName: senderName,
                files: files.map(\.metadata),
                totalBytes: totalBytes,
                receivedBytes: min(receivedBytes, totalBytes),
                requiresConfirmation: requiresConfirmation
            )
        )
    }

    private func finishIfComplete() {
        guard !files.isEmpty, receivedBytes >= totalBytes else {
            return
        }
        guard files.allSatisfy({ completedChunks[$0.index]?.count == $0.chunkCount }) else {
            return
        }
        let payloadFiles = files.compactMap { file -> NativeReceivedPayloadFile? in
            guard let chunks = fileChunks[file.index] else {
                return nil
            }
            var payload = Data()
            for chunkIndex in 0..<file.chunkCount {
                guard let chunk = chunks[chunkIndex] else {
                    return nil
                }
                payload.append(chunk)
            }
            guard payload.count == file.sizeBytes, SHA256.hashData(payload) == file.fileHash else {
                resetFileForRetry(file)
                return nil
            }
            return NativeReceivedPayloadFile(
                displayName: file.displayName,
                fileType: file.fileType,
                sizeBytes: file.sizeBytes,
                payloadData: payload
            )
        }
        guard payloadFiles.count == files.count else {
            return
        }
        let transfer = NativeReceivedTransfer(senderName: senderName, files: payloadFiles)
        receiveFileStore.save(transfer, destinationFor: destinationFor) { [weak self] item in
            guard let self, let item else {
                self?.onReceiveState(nil)
                return
            }
            self.onReceiveCompleted(item)
            self.onReceiveState(nil)
            self.fileChunks.removeAll(keepingCapacity: false)
            self.progressStore.clear(transferId: self.transferId)
        }
    }

    private func resetFileForRetry(_ file: NativeTransferV3File) {
        let attempts = (hashRetryCount[file.index] ?? 0) + 1
        hashRetryCount[file.index] = attempts
        guard attempts <= 3 else {
            onReceiveState(nil)
            return
        }
        if let completed = completedChunks[file.index] {
            receivedBytes -= completed.reduce(0) { $0 + expectedChunkLength(file: file, chunkIndex: $1) }
        }
        completedChunks[file.index] = []
        fileChunks[file.index] = [:]
        progressStore.save(transferId: transferId, manifestHashB64: manifestHashB64, manifest: files, completedChunks: completedChunks)
        for chunkIndex in 0..<file.chunkCount {
            sendRetry(fileIndex: file.index, chunkIndex: chunkIndex)
        }
    }

    private func expectedChunkLength(file: NativeTransferV3File, chunkIndex: Int) -> Int {
        let offset = chunkIndex * file.chunkSize
        return min(file.chunkSize, file.sizeBytes - offset)
    }

    private func sendAck(fileIndex: Int, chunkIndex: Int) {
        pendingAckFrames.append(NativeTransferProtocolV3.encodeAck(fileIndex: fileIndex, chunkIndex: chunkIndex))
        guard !ackFlushScheduled else { return }
        ackFlushScheduled = true
        DispatchQueue.main.async { [weak self] in
            self?.flushPendingAcks()
        }
    }

    private func flushPendingAcks() {
        ackFlushScheduled = false
        guard !pendingAckFrames.isEmpty, !canceled else { return }
        let batch = pendingAckFrames
        pendingAckFrames.removeAll(keepingCapacity: true)
        if let sendBatch = sendControlBatch {
            sendBatch(batch)
        } else {
            batch.forEach { sendControl($0) }
        }
    }

    private func sendReady() {
        sendControl(NativeTransferProtocolV3.encodeReady())
    }

    private func sendRetry(fileIndex: Int, chunkIndex: Int) {
        sendControl(NativeTransferProtocolV3.encodeRetry(fileIndex: fileIndex, chunkIndex: chunkIndex))
    }

    private func sendReadyAndDrainPending() {
        guard !readySent, !files.isEmpty else {
            return
        }
        sendReady()
        readySent = true
        let pending = pendingChunkFrames
        pendingChunkFrames.removeAll(keepingCapacity: false)
        pending.forEach(receive)
    }
}

private extension NativeTransferV3File {
    var metadata: NativeTransferFileMetadata {
        NativeTransferFileMetadata(displayName: displayName, fileType: fileType, sizeBytes: sizeBytes)
    }
}

private final class NativeP2PSendSession {
    let config: NativeTransferSessionConfig
    let ephemeral: NativeTransferV3EphemeralKeyPair
    let senderX25519PrivateB64: String
    let receiverEd25519PubB64: String
    let receiverX25519PubB64: String
    let manifestHashB64: String
    var sessionKey: Data?

    init(
        config: NativeTransferSessionConfig,
        ephemeral: NativeTransferV3EphemeralKeyPair,
        senderX25519PrivateB64: String,
        receiverEd25519PubB64: String,
        receiverX25519PubB64: String,
        manifestHashB64: String
    ) {
        self.config = config
        self.ephemeral = ephemeral
        self.senderX25519PrivateB64 = senderX25519PrivateB64
        self.receiverEd25519PubB64 = receiverEd25519PubB64
        self.receiverX25519PubB64 = receiverX25519PubB64
        self.manifestHashB64 = manifestHashB64
    }
}

private extension NativeAccountError {
    var p2pCode: String {
        switch self {
        case .server(let code, _):
            return code
        case .networkUnavailable:
            return "NETWORK_UNAVAILABLE"
        case .sessionExpired:
            return "SESSION_EXPIRED"
        default:
            return "ACCOUNT_ERROR"
        }
    }

    var p2pOriginalReason: String {
        switch self {
        case .server(_, let message):
            return message.nilIfBlank ?? displayMessage
        default:
            return displayMessage
        }
    }
}

private final class NativeTransferProgressStore {
    private let rootURL: URL

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        rootURL = appSupport.appendingPathComponent("piko-transfers", isDirectory: true)
    }

    func completedBitmapB64(transferId: String, manifestHashB64: String) -> String? {
        guard let progress = loadRaw(transferId: transferId),
              progress["manifest_hash_b64"] as? String == manifestHashB64,
              let sanitized = sanitizeCompletedChunks(transferId: transferId, progress: progress),
              let data = try? JSONSerialization.data(withJSONObject: sanitized) else {
            return nil
        }
        return data.base64EncodedString()
    }

    func completedChunks(transferId: String, manifestHashB64: String, manifest: [NativeTransferV3File]) -> [Int: Set<Int>] {
        guard let progress = loadRaw(transferId: transferId),
              progress["manifest_hash_b64"] as? String == manifestHashB64,
              let files = progress["files"] as? [[String: Any]] else {
            return [:]
        }
        var result: [Int: Set<Int>] = [:]
        for file in files {
            guard let index = file["index"] as? Int,
                  let completed = file["completed"] as? [Bool],
                  let manifestFile = manifest.first(where: { $0.index == index }),
                  file["file_hash_b64"] as? String == manifestFile.fileHash.base64EncodedString() else {
                continue
            }
            result[index] = Set(
                completed.enumerated().compactMap { chunkIndex, isCompleted in
                    guard isCompleted,
                          chunkData(transferId: transferId, fileIndex: index, chunkIndex: chunkIndex)?.count == expectedChunkLength(sizeBytes: manifestFile.sizeBytes, chunkSize: manifestFile.chunkSize, chunkIndex: chunkIndex) else {
                        return nil
                    }
                    return chunkIndex
                }
            )
        }
        return result.values.contains { !$0.isEmpty } ? result : [:]
    }

    func save(transferId: String, manifestHashB64: String, manifest: [NativeTransferV3File], completedChunks: [Int: Set<Int>]) {
        let dir = transferDir(transferId: transferId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let files = manifest.map { file -> [String: Any] in
            [
                "index": file.index,
                "display_name": file.displayName,
                "size_bytes": file.sizeBytes,
                "chunk_size": file.chunkSize,
                "chunk_count": file.chunkCount,
                "file_hash_b64": file.fileHash.base64EncodedString(),
                "completed": (0..<file.chunkCount).map { completedChunks[file.index]?.contains($0) == true }
            ]
        }
        let progress: [String: Any] = [
            "transfer_id": transferId,
            "manifest_hash_b64": manifestHashB64,
            "files": files,
            "updated_at": Int(Date().timeIntervalSince1970 * 1000)
        ]
        if let data = try? JSONSerialization.data(withJSONObject: progress) {
            try? data.write(to: dir.appendingPathComponent("progress.json"), options: .atomic)
        }
    }

    func saveChunk(transferId: String, fileIndex: Int, chunkIndex: Int, data: Data) {
        let dir = transferDir(transferId: transferId).appendingPathComponent("chunks", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: dir.appendingPathComponent("\(fileIndex)-\(chunkIndex).part"), options: .atomic)
    }

    func chunkData(transferId: String, fileIndex: Int, chunkIndex: Int) -> Data? {
        try? Data(contentsOf: transferDir(transferId: transferId).appendingPathComponent("chunks/\(fileIndex)-\(chunkIndex).part"))
    }

    func clear(transferId: String) {
        try? FileManager.default.removeItem(at: transferDir(transferId: transferId))
    }

    static func decodeCompletedBitmap(_ bitmapB64: String?) -> [Int: Set<Int>] {
        guard let bitmapB64,
              let data = Data(base64Encoded: bitmapB64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let files = json["files"] as? [[String: Any]] else {
            return [:]
        }
        var result: [Int: Set<Int>] = [:]
        for file in files {
            guard let index = file["index"] as? Int,
                  let completed = file["completed"] as? [Bool] else {
                continue
            }
            result[index] = Set(completed.enumerated().compactMap { $0.element ? $0.offset : nil })
        }
        return result
    }

    private func loadRaw(transferId: String) -> [String: Any]? {
        let file = transferDir(transferId: transferId).appendingPathComponent("progress.json")
        guard let data = try? Data(contentsOf: file) else {
            return nil
        }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func sanitizeCompletedChunks(transferId: String, progress: [String: Any]) -> [String: Any]? {
        guard let files = progress["files"] as? [[String: Any]] else {
            return nil
        }
        var sanitizedFiles: [[String: Any]] = []
        var hasCompletedChunk = false
        for file in files {
            guard let index = file["index"] as? Int,
                  let sizeBytes = file["size_bytes"] as? Int,
                  let chunkSize = file["chunk_size"] as? Int,
                  let chunkCount = file["chunk_count"] as? Int,
                  let completed = file["completed"] as? [Bool],
                  sizeBytes >= 0,
                  chunkSize > 0,
                  chunkCount >= 0 else {
                continue
            }
            let sanitizedCompleted = (0..<chunkCount).map { chunkIndex in
                let isValid = completed.indices.contains(chunkIndex)
                    && completed[chunkIndex]
                    && chunkData(transferId: transferId, fileIndex: index, chunkIndex: chunkIndex)?.count == expectedChunkLength(sizeBytes: sizeBytes, chunkSize: chunkSize, chunkIndex: chunkIndex)
                if isValid {
                    hasCompletedChunk = true
                }
                return isValid
            }
            var sanitizedFile = file
            sanitizedFile["completed"] = sanitizedCompleted
            sanitizedFiles.append(sanitizedFile)
        }
        guard hasCompletedChunk else {
            return nil
        }
        var sanitized = progress
        sanitized["files"] = sanitizedFiles
        return sanitized
    }

    private func transferDir(transferId: String) -> URL {
        rootURL.appendingPathComponent(transferId, isDirectory: true)
    }

    private func expectedChunkLength(sizeBytes: Int, chunkSize: Int, chunkIndex: Int) -> Int {
        let offset = chunkIndex * chunkSize
        guard offset < sizeBytes else {
            return 0
        }
        return min(chunkSize, sizeBytes - offset)
    }
}

private extension Array where Element == NativeTransferItem {
    func toManifestInputs() -> [NativeTransferV3ManifestInput]? {
        compactMap { item in
            guard let fileHash = SHA256.hashFile(item.fileURL) else {
                return nil
            }
            return NativeTransferV3ManifestInput(
                displayName: item.displayName,
                fileType: item.fileType,
                sizeBytes: item.sizeBytes,
                fileHash: fileHash
            )
        }.takeIfCount(count)
    }
}

private extension Array where Element == NativeTransferV3ManifestInput {
    var manifestHashB64: String {
        var hasher = SHA256()
        for (index, item) in enumerated() {
            hasher.update(data: Data(String(index).utf8))
            hasher.update(data: Data([0]))
            hasher.update(data: Data(item.displayName.utf8))
            hasher.update(data: Data([0]))
            hasher.update(data: Data(String(item.fileType.rawValue).utf8))
            hasher.update(data: Data([0]))
            hasher.update(data: Data(String(item.sizeBytes).utf8))
            hasher.update(data: Data([0]))
            hasher.update(data: item.fileHash)
            hasher.update(data: Data([0]))
        }
        return Data(hasher.finalize()).base64EncodedString()
    }
}

@MainActor
private final class NativeReceiverReadyTracker {
    private var isReady = false
    private(set) var isAborted = false
    private var continuation: CheckedContinuation<Bool, Never>?

    func markReady() {
        isReady = true
        continuation?.resume(returning: true)
        continuation = nil
    }

    func abort() {
        guard !isReady, !isAborted else {
            return
        }
        isAborted = true
        continuation?.resume(returning: false)
        continuation = nil
    }

    func wait(seconds: TimeInterval) async -> Bool {
        guard !isReady else {
            return true
        }
        guard !isAborted else {
            return false
        }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                Task { @MainActor in
                    guard let self, !self.isReady, !self.isAborted else {
                        return
                    }
                    self.continuation?.resume(returning: false)
                    self.continuation = nil
                }
            }
        }
    }
}

@MainActor
private final class NativeChunkAckTracker {
    private let totalChunks: Int
    private var ackedChunks: Set<String> = []
    private(set) var isAborted = false
    private var continuation: CheckedContinuation<Bool, Never>?
    private var ackCountContinuation: CheckedContinuation<Bool, Never>?
    private var ackCountTarget = 0

    init(totalChunks: Int) {
        self.totalChunks = totalChunks
    }

    var ackedCount: Int {
        ackedChunks.count
    }

    @discardableResult
    func markAck(fileIndex: Int, chunkIndex: Int) -> Bool {
        let inserted = ackedChunks.insert(chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)).inserted
        if ackedChunks.count >= ackCountTarget {
            ackCountContinuation?.resume(returning: true)
            ackCountContinuation = nil
            ackCountTarget = 0
        }
        if ackedChunks.count >= totalChunks {
            continuation?.resume(returning: true)
            continuation = nil
        }
        return inserted
    }

    func abort() {
        guard !isAborted, ackedChunks.count < totalChunks else {
            return
        }
        isAborted = true
        continuation?.resume(returning: false)
        continuation = nil
        ackCountContinuation?.resume(returning: false)
        ackCountContinuation = nil
        ackCountTarget = 0
    }

    func waitForAckedCount(_ target: Int, seconds: TimeInterval) async -> Bool {
        guard ackedChunks.count < target else {
            return true
        }
        guard !isAborted else {
            return false
        }
        return await withCheckedContinuation { continuation in
            self.ackCountTarget = target
            self.ackCountContinuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                Task { @MainActor in
                    guard let self, self.ackedChunks.count < target, !self.isAborted else {
                        return
                    }
                    self.ackCountContinuation?.resume(returning: false)
                    self.ackCountContinuation = nil
                    self.ackCountTarget = 0
                }
            }
        }
    }

    func waitForAll(seconds: TimeInterval) async -> Bool {
        guard totalChunks > 0, ackedChunks.count < totalChunks else {
            return true
        }
        guard !isAborted else {
            return false
        }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                Task { @MainActor in
                    guard let self, self.ackedChunks.count < self.totalChunks, !self.isAborted else {
                        return
                    }
                    self.continuation?.resume(returning: false)
                    self.continuation = nil
                }
            }
        }
    }
}

private func chunkKey(fileIndex: Int, chunkIndex: Int) -> String {
    "\(fileIndex):\(chunkIndex)"
}

private extension Array {
    func takeIfCount(_ expectedCount: Int) -> [Element]? {
        count == expectedCount ? self : nil
    }
}

private extension SHA256 {
    static func hashFile(_ url: URL) -> Data? {
        guard let stream = InputStream(url: url) else {
            return nil
        }
        stream.open()
        defer {
            stream.close()
        }
        var hasher = SHA256()
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read < 0 {
                return nil
            }
            if read == 0 {
                break
            }
            hasher.update(data: Data(buffer.prefix(read)))
        }
        return Data(hasher.finalize())
    }
}
