import CryptoKit
import Darwin
import Foundation
import Network
import OSLog

private enum NativeP2PTiming {
    static let directEndpointWaitSeconds: TimeInterval = 5
    static let directTransportWaitSeconds: TimeInterval = 5
    static let initialOpenWaitSeconds: TimeInterval = 12
    static let restartOpenWaitSeconds: TimeInterval = 45
    static let receiverWatchdogSeconds: TimeInterval = 90
}

private let nativeP2PWebRtcChunkBatchLimit = 4
private let nativeP2PWebRtcChunkBatchBytes = 1 * 1024 * 1024
private let nativeP2PMaxDirectFrameBytes = 8 * 1024 * 1024
private let nativeP2PThroughputLogIntervalBytes = 32 * 1024 * 1024
private let nativeP2PQuicUdpPunchEndpoint = "quic_udp_punch"
private let nativeP2PQuicIpv6DirectEndpoint = "quic_ipv6_direct"
private let nativeP2PTcpIpv6DirectEndpoint = "tcp_ipv6_direct"
private let nativeP2PLogger = Logger(subsystem: "com.juren233.piko", category: "p2p")

private struct NativeP2PTransportAttempt {
    let name: String
    let timeoutSeconds: TimeInterval
}

@MainActor
private func directTransportAttemptPlan() -> [NativeP2PTransportAttempt] {
    var attempts: [NativeP2PTransportAttempt] = []
    if NativeXQuicDirectTransport.isAvailable {
        attempts.append(NativeP2PTransportAttempt(name: nativeP2PQuicIpv6DirectEndpoint, timeoutSeconds: NativeP2PTiming.directTransportWaitSeconds))
        attempts.append(NativeP2PTransportAttempt(name: nativeP2PQuicUdpPunchEndpoint, timeoutSeconds: NativeP2PTiming.directTransportWaitSeconds))
    }
    attempts.append(NativeP2PTransportAttempt(name: nativeP2PTcpIpv6DirectEndpoint, timeoutSeconds: NativeP2PTiming.directTransportWaitSeconds))
    attempts.append(NativeP2PTransportAttempt(name: "webrtc_ipv6_host", timeoutSeconds: NativeP2PTiming.initialOpenWaitSeconds))
    attempts.append(NativeP2PTransportAttempt(name: "webrtc_stun", timeoutSeconds: NativeP2PTiming.restartOpenWaitSeconds))
    return attempts
}

private struct NativeStunProbeTarget {
    let host: String
    let port: Int32
}

private struct NativeStunProbeResult {
    let serverUrl: String
    let success: Bool
    let mappedHost: String?
    let mappedPort: Int
    let error: String?
    let elapsedMs: Int
}

private struct NativeXQuicMappedCandidate {
    let id: String
    let host: String
    let port: Int
    let stunServer: String
    let mappingStable: Bool
    let priority: Int
}

private struct NativeStunAggregation {
    let candidates: [NativeXQuicMappedCandidate]
    let udpProbeResult: String
    let mappingBehavior: String
    let stunSuccessCount: Int
    let stunErrorCount: Int
}

private struct NativeXQuicMappedEndpoint {
    let host: String
    let port: Int
}

private extension Array where Element == NativeIceServerConfig {
    var firstStunProbeTarget: NativeStunProbeTarget? {
        firstNonNil { $0.urls.nativeStunProbeTarget() }
    }

    var allStunUrls: [String] {
        compactMap { config in
            let url = config.urls.trimmingCharacters(in: .whitespacesAndNewlines)
            return url.lowercased().hasPrefix("stun:") && url.nativeStunProbeTarget() != nil ? url : nil
        }
    }
}

private extension Sequence {
    func firstNonNil<T>(_ transform: (Element) -> T?) -> T? {
        for item in self {
            if let value = transform(item) {
                return value
            }
        }
        return nil
    }
}

private extension String {
    func nativeStunProbeTarget() -> NativeStunProbeTarget? {
        let raw = trimmingCharacters(in: .whitespacesAndNewlines)
        guard raw.lowercased().hasPrefix("stun:") else {
            return nil
        }
        let withoutScheme = String(raw.dropFirst(5))
        let endpoint = withoutScheme
            .split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
            .split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)[0]
        guard !endpoint.isEmpty else {
            return nil
        }
        if endpoint.hasPrefix("["),
           let end = endpoint.firstIndex(of: "]") {
            let host = String(endpoint[endpoint.index(after: endpoint.startIndex)..<end])
            let suffix = endpoint[endpoint.index(after: end)...]
            let portText = suffix.hasPrefix(":") ? String(suffix.dropFirst()) : ""
            let port = Int32(portText) ?? 3478
            return (1...65535).contains(Int(port)) && !host.isEmpty ? NativeStunProbeTarget(host: host, port: port) : nil
        }
        let parts = endpoint.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        guard let hostPart = parts.first, !hostPart.isEmpty else {
            return nil
        }
        let port = parts.count > 1 ? (Int32(String(parts[1])) ?? 3478) : 3478
        return (1...65535).contains(Int(port)) ? NativeStunProbeTarget(host: String(hostPart), port: port) : nil
    }

    func nativeXQuicMappedEndpoint() -> NativeXQuicMappedEndpoint? {
        let parts = split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2,
              !parts[0].isEmpty,
              let port = Int(parts[1]),
              (1...65535).contains(port) else {
            return nil
        }
        return NativeXQuicMappedEndpoint(host: String(parts[0]), port: port)
    }

    func nativeStunProbeResult() -> NativeStunProbeResult? {
        let parts = split(separator: "|", maxSplits: 5, omittingEmptySubsequences: false)
        guard parts.count == 6, !parts[0].isEmpty else { return nil }
        let serverUrl = String(parts[0])
        let success = parts[1] == "true"
        let mappedHost = parts[2].isEmpty ? nil : String(parts[2])
        let mappedPort = Int(parts[3]) ?? 0
        let error = parts[4].isEmpty ? nil : String(parts[4])
        let elapsedMs = Int(parts[5]) ?? 0
        return NativeStunProbeResult(serverUrl: serverUrl, success: success, mappedHost: mappedHost,
                                     mappedPort: mappedPort, error: error, elapsedMs: elapsedMs)
    }
}

private func parseNativeStunProbeResults(_ raw: String?) -> [NativeStunProbeResult] {
    guard let raw, !raw.isEmpty else { return [] }
    return raw.split(separator: ";", omittingEmptySubsequences: true)
        .compactMap { String($0).nativeStunProbeResult() }
}

private func aggregateNativeStunProbeResults(_ results: [NativeStunProbeResult]) -> NativeStunAggregation {
    let successProbes = results.filter { $0.success && $0.mappedHost != nil && (1...65535).contains($0.mappedPort) }
    let stunSuccessCount = successProbes.count
    let stunErrorCount = results.filter { !$0.success }.count
    guard !successProbes.isEmpty else {
        return NativeStunAggregation(candidates: [], udpProbeResult: "failed",
                                     mappingBehavior: "unknown", stunSuccessCount: 0, stunErrorCount: stunErrorCount)
    }
    let uniqueAddresses = Set(successProbes.map { "\($0.mappedHost!):\($0.mappedPort)" })
    let uniqueHosts = Set(successProbes.compactMap { $0.mappedHost })
    let mappingBehavior: String
    if successProbes.count == 1 {
        mappingBehavior = "unknown"
    } else if uniqueAddresses.count == 1 {
        mappingBehavior = "stable"
    } else if uniqueHosts.count == 1 {
        mappingBehavior = "port_dependent"
    } else {
        mappingBehavior = "address_and_port_dependent"
    }
    let isStable = mappingBehavior == "stable"
    let candidatePriority: Int
    switch mappingBehavior {
    case "stable":
        candidatePriority = 2_130_000_000
    case "unknown":
        candidatePriority = 2_000_000_000
    default:
        candidatePriority = 1_845_493_760
    }
    var candidateIndex = 0
    var seen = Set<String>()
    var candidates: [NativeXQuicMappedCandidate] = []
    for probe in successProbes {
        let key = "\(probe.mappedHost!):\(probe.mappedPort)"
        guard !seen.contains(key) else { continue }
        seen.insert(key)
        candidateIndex += 1
        candidates.append(NativeXQuicMappedCandidate(
            id: "r-srflx-\(candidateIndex)",
            host: probe.mappedHost!,
            port: probe.mappedPort,
            stunServer: probe.serverUrl,
            mappingStable: isStable,
            priority: candidatePriority
        ))
    }
    return NativeStunAggregation(candidates: candidates, udpProbeResult: "success",
                                  mappingBehavior: mappingBehavior, stunSuccessCount: stunSuccessCount,
                                  stunErrorCount: stunErrorCount)
}

private func nativeP2PTimingLog(stage: String, sessionId: String, transferId: String, startedAt: Date, detail: String = "") {
    let elapsedMs = Int(Date().timeIntervalSince(startedAt) * 1000)
    if detail.isEmpty {
        nativeP2PLogger.debug("timing session=\(sessionId, privacy: .public) transfer=\(transferId, privacy: .public) stage=\(stage, privacy: .public) elapsed_ms=\(elapsedMs, privacy: .public)")
    } else {
        nativeP2PLogger.debug("timing session=\(sessionId, privacy: .public) transfer=\(transferId, privacy: .public) stage=\(stage, privacy: .public) elapsed_ms=\(elapsedMs, privacy: .public) \(detail, privacy: .public)")
    }
}

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
    private let onReceiverNotice: (String) -> Void
    private var sessions: [String: NativeWebRTCSession] = [:]
    private var receivers: [String: NativeP2PReceiver] = [:]
    private var directServers: [String: NativeP2PDirectServer] = [:]
    private var directEndpointTrackers: [String: NativeDirectEndpointTracker] = [:]
    private var pendingSignals: [String: [[String: Any]]] = [:]
    private var senderAbortCallbacks: [String: () -> Void] = [:]
    private let progressStore = NativeTransferProgressStore()
    private let maxInFlightChunks = 16
    private let initialInFlightChunks = 2

    init(
        authStore: NativeAuthStore,
        identityStore: NativeDeviceIdentityStore,
        sessionApi: NativeTransferSessionApiClient,
        signalingClient: NativeSignalingClient,
        receiveFileStore: NativeReceiveFileStore,
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        onReceiveState: @escaping (NativeReceiveTransferState?) -> Void,
        onReceiveCompleted: @escaping (NativeReceiveHistoryItem) -> Void,
        onReceiverNotice: @escaping (String) -> Void = { _ in }
    ) {
        self.authStore = authStore
        self.identityStore = identityStore
        self.sessionApi = sessionApi
        self.signalingClient = signalingClient
        self.receiveFileStore = receiveFileStore
        self.destinationFor = destinationFor
        self.onReceiveState = onReceiveState
        self.onReceiveCompleted = onReceiveCompleted
        self.onReceiverNotice = onReceiverNotice
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
        var lines: [String] = []
        if let reason = diagnostic.failureReason {
            lines.append("失败原因：\(reason.title)")
            lines.append("建议：\(reason.suggestion)")
            lines.append("")
        }
        lines.append("阶段：\(stage)")
        lines.append("会话：\(sessionId?.nilIfBlank ?? "未创建/未知")")
        lines.append("原始原因：\(message.nilIfBlank ?? code)")
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
        lines.append("stun_error_rate：\(String(format: "%.2f", diagnostic.stunErrorRate))")
        lines.append("gathering_incomplete：\(diagnostic.gatheringIncomplete)")
        lines.append("symmetric_nat_suspect：\(diagnostic.symmetricNatSuspect)")
        lines.append("remote_only_mdns：\(diagnostic.remoteOnlyMdns)")
        lines.append("failure_reason_code：\(diagnostic.failureReason?.rawValue ?? "unknown")")
        return .server(code: code, message: lines.joined(separator: "\n"))
    }

    func send(
        _ items: [NativeTransferItem],
        to target: NativeSendDevice,
        senderName: String,
        transferId: String,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int,
        progressUpdate: @escaping (Double) -> Void,
        transportNotice: @escaping (String) -> Void = { _ in }
    ) async -> Result<Int, NativeAccountError> {
        guard let manifestFiles = items.toManifestInputs() else {
            return .failure(p2pError(stage: "send_manifest", sessionId: nil, code: "MANIFEST_INPUT_FAILED", message: "无法读取待发送文件清单"))
        }
        switch await createSession(target: target, transferId: transferId, manifestFiles: manifestFiles) {
        case .failure(let error):
            return .failure(error)
        case .success(let sessionContext):
            let config = sessionContext.config
            let timingStartedAt = Date()
            nativeP2PTimingLog(stage: "create_session_done", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt)
            let receiverReadyTracker = NativeReceiverReadyTracker()
            let totalChunks = manifestFiles.reduce(0) { $0 + NativeTransferProtocolV3.chunkCount(sizeBytes: $1.sizeBytes) }
            let ackTracker = NativeChunkAckTracker(totalChunks: totalChunks)
            var sentFrames: [String: Data] = [:]
            var chunkByteCounts: [String: Int] = [:]
            var confirmedBytes = 0
            var queuedChunks = 0
            var inFlightChunks = 0
            var currentInFlightWindow = initialInFlightChunks
            var transferDataStartedAt: Date?
            var receiverReadyLogged = false
            var firstAckLogged = false
            var retryCount = 0
            var lastThroughputLogBytes = 0
            var selectedTransportName = ""
            var negotiatedSessionKey: Data?
            var sendFrame: ((Data) async -> Bool)?
            var sendBatch: (([Data]) async -> Bool)?
            var diagnosticSnapshot: () async -> NativeWebRTCDiagnostic = { .empty }
            var raceFailureStage = "data_channel_open"
            var raceFailureCode = "DATA_CHANNEL_TIMEOUT"
            var raceFailureMessage: String?
            let directTracker = NativeDirectEndpointTracker()
            func diagnosticWithDirect(_ base: NativeWebRTCDiagnostic) -> NativeWebRTCDiagnostic {
                var diagnostic = base
                diagnostic.applyDirect(directTracker.diagnostic)
                return diagnostic
            }
            func failureDiagnostic(_ message: String) async -> NativeWebRTCDiagnostic {
                var diagnostic = await diagnosticSnapshot()
                if diagnostic.sendFailure.isEmpty || diagnostic.sendFailure == "none" {
                    diagnostic.sendFailure = message
                }
                return diagnostic
            }
            func attachAckTimeoutDiagnostic(_ diagnostic: inout NativeWebRTCDiagnostic, message: String) {
                let ackDetail = "\(message)|acked_chunks=\(ackTracker.ackedCount)|total_chunks=\(totalChunks)|in_flight_chunks=\(inFlightChunks)|confirmed_bytes=\(confirmedBytes)|in_flight_window=\(currentInFlightWindow)|max_in_flight_window=\(maxInFlightChunks)"
                if diagnostic.sendFailure.isEmpty || diagnostic.sendFailure == "none" || diagnostic.sendFailure == message {
                    diagnostic.sendFailure = ackDetail
                } else {
                    diagnostic.sendFailure = "\(diagnostic.sendFailure)|\(ackDetail)"
                }
            }
            func recordRaceFailure(stage: String, code: String, message: String) {
                raceFailureStage = stage
                raceFailureCode = code
                raceFailureMessage = message
            }
            func logThroughput(completed: Int, force: Bool = false) {
                guard completed > 0, let startedAt = transferDataStartedAt else {
                    return
                }
                if !force, completed - lastThroughputLogBytes < nativeP2PThroughputLogIntervalBytes {
                    return
                }
                lastThroughputLogBytes = completed
                let elapsedMs = max(Int(Date().timeIntervalSince(startedAt) * 1000), 1)
                let throughputBps = (completed * 1000) / elapsedMs
                nativeP2PTimingLog(
                    stage: force ? "transfer_done" : "throughput",
                    sessionId: config.sessionId,
                    transferId: transferId,
                    startedAt: timingStartedAt,
                    detail: "transport=\(selectedTransportName) completed_bytes=\(completed) elapsed_ms=\(elapsedMs) throughput_bps=\(throughputBps) in_flight_window=\(currentInFlightWindow) max_in_flight_window=\(maxInFlightChunks) retry_count=\(retryCount)"
                )
            }
            diagnosticSnapshot = { diagnosticWithDirect(.empty) }
            directEndpointTrackers[config.sessionId] = directTracker
            flushPendingSignals(for: config.sessionId)
            let onBinary: (Data) -> Void = { data in
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
                    if !receiverReadyLogged {
                        nativeP2PTimingLog(stage: "receiver_ready", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt)
                        receiverReadyLogged = true
                    }
                case .ack(let fileIndex, let chunkIndex):
                    let key = chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)
                    if ackTracker.markAck(fileIndex: fileIndex, chunkIndex: chunkIndex) {
                        if sentFrames[key] != nil, inFlightChunks > 0 {
                            inFlightChunks -= 1
                            currentInFlightWindow = min(self.maxInFlightChunks, currentInFlightWindow + 1)
                        }
                        confirmedBytes += chunkByteCounts[key] ?? 0
                        if !firstAckLogged {
                            nativeP2PTimingLog(stage: "first_ack_received", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "completed_bytes=\(confirmedBytes)")
                            firstAckLogged = true
                        }
                        logThroughput(completed: confirmedBytes)
                        progressUpdate(Double(min(totalCompletedBeforeTarget + confirmedBytes, totalBytes)) / Double(max(totalBytes, 1)))
                    }
                case .retry(let fileIndex, let chunkIndex):
                    retryCount += 1
                    if let frame = sentFrames[chunkKey(fileIndex: fileIndex, chunkIndex: chunkIndex)] {
                        Task { @MainActor in
                            _ = await sendFrame?(frame)
                        }
                    }
                case .manifest, .chunk:
                    break
                }
            }
            senderAbortCallbacks[config.sessionId] = { [weak receiverReadyTracker, weak ackTracker] in
                receiverReadyTracker?.abort()
                ackTracker?.abort()
            }
            func verifyAcceptSignature(peerEphemeralPublicB64: String, signatureB64: String) -> Bool {
                NativeTransferProtocolV3.verifyAcceptSignature(
                    sessionId: config.sessionId,
                    transferId: transferId,
                    manifestHashB64: sessionContext.manifestHashB64,
                    senderEphemeralPublicKeyB64: sessionContext.ephemeral.publicKeyB64,
                    receiverEphemeralPublicKeyB64: peerEphemeralPublicB64,
                    signatureB64: signatureB64,
                    receiverEd25519PublicKeyB64: sessionContext.receiverEd25519PubB64
                )
            }
            func deriveSessionKey(peerEphemeralPublicB64: String) throws -> Data {
                try NativeTransferProtocolV3.deriveSessionKey(
                    sessionId: config.sessionId,
                    transferId: transferId,
                    localEphemeralPrivateKeyB64: sessionContext.ephemeral.privateKeyB64,
                    peerEphemeralPublicKeyB64: peerEphemeralPublicB64,
                    localStaticPrivateKeyB64: sessionContext.senderX25519PrivateB64,
                    peerStaticPublicKeyB64: sessionContext.receiverX25519PubB64,
                    role: .sender
                )
            }
            func openDirectRaceChannel() async -> NativePreparedP2PChannel? {
                let directEndpoints = await directTracker.waitForEndpoints(seconds: NativeP2PTiming.directEndpointWaitSeconds)
                nativeP2PTimingLog(stage: "direct_endpoint_wait_done", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "count=\(directEndpoints.count)")
                guard !directEndpoints.isEmpty else {
                    return nil
                }
                guard let directHandshake = directTracker.handshake else {
                    let message = "接收方握手等待失败"
                    directTracker.recordAttempt(selected: "none", result: "handshake_failed", error: message)
                    recordRaceFailure(stage: "key_agreement", code: "KEY_AGREEMENT_FAILED", message: message)
                    return nil
                }
                guard verifyAcceptSignature(
                    peerEphemeralPublicB64: directHandshake.peerEphemeralPublicB64,
                    signatureB64: directHandshake.peerAcceptSignatureB64
                ) else {
                    let message = "接收方签名校验失败"
                    directTracker.recordAttempt(selected: "none", result: "handshake_invalid", error: message)
                    recordRaceFailure(stage: "key_agreement", code: "KEY_AGREEMENT_FAILED", message: message)
                    return nil
                }
                do {
                    let sessionKey = try deriveSessionKey(peerEphemeralPublicB64: directHandshake.peerEphemeralPublicB64)
                    let endpoints = [
                        directEndpoints.first { $0.name == nativeP2PQuicIpv6DirectEndpoint },
                        directEndpoints.first { $0.name == nativeP2PQuicUdpPunchEndpoint },
                        directEndpoints.first { $0.name == nativeP2PTcpIpv6DirectEndpoint },
                    ].compactMap { $0 }
                    guard !endpoints.isEmpty else {
                        return nil
                    }
                    let directRace = NativeP2PConnectionRace(expectedCount: endpoints.count)
                    for endpoint in endpoints {
                        Task { @MainActor in
                            let endpointStartedAt = Date()
                            nativeP2PTimingLog(stage: "direct_open_start", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "endpoint=\(endpoint.name)")
                            directTracker.recordAttempt(endpoint: endpoint, result: "attempting")
                            let channel: NativeP2PDirectChannel?
                            if endpoint.name == nativeP2PQuicIpv6DirectEndpoint || endpoint.name == nativeP2PQuicUdpPunchEndpoint {
                                channel = await NativeXQuicDirectTransport.openClient(
                                    endpoint: endpoint,
                                    timeoutSeconds: NativeP2PTiming.directTransportWaitSeconds,
                                    onFrame: onBinary
                                )
                            } else {
                                channel = await NativeP2PFramedConnection.connect(
                                    to: endpoint,
                                    timeoutSeconds: NativeP2PTiming.directTransportWaitSeconds,
                                    onFrame: onBinary
                                )
                            }
                            let durationMs = Int(Date().timeIntervalSince(endpointStartedAt) * 1000)
                            guard let channel else {
                                nativeP2PTimingLog(stage: "direct_open_done", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "endpoint=\(endpoint.name) result=failed duration_ms=\(durationMs)")
                                directTracker.recordAttempt(endpoint: endpoint, result: "failed", error: "直连打开返回空通道")
                                directRace.submit(nil)
                                return
                            }
                            nativeP2PTimingLog(stage: "direct_open_done", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "endpoint=\(endpoint.name) result=connected duration_ms=\(durationMs)")
                            directTracker.recordAttempt(endpoint: endpoint, result: "connected")
                            let transportName: String
                            switch endpoint.name {
                            case nativeP2PQuicUdpPunchEndpoint:
                                transportName = "QUIC UDP 打洞通道"
                            case nativeP2PQuicIpv6DirectEndpoint:
                                transportName = "QUIC 直连通道"
                            default:
                                transportName = "TCP 直连通道"
                            }
                            directRace.submit(
                                NativePreparedP2PChannel(
                                    sessionKey: sessionKey,
                                    transportName: transportName,
                                    completedBitmapB64: directHandshake.peerCompletedBitmapB64,
                                    send: { data in await channel.send(data) },
                                    sendBatch: nil,
                                    closeUnused: { channel.close() },
                                    closeSelected: { channel.close() }
                                )
                            )
                        }
                    }
                    return await directRace.waitForWinner()
                } catch {
                    let message = error.localizedDescription
                    directTracker.recordAttempt(selected: "none", result: "session_key_failed", error: message)
                    recordRaceFailure(stage: "key_agreement", code: "KEY_AGREEMENT_FAILED", message: message)
                }
                return nil
            }
            func openWebRTCRaceChannel() async -> NativePreparedP2PChannel? {
                guard !Task.isCancelled else {
                    return nil
                }
                let session = makeSession(
                    sessionId: config.sessionId,
                    iceServers: config.iceServers,
                    onBinary: onBinary
                )
                sessions[config.sessionId] = session
                func closeWebRTCSessionIfStored() {
                    if let current = sessions[config.sessionId], current === session {
                        sessions.removeValue(forKey: config.sessionId)
                    }
                    session.close()
                }
                diagnosticSnapshot = {
                    let base = await session.diagnosticSnapshotWithStats()
                    return diagnosticWithDirect(base)
                }
                guard !Task.isCancelled else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                guard await session.createOffer() else {
                    recordRaceFailure(stage: "data_channel_open", code: "OFFER_FAILED", message: "WebRTC offer 创建失败")
                    closeWebRTCSessionIfStored()
                    return nil
                }
                guard !Task.isCancelled else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                nativeP2PTimingLog(stage: "webrtc_offer_sent", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt)
                var opened = await session.waitUntilOpen(seconds: NativeP2PTiming.initialOpenWaitSeconds)
                guard !Task.isCancelled else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                if !opened && !receiverReadyTracker.isAborted {
                    let initialDiagnostic = await session.diagnosticSnapshotWithStats()
                    if !NativeP2PDiagnostics.shouldContinueWaitingForIce(initialDiagnostic) {
                        recordRaceFailure(stage: "data_channel_open", code: "DATA_CHANNEL_TIMEOUT", message: Self.crossNetworkDiagnosis(initialDiagnostic).body)
                        closeWebRTCSessionIfStored()
                        return nil
                    }
                    nativeP2PTimingLog(stage: "webrtc_early_ice_restart", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "wait_seconds=\(Int(NativeP2PTiming.initialOpenWaitSeconds))")
                    _ = await session.restartIce()
                    opened = await session.waitUntilOpen(seconds: NativeP2PTiming.restartOpenWaitSeconds)
                }
                guard !Task.isCancelled else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                guard opened else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                nativeP2PTimingLog(stage: "webrtc_opened", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt)
                guard let peerHandshake = await session.waitForPeerEphemeralPublic(seconds: 10),
                      verifyAcceptSignature(
                        peerEphemeralPublicB64: peerHandshake,
                        signatureB64: session.peerAcceptSignatureB64 ?? ""
                      ),
                      let sessionKey = try? deriveSessionKey(peerEphemeralPublicB64: peerHandshake) else {
                    recordRaceFailure(stage: "key_agreement", code: "KEY_AGREEMENT_FAILED", message: "跨网密钥协商失败")
                    closeWebRTCSessionIfStored()
                    return nil
                }
                guard !Task.isCancelled else {
                    closeWebRTCSessionIfStored()
                    return nil
                }
                return NativePreparedP2PChannel(
                    sessionKey: sessionKey,
                    transportName: "WebRTC 通道",
                    completedBitmapB64: session.peerCompletedBitmapB64,
                    send: { data in await session.send(data) },
                    sendBatch: { items in await session.sendBatch(items) },
                    closeUnused: { closeWebRTCSessionIfStored() },
                    closeSelected: {}
                )
            }
            transportNotice("正在同时尝试直连通道和 WebRTC 通道")
            let directTask = Task { @MainActor in await openDirectRaceChannel() }
            let webRTCTask = Task { @MainActor in await openWebRTCRaceChannel() }
            let directCandidate = await directTask.value
            let webRTCCandidate: NativePreparedP2PChannel?
            if directCandidate != nil {
                webRTCTask.cancel()
                sessions.removeValue(forKey: config.sessionId)?.close()
                webRTCCandidate = nil
            } else {
                webRTCCandidate = await webRTCTask.value
            }
            guard let selectedChannel = directCandidate ?? webRTCCandidate else {
                var diagnostic = await diagnosticSnapshot()
                let abortedByPeer = receiverReadyTracker.isAborted
                closeSession(config.sessionId)
                directEndpointTrackers.removeValue(forKey: config.sessionId)
                if abortedByPeer {
                    return .failure(p2pError(stage: "data_channel_open", sessionId: config.sessionId, code: "P2P_RECEIVER_CANCELED", message: "对方已取消接收", diagnostic: diagnostic))
                }
                if let message = raceFailureMessage {
                    return .failure(p2pError(stage: raceFailureStage, sessionId: config.sessionId, code: raceFailureCode, message: message, diagnostic: diagnostic))
                }
                let reason = Self.crossNetworkDiagnosis(diagnostic)
                diagnostic.failureReason = reason
                return .failure(p2pError(stage: "data_channel_open", sessionId: config.sessionId, code: "DATA_CHANNEL_TIMEOUT", message: reason.body, diagnostic: diagnostic))
            }
            negotiatedSessionKey = selectedChannel.sessionKey
            sessionContext.sessionKey = selectedChannel.sessionKey
            sendFrame = selectedChannel.send
            sendBatch = selectedChannel.sendBatch
            let sessionKey = selectedChannel.sessionKey
            selectedTransportName = selectedChannel.transportName
            nativeP2PTimingLog(stage: "race_winner", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "transport=\(selectedChannel.transportName)")
            transportNotice("已连接\(selectedChannel.transportName)，开始传输文件")
            let peerCompletedChunks = NativeTransferProgressStore.decodeCompletedBitmap(selectedChannel.completedBitmapB64)

            guard let manifestFrame = try? NativeTransferProtocolV3.encodeManifest(
                sessionKey: sessionKey,
                sessionId: config.sessionId,
                transferId: transferId,
                files: manifestFiles,
                senderName: senderName
            ),
            await sendFrame?(manifestFrame) == true else {
                let message = "传输清单发送失败"
                let diagnostic = await failureDiagnostic(message)
                closeSession(config.sessionId)
                selectedChannel.closeAfterSelectedUse()
                directEndpointTrackers.removeValue(forKey: config.sessionId)
                return .failure(p2pError(stage: "send_manifest", sessionId: config.sessionId, code: "SEND_MANIFEST_FAILED", message: message, diagnostic: diagnostic))
            }
            nativeP2PTimingLog(stage: "manifest_sent", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "chunk_size_bytes=\(NativeTransferProtocolV3.chunkSize) in_flight_window=\(currentInFlightWindow) max_in_flight_window=\(maxInFlightChunks)")
            guard await receiverReadyTracker.wait(seconds: 120) else {
                let abortedByPeer = receiverReadyTracker.isAborted
                let message = abortedByPeer ? "对方已取消接收" : "等待接收端确认超时"
                let diagnostic = await failureDiagnostic(message)
                closeSession(config.sessionId)
                selectedChannel.closeAfterSelectedUse()
                directEndpointTrackers.removeValue(forKey: config.sessionId)
                return .failure(p2pError(stage: "receiver_ready", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_RECEIVER_READY_TIMEOUT", message: message, diagnostic: diagnostic))
            }

            var pendingChunkBatch: [Data] = []
            var pendingChunkBatchBytes = 0
            var firstChunkLogged = false
            func flushChunkBatch() async -> Bool {
                guard !pendingChunkBatch.isEmpty else {
                    return true
                }
                let batch = pendingChunkBatch
                pendingChunkBatch.removeAll(keepingCapacity: true)
                pendingChunkBatchBytes = 0
                if let sendBatch {
                    return await sendBatch(batch)
                }
                for frame in batch {
                    guard await sendFrame?(frame) == true else {
                        return false
                    }
                }
                return true
            }

            for (fileIndex, item) in items.enumerated() {
                guard let stream = InputStream(url: item.fileURL) else {
                    let message = "无法读取待发送文件"
                    let diagnostic = await failureDiagnostic(message)
                    closeSession(config.sessionId)
                    selectedChannel.closeAfterSelectedUse()
                    directEndpointTrackers.removeValue(forKey: config.sessionId)
                    return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "OPEN_FILE_FAILED", message: message, diagnostic: diagnostic))
                }
                stream.open()
                defer {
                    stream.close()
                }
                var buffer = [UInt8](repeating: 0, count: NativeTransferProtocolV3.chunkSize)
                var chunkIndex = 0
                while stream.hasBytesAvailable {
                    if Task.isCancelled {
                        let message = "传输已取消"
                        let diagnostic = await failureDiagnostic(message)
                        closeSession(config.sessionId)
                        selectedChannel.closeAfterSelectedUse()
                        directEndpointTrackers.removeValue(forKey: config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "TRANSFER_CANCELLED", message: message, diagnostic: diagnostic))
                    }
                    let read = stream.read(&buffer, maxLength: buffer.count)
                    guard read >= 0 else {
                        let message = "文件分片读取失败"
                        let diagnostic = await failureDiagnostic(message)
                        closeSession(config.sessionId)
                        selectedChannel.closeAfterSelectedUse()
                        directEndpointTrackers.removeValue(forKey: config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "READ_FILE_FAILED", message: message, diagnostic: diagnostic))
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
                    ) else {
                        let message = "文件分片编码失败"
                        let diagnostic = await failureDiagnostic(message)
                        closeSession(config.sessionId)
                        selectedChannel.closeAfterSelectedUse()
                        directEndpointTrackers.removeValue(forKey: config.sessionId)
                        return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "SEND_CHUNK_FAILED", message: message, diagnostic: diagnostic))
                    }
                    pendingChunkBatch.append(chunkFrame)
                    pendingChunkBatchBytes += chunkFrame.count
                    sentFrames[key] = chunkFrame
                    queuedChunks += 1
                    inFlightChunks += 1
                    chunkIndex += 1
                    if pendingChunkBatch.count >= nativeP2PWebRtcChunkBatchLimit || pendingChunkBatchBytes >= nativeP2PWebRtcChunkBatchBytes {
                        guard await flushChunkBatch() else {
                            let message = "文件分片发送失败"
                            let diagnostic = await failureDiagnostic(message)
                            closeSession(config.sessionId)
                            selectedChannel.closeAfterSelectedUse()
                            directEndpointTrackers.removeValue(forKey: config.sessionId)
                            return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "SEND_CHUNK_FAILED", message: message, diagnostic: diagnostic))
                        }
                        if !firstChunkLogged {
                            transferDataStartedAt = Date()
                            nativeP2PTimingLog(stage: "first_chunk_sent", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "transport=\(selectedChannel.transportName)")
                            firstChunkLogged = true
                        }
                    }
                    let ackTarget = queuedChunks - currentInFlightWindow + 1
                    if ackTarget > 0, !Task.isCancelled {
                        guard await flushChunkBatch() else {
                            let message = "文件分片发送失败"
                            let diagnostic = await failureDiagnostic(message)
                            closeSession(config.sessionId)
                            selectedChannel.closeAfterSelectedUse()
                            directEndpointTrackers.removeValue(forKey: config.sessionId)
                            return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "SEND_CHUNK_FAILED", message: message, diagnostic: diagnostic))
                        }
                        if !firstChunkLogged {
                            transferDataStartedAt = Date()
                            nativeP2PTimingLog(stage: "first_chunk_sent", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "transport=\(selectedChannel.transportName)")
                            firstChunkLogged = true
                        }
                        guard await ackTracker.waitForAckedCount(ackTarget, seconds: 30) else {
                            let abortedByPeer = ackTracker.isAborted
                            let message = abortedByPeer ? "对方已取消接收" : "跨网传输确认超时"
                            var diagnostic = await failureDiagnostic(message)
                            if !abortedByPeer {
                                attachAckTimeoutDiagnostic(&diagnostic, message: message)
                            }
                            closeSession(config.sessionId)
                            selectedChannel.closeAfterSelectedUse()
                            directEndpointTrackers.removeValue(forKey: config.sessionId)
                            return .failure(p2pError(stage: "ack", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_ACK_TIMEOUT", message: message, diagnostic: diagnostic))
                        }
                    }
                }
            }

            guard await flushChunkBatch() else {
                let message = "文件分片发送失败"
                let diagnostic = await failureDiagnostic(message)
                closeSession(config.sessionId)
                selectedChannel.closeAfterSelectedUse()
                directEndpointTrackers.removeValue(forKey: config.sessionId)
                return .failure(p2pError(stage: "send_chunk", sessionId: config.sessionId, code: "SEND_CHUNK_FAILED", message: message, diagnostic: diagnostic))
            }
            if !firstChunkLogged, queuedChunks > 0 {
                transferDataStartedAt = Date()
                nativeP2PTimingLog(stage: "first_chunk_sent", sessionId: config.sessionId, transferId: transferId, startedAt: timingStartedAt, detail: "transport=\(selectedChannel.transportName)")
                firstChunkLogged = true
            }
            guard await ackTracker.waitForAll(seconds: 30) else {
                let abortedByPeer = ackTracker.isAborted
                let message = abortedByPeer ? "对方已取消接收" : "跨网传输确认超时"
                var diagnostic = await failureDiagnostic(message)
                if !abortedByPeer {
                    attachAckTimeoutDiagnostic(&diagnostic, message: message)
                }
                closeSession(config.sessionId)
                selectedChannel.closeAfterSelectedUse()
                directEndpointTrackers.removeValue(forKey: config.sessionId)
                return .failure(p2pError(stage: "ack", sessionId: config.sessionId, code: abortedByPeer ? "P2P_RECEIVER_CANCELED" : "P2P_ACK_TIMEOUT", message: message, diagnostic: diagnostic))
            }
            logThroughput(completed: confirmedBytes, force: true)
            closeSession(config.sessionId)
            selectedChannel.closeAfterSelectedUse()
            directEndpointTrackers.removeValue(forKey: config.sessionId)
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
            guard let receiver = receiver(
                for: sessionId,
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicB64: senderEphemeralPublic,
                senderInviteSignatureB64: senderInviteSignatureB64,
                senderEd25519PublicB64: senderEd25519PublicB64,
                senderX25519PublicB64: senderX25519PublicB64,
                iceServers: NativeIceServerConfig.parse(message["ice_servers"] as? [[String: Any]]),
                autoAccept: (message["same_account"] as? Bool) ?? false
            ) else {
                return
            }
            let prewarmStartedAt = Date()
            nativeP2PTimingLog(stage: "receiver_direct_prewarm_start", sessionId: sessionId, transferId: transferId, startedAt: prewarmStartedAt)
            if directServers[sessionId] == nil,
               let server = NativeP2PDirectServer.start(
                sessionId: sessionId,
                signalingClient: signalingClient,
                receiver: receiver
               ) {
                directServers[sessionId] = server
            }
            nativeP2PTimingLog(
                stage: "receiver_direct_prewarm_done",
                sessionId: sessionId,
                transferId: transferId,
                startedAt: prewarmStartedAt,
                detail: directServers[sessionId] == nil ? "result=unavailable" : "result=started"
            )
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
        case "direct_endpoint":
            guard let tracker = directEndpointTrackers[sessionId] else {
                bufferSignal(message, for: sessionId)
                return
            }
            tracker.accept(message)
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
            Task(priority: .high) { @MainActor in
                _ = await sessionRef?.send(data)
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
            watchdogTimeoutSeconds: NativeP2PTiming.receiverWatchdogSeconds,
            receiveFileStore: receiveFileStore,
            destinationFor: destinationFor,
            onReceiveState: onReceiveState,
            onReceiverNotice: onReceiverNotice,
            onReceiveCompleted: { [weak self] item in
                self?.onReceiveCompleted(item)
                self?.receivers.removeValue(forKey: sessionId)
                self?.sessions.removeValue(forKey: sessionId)
                self?.directServers.removeValue(forKey: sessionId)?.close()
                self?.directEndpointTrackers.removeValue(forKey: sessionId)
            }
        )
        receivers[sessionId] = receiver
        return receiver
    }

    private func closeSession(_ sessionId: String) {
        sessions.removeValue(forKey: sessionId)?.close()
        directServers.removeValue(forKey: sessionId)?.close()
        directEndpointTrackers.removeValue(forKey: sessionId)
        pendingSignals.removeValue(forKey: sessionId)
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
        directServers.removeValue(forKey: sessionId)?.close()
        directEndpointTrackers.removeValue(forKey: sessionId)
        pendingSignals.removeValue(forKey: sessionId)
        signalingClient.send([
            "type": "bye",
            "session_id": sessionId,
            "reason": "receiver_canceled",
        ])
    }

    static func crossNetworkDiagnosis(_ diag: NativeWebRTCDiagnostic) -> NativeP2PFailureReason {
        NativeP2PDiagnostics.crossNetworkDiagnosis(diag)
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
    private let watchdogTimeoutSeconds: TimeInterval
    private let destinationFor: (NativeFileType) -> NativeReceiveSaveDestination
    private let onReceiveState: (NativeReceiveTransferState?) -> Void
    private let onReceiverNotice: (String) -> Void
    private let onReceiveCompleted: (NativeReceiveHistoryItem) -> Void
    private let sessionKey: Data
    var sendControl: (Data) -> Void = { _ in }
    private var channelAttached = false
    private var firstChunkReceived = false
    private var channelReadyNoticeSent = false
    private var watchdogTask: Task<Void, Never>?
    private var watchdogStartedAt: Date?
    private var senderName = "跨网设备"
    private var files: [NativeTransferV3File] = []
    private var outputFiles: [Int: URL] = [:]
    private var outputHandles: [Int: FileHandle] = [:]
    private var completedChunks: [Int: Set<Int>] = [:]
    private var hashRetryCount: [Int: Int] = [:]
    private var pendingChunkFrames: [Data] = []
    private var confirmed = false
    private var canceled = false
    private var readySent = false
    private var totalBytes = 0
    private var receivedBytes = 0
    private var lastProgressPersistAt = 0.0
    private var chunksSinceProgressPersist = 0

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
        watchdogTimeoutSeconds: TimeInterval,
        receiveFileStore: NativeReceiveFileStore,
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        onReceiveState: @escaping (NativeReceiveTransferState?) -> Void,
        onReceiverNotice: @escaping (String) -> Void,
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
        self.watchdogTimeoutSeconds = watchdogTimeoutSeconds
        self.receiveFileStore = receiveFileStore
        self.destinationFor = destinationFor
        self.onReceiveState = onReceiveState
        self.onReceiverNotice = onReceiverNotice
        self.onReceiveCompleted = onReceiveCompleted
    }

    func attach(channel: NativeP2PDirectChannel) {
        sendControl = { data in
            Task(priority: .high) { @MainActor in
                _ = await channel.send(data)
            }
        }
        channelAttached = true
        nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_attach channel_open=true")
        if confirmed, !firstChunkReceived, !channelReadyNoticeSent {
            channelReadyNoticeSent = true
            onReceiverNotice("通道已就绪，等待数据...")
        }
        sendReadyAndDrainPending()
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
            outputFiles.removeAll(keepingCapacity: true)
            closeOutputHandles()
            for file in files {
                guard let fileURL = progressStore.outputFileURL(transferId: transferId, fileIndex: file.index),
                      let handle = openOutputHandle(fileURL: fileURL, sizeBytes: file.sizeBytes) else {
                    sendRetry(fileIndex: file.index, chunkIndex: 0)
                    continue
                }
                outputFiles[file.index] = fileURL
                outputHandles[file.index] = handle
                let restoredSet = restored[file.index] ?? []
                completedChunks[file.index] = restoredSet
            }
            receivedBytes = files.reduce(0) { total, file in
                total + (completedChunks[file.index] ?? []).reduce(0) { $0 + expectedChunkLength(file: file, chunkIndex: $1) }
            }
            persistProgressIfNeeded(force: true)
            let wasAlreadyConfirmed = confirmed
            confirmed = confirmed || autoAccept
            if wasAlreadyConfirmed {
                publishReceiveState(requiresConfirmation: false)
            } else {
                publishReceiveState(requiresConfirmation: !autoAccept)
            }
            if confirmed {
                startWatchdogIfNeeded()
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
                  bytes.count == expectedChunkLength(file: file, chunkIndex: chunkIndex),
                  let handle = outputHandles[fileIndex] else {
                sendRetry(fileIndex: fileIndex, chunkIndex: chunkIndex)
                return
            }
            if !firstChunkReceived {
                firstChunkReceived = true
                cancelWatchdog()
                nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_first_chunk file_index=\(fileIndex, privacy: .public) chunk_index=\(chunkIndex, privacy: .public)")
            }
            if completedChunks[fileIndex]?.contains(chunkIndex) != true {
                let offset = UInt64(chunkIndex * file.chunkSize)
                do {
                    try handle.seek(toOffset: offset)
                    try handle.write(contentsOf: bytes)
                } catch {
                    sendRetry(fileIndex: fileIndex, chunkIndex: chunkIndex)
                    return
                }
                completedChunks[fileIndex, default: []].insert(chunkIndex)
                receivedBytes += bytes.count
                progressStore.saveChunk(transferId: transferId, fileIndex: fileIndex, chunkIndex: chunkIndex, data: bytes)
                persistProgressIfNeeded()
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
        let manifestReady = !files.isEmpty
        nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_accept_clicked manifest_ready=\(manifestReady, privacy: .public) channel_open=\(self.channelAttached, privacy: .public)")
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
        } else {
            publishReceiveState(requiresConfirmation: false)
        }
        startWatchdogIfNeeded()
        onReceiverNotice("等待发送端建立连接，请稍候...")
        if channelAttached, !channelReadyNoticeSent {
            channelReadyNoticeSent = true
            onReceiverNotice("通道已就绪，等待数据...")
        }
        sendReadyAndDrainPending()
    }

    private func startWatchdogIfNeeded() {
        guard !firstChunkReceived, !canceled else {
            return
        }
        guard watchdogTask == nil else {
            return
        }
        watchdogStartedAt = Date()
        let timeoutSeconds = watchdogTimeoutSeconds
        watchdogTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
            guard let self else {
                return
            }
            if Task.isCancelled {
                return
            }
            self.onWatchdogFired()
        }
    }

    private func cancelWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = nil
    }

    private func onWatchdogFired() {
        if firstChunkReceived || canceled {
            return
        }
        let elapsedMs = watchdogStartedAt.map { Int(Date().timeIntervalSince($0) * 1000) } ?? -1
        nativeP2PLogger.warning("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_watchdog_fired elapsed_ms=\(elapsedMs, privacy: .public) manifest_ready=\(!self.files.isEmpty, privacy: .public) channel_attached=\(self.channelAttached, privacy: .public) ready_sent=\(self.readySent, privacy: .public)")
        onReceiverNotice("等待发送端建立连接超时（请检查双方网络后重试）")
        cancel()
    }

    func cancel() {
        canceled = true
        cancelWatchdog()
        pendingChunkFrames.removeAll(keepingCapacity: false)
        closeOutputHandles()
        outputFiles.removeAll(keepingCapacity: false)
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
        persistProgressIfNeeded(force: true)
        outputHandles.values.forEach { handle in
            try? handle.synchronize()
        }
        let preparedFiles = files.compactMap { file -> NativeReceivedPreparedFile? in
            guard let fileURL = outputFiles[file.index],
                  fileURL.fileSize == file.sizeBytes,
                  SHA256.hashFile(fileURL) == file.fileHash else {
                resetFileForRetry(file)
                return nil
            }
            return NativeReceivedPreparedFile(
                displayName: file.displayName,
                fileType: file.fileType,
                sizeBytes: file.sizeBytes,
                temporaryURL: fileURL
            )
        }
        guard preparedFiles.count == files.count else {
            return
        }
        closeOutputHandles()
        receiveFileStore.savePreparedFiles(senderName: senderName, files: preparedFiles, destinationFor: destinationFor) { [weak self] item in
            guard let self, let item else {
                self?.onReceiveState(nil)
                return
            }
            self.onReceiveCompleted(item)
            self.onReceiveState(nil)
            self.outputFiles.removeAll(keepingCapacity: false)
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
        resetOutputFile(file)
        persistProgressIfNeeded(force: true)
        for chunkIndex in 0..<file.chunkCount {
            sendRetry(fileIndex: file.index, chunkIndex: chunkIndex)
        }
    }

    private func openOutputHandle(fileURL: URL, sizeBytes: Int) -> FileHandle? {
        let directory = fileURL.deletingLastPathComponent()
        guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else {
            return nil
        }
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            guard FileManager.default.createFile(atPath: fileURL.path, contents: nil) else {
                return nil
            }
        }
        guard let handle = try? FileHandle(forWritingTo: fileURL) else {
            return nil
        }
        do {
            try handle.truncate(atOffset: UInt64(sizeBytes))
            return handle
        } catch {
            try? handle.close()
            return nil
        }
    }

    private func resetOutputFile(_ file: NativeTransferV3File) {
        if let handle = outputHandles.removeValue(forKey: file.index) {
            try? handle.close()
        }
        guard let fileURL = outputFiles[file.index] ?? progressStore.outputFileURL(transferId: transferId, fileIndex: file.index),
              let handle = openOutputHandle(fileURL: fileURL, sizeBytes: file.sizeBytes) else {
            return
        }
        outputFiles[file.index] = fileURL
        outputHandles[file.index] = handle
    }

    private func closeOutputHandles() {
        outputHandles.values.forEach { handle in
            try? handle.close()
        }
        outputHandles.removeAll(keepingCapacity: true)
    }

    private func persistProgressIfNeeded(force: Bool = false) {
        guard !files.isEmpty else {
            return
        }
        if !force {
            chunksSinceProgressPersist += 1
        }
        let now = Date().timeIntervalSince1970
        let elapsedMillis = lastProgressPersistAt == 0 ? Double.greatestFiniteMagnitude : (now - lastProgressPersistAt) * 1000
        if force || chunksSinceProgressPersist >= 16 || elapsedMillis >= 500 {
            progressStore.save(transferId: transferId, manifestHashB64: manifestHashB64, manifest: files, completedChunks: completedChunks)
            lastProgressPersistAt = now
            chunksSinceProgressPersist = 0
        }
    }

    private func expectedChunkLength(file: NativeTransferV3File, chunkIndex: Int) -> Int {
        let offset = chunkIndex * file.chunkSize
        return min(file.chunkSize, file.sizeBytes - offset)
    }

    private func sendAck(fileIndex: Int, chunkIndex: Int) {
        sendControl(NativeTransferProtocolV3.encodeAck(fileIndex: fileIndex, chunkIndex: chunkIndex))
    }

    private func sendReady() {
        sendControl(NativeTransferProtocolV3.encodeReady())
    }

    private func sendRetry(fileIndex: Int, chunkIndex: Int) {
        sendControl(NativeTransferProtocolV3.encodeRetry(fileIndex: fileIndex, chunkIndex: chunkIndex))
    }

    private func sendReadyAndDrainPending() {
        if !channelAttached {
            nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_ready_drain_skip reason=channel_not_attached confirmed=\(self.confirmed, privacy: .public) manifest_ready=\(!self.files.isEmpty, privacy: .public)")
            return
        }
        if !confirmed {
            nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_ready_drain_skip reason=not_confirmed")
            return
        }
        if files.isEmpty {
            nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_ready_drain_skip reason=manifest_empty")
            return
        }
        if readySent {
            return
        }
        sendReady()
        readySent = true
        nativeP2PLogger.debug("timing session=\(self.sessionId, privacy: .public) transfer=\(self.transferId, privacy: .public) stage=receiver_ready_sent pending_chunks=\(self.pendingChunkFrames.count, privacy: .public)")
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

private struct NativeDirectEndpoint {
    let name: String
    let host: String
    let port: UInt16
}

private struct NativeDirectTransportDiagnostic {
    var attemptPlan: String
    var endpointCount: Int = 0
    var endpoints: String = "none"
    var candidates: String = "none"
    var natDiagnostic: String = "none"
    var selected: String = "none"
    var attemptResult: String = "not_attempted"
    var lastError: String = "none"
}

private func nativeDirectAttemptPlanDescription(_ attempts: [NativeP2PTransportAttempt]) -> String {
    attempts.map { "\($0.name)/\(Int($0.timeoutSeconds))s" }.joined(separator: ",").nilIfBlank ?? "none"
}

private func nativeDirectEndpointSummary(_ endpoint: NativeDirectEndpoint) -> String {
    "\(endpoint.name)@[\(endpoint.host)]:\(endpoint.port)"
}

private func nativeDirectEndpointsDescription(_ endpoints: [NativeDirectEndpoint]) -> String {
    endpoints.map(nativeDirectEndpointSummary).joined(separator: ";").nilIfBlank ?? "none"
}

private func nativeDirectCandidatesDescription(_ candidates: [[String: Any]]) -> String {
    let values = candidates.compactMap { item -> String? in
        let type = (item["type"] as? String)?.nilIfBlank ?? "unknown"
        let proto = (item["protocol"] as? String)?.nilIfBlank ?? "unknown"
        let host = (item["host"] as? String)?.nilIfBlank ?? "unknown"
        let port = item["port"].map { "\($0)" }?.nilIfBlank ?? "unknown"
        let stable = item["mapping_stable"] as? Bool ?? false
        let priority = item["priority"].map { "\($0)" }?.nilIfBlank ?? "0"
        return "\(type)/\(proto)@\(host):\(port)|stable=\(stable)|priority=\(priority)"
    }
    return values.prefix(12).joined(separator: ";").nilIfBlank ?? "none"
}

private func nativeDirectNatDiagnosticDescription(_ diagnostic: [String: Any]?) -> String {
    guard let diagnostic else {
        return "none"
    }
    let udpProbeResult = (diagnostic["udp_probe_result"] as? String)?.nilIfBlank ?? "unknown"
    let mappingBehavior = (diagnostic["mapping_behavior"] as? String)?.nilIfBlank ?? "unknown"
    let stunSuccessCount = diagnostic["stun_success_count"].map { "\($0)" }?.nilIfBlank ?? "0"
    let stunErrorCount = diagnostic["stun_error_count"].map { "\($0)" }?.nilIfBlank ?? "0"
    return "udp_probe_result=\(udpProbeResult)|mapping_behavior=\(mappingBehavior)|stun_success_count=\(stunSuccessCount)|stun_error_count=\(stunErrorCount)"
}

private extension NativeWebRTCDiagnostic {
    mutating func applyDirect(_ direct: NativeDirectTransportDiagnostic) {
        directAttemptPlan = direct.attemptPlan
        directEndpointCount = direct.endpointCount
        directEndpoints = direct.endpoints
        directCandidates = direct.candidates
        directNatDiagnostic = direct.natDiagnostic
        directSelected = direct.selected
        directAttemptResult = direct.attemptResult
        directLastError = direct.lastError
    }
}

private struct NativeDirectPeerHandshake {
    let peerEphemeralPublicB64: String
    let peerAcceptSignatureB64: String
    let peerCompletedBitmapB64: String?
}

@MainActor
private final class NativePreparedP2PChannel {
    let sessionKey: Data
    let transportName: String
    let completedBitmapB64: String?
    let send: (Data) async -> Bool
    let sendBatch: (([Data]) async -> Bool)?
    private let closeUnused: @MainActor () -> Void
    private let closeSelected: @MainActor () -> Void

    init(
        sessionKey: Data,
        transportName: String,
        completedBitmapB64: String?,
        send: @escaping (Data) async -> Bool,
        sendBatch: (([Data]) async -> Bool)?,
        closeUnused: @escaping @MainActor () -> Void,
        closeSelected: @escaping @MainActor () -> Void
    ) {
        self.sessionKey = sessionKey
        self.transportName = transportName
        self.completedBitmapB64 = completedBitmapB64
        self.send = send
        self.sendBatch = sendBatch
        self.closeUnused = closeUnused
        self.closeSelected = closeSelected
    }

    func closeIfUnused() {
        closeUnused()
    }

    func closeAfterSelectedUse() {
        closeSelected()
    }
}

@MainActor
private final class NativeP2PConnectionRace {
    private let expectedCount: Int
    private var completedCount = 0
    private var selected: NativePreparedP2PChannel?
    private var continuation: CheckedContinuation<NativePreparedP2PChannel?, Never>?

    init(expectedCount: Int) {
        self.expectedCount = expectedCount
    }

    func submit(_ candidate: NativePreparedP2PChannel?) {
        completedCount += 1
        if let candidate {
            if selected == nil {
                selected = candidate
                continuation?.resume(returning: candidate)
                continuation = nil
            } else {
                candidate.closeIfUnused()
            }
        } else if completedCount >= expectedCount {
            continuation?.resume(returning: selected)
            continuation = nil
        }
    }

    func waitForWinner() async -> NativePreparedP2PChannel? {
        if let selected {
            return selected
        }
        if completedCount >= expectedCount {
            return nil
        }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }
}

@MainActor
private protocol NativeP2PDirectChannel: AnyObject {
    func send(_ data: Data) async -> Bool
    func close()
}

@MainActor
private final class NativeDirectEndpointTracker {
    private var endpoints: [NativeDirectEndpoint] = []
    private var endpointContinuation: CheckedContinuation<[NativeDirectEndpoint], Never>?
    private(set) var handshake: NativeDirectPeerHandshake?
    private(set) var diagnostic: NativeDirectTransportDiagnostic

    init() {
        diagnostic = NativeDirectTransportDiagnostic(
            attemptPlan: nativeDirectAttemptPlanDescription(directTransportAttemptPlan())
        )
    }

    func accept(_ message: [String: Any]) {
        if let peerEphemeral = message["receiver_x25519_eph_pub_b64"] as? String,
           let peerSignature = message["receiver_accept_signature_b64"] as? String {
            handshake = NativeDirectPeerHandshake(
                peerEphemeralPublicB64: peerEphemeral,
                peerAcceptSignatureB64: peerSignature,
                peerCompletedBitmapB64: message["completed_chunks_bitmap_b64"] as? String
            )
        }
        diagnostic.candidates = nativeDirectCandidatesDescription(message["candidates"] as? [[String: Any]] ?? [])
        diagnostic.natDiagnostic = nativeDirectNatDiagnosticDescription(message["nat_diagnostic"] as? [String: Any])
        guard let rawEndpoints = message["endpoints"] as? [[String: Any]] else {
            resumeIfNeeded()
            return
        }
        endpoints.append(
            contentsOf: rawEndpoints.compactMap { item in
                guard let name = item["name"] as? String,
                      let host = item["host"] as? String,
                      let portValue = item["port"] as? Int,
                      (1...65535).contains(portValue) else {
                    return nil
                }
                return NativeDirectEndpoint(name: name, host: host, port: UInt16(portValue))
            }
        )
        refreshEndpointDiagnostic()
        resumeIfNeeded()
    }

    func waitForEndpoints(seconds: TimeInterval) async -> [NativeDirectEndpoint] {
        if !endpoints.isEmpty {
            refreshEndpointDiagnostic()
            return endpoints
        }
        let received: [NativeDirectEndpoint] = await withCheckedContinuation { continuation in
            endpointContinuation = continuation
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
                resumeIfNeeded()
            }
        }
        if received.isEmpty {
            recordAttempt(
                selected: "none",
                result: "no_endpoint",
                error: "未在 \(Int(seconds))s 内收到 direct_endpoint 信令"
            )
        } else {
            refreshEndpointDiagnostic()
        }
        return received
    }

    func recordAttempt(
        selected: String? = nil,
        result: String,
        error: String? = nil
    ) {
        if diagnostic.attemptResult == "connected", result != "connected" {
            return
        }
        diagnostic.selected = selected?.nilIfBlank ?? diagnostic.selected
        diagnostic.attemptResult = result.nilIfBlank ?? "unknown"
        diagnostic.lastError = error?.nilIfBlank ?? "none"
    }

    func recordAttempt(
        endpoint: NativeDirectEndpoint,
        result: String,
        error: String? = nil
    ) {
        recordAttempt(
            selected: nativeDirectEndpointSummary(endpoint),
            result: result,
            error: error
        )
    }

    private func resumeIfNeeded() {
        guard let continuation = endpointContinuation else {
            return
        }
        endpointContinuation = nil
        continuation.resume(returning: endpoints)
    }

    private func refreshEndpointDiagnostic() {
        diagnostic.endpointCount = endpoints.count
        diagnostic.endpoints = nativeDirectEndpointsDescription(endpoints)
        if diagnostic.attemptResult == "not_attempted" {
            diagnostic.attemptResult = endpoints.isEmpty ? "no_endpoint" : "endpoint_received"
            diagnostic.lastError = endpoints.isEmpty ? "未收到 direct_endpoint 信令" : "none"
        }
    }
}

@MainActor
private final class NativeP2PFramedConnection: NativeP2PDirectChannel {
    private let connection: NWConnection
    private let onFrame: (Data) -> Void
    private var closed = false

    init(connection: NWConnection, onFrame: @escaping (Data) -> Void) {
        self.connection = connection
        self.onFrame = onFrame
    }

    static func connect(to endpoint: NativeDirectEndpoint, timeoutSeconds: TimeInterval, onFrame: @escaping (Data) -> Void) async -> NativeP2PFramedConnection? {
        let host = NWEndpoint.Host(endpoint.host)
        guard let port = NWEndpoint.Port(rawValue: endpoint.port) else {
            return nil
        }
        let channel = NativeP2PFramedConnection(
            connection: NWConnection(host: host, port: port, using: .tcp),
            onFrame: onFrame
        )
        guard await channel.startAndWait(timeoutSeconds: timeoutSeconds) else {
            channel.close()
            return nil
        }
        channel.receiveLength()
        return channel
    }

    func startAccepted() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                Task { @MainActor in self?.close() }
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
        receiveLength()
    }

    func send(_ data: Data) async -> Bool {
        guard !closed, data.count <= nativeP2PMaxDirectFrameBytes else {
            return false
        }
        return await withCheckedContinuation { continuation in
            connection.send(content: framed(data), completion: .contentProcessed { error in
                continuation.resume(returning: error == nil)
            })
        }
    }

    func close() {
        guard !closed else {
            return
        }
        closed = true
        connection.cancel()
    }

    private func startAndWait(timeoutSeconds: TimeInterval) async -> Bool {
        await withCheckedContinuation { continuation in
            var resumed = false
            let resume: (Bool) -> Void = { success in
                guard !resumed else {
                    return
                }
                resumed = true
                continuation.resume(returning: success)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    Task { @MainActor in
                        resume(true)
                    }
                case .failed, .cancelled:
                    Task { @MainActor in
                        resume(false)
                    }
                default:
                    break
                }
            }
            connection.start(queue: .global(qos: .userInitiated))
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(max(timeoutSeconds, 0) * 1_000_000_000))
                resume(false)
            }
        }
    }

    private func receiveLength() {
        guard !closed else {
            return
        }
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else {
                    return
                }
                guard error == nil, !isComplete, let data, data.count == 4 else {
                    self.close()
                    return
                }
                var lengthValue: UInt32 = 0
                _ = withUnsafeMutableBytes(of: &lengthValue) { data.copyBytes(to: $0) }
                let length = Int(UInt32(bigEndian: lengthValue))
                guard length > 0, length <= nativeP2PMaxDirectFrameBytes else {
                    self.close()
                    return
                }
                self.receivePayload(length: length)
            }
        }
    }

    private func receivePayload(length: Int) {
        connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else {
                    return
                }
                guard error == nil, !isComplete, let data, data.count == length else {
                    self.close()
                    return
                }
                self.onFrame(data)
                self.receiveLength()
            }
        }
    }

    private func framed(_ data: Data) -> Data {
        var length = UInt32(data.count).bigEndian
        var frame = withUnsafeBytes(of: &length) { Data($0) }
        frame.append(data)
        return frame
    }
}

#if PIKO_XQUIC_NATIVE
private typealias NativeXQuicFrameCallback = @convention(c) (Int64, UnsafePointer<UInt8>?, Int32, UnsafeMutableRawPointer?) -> Void
private typealias NativeXQuicClosedCallback = @convention(c) (Int64, UnsafeMutableRawPointer?) -> Void

@_silgen_name("piko_xquic_is_linked")
private func piko_xquic_is_linked() -> Int32

@_silgen_name("piko_xquic_open_server")
private func piko_xquic_open_server(
    _ bindHost: UnsafePointer<CChar>,
    _ alpn: UnsafePointer<CChar>,
    _ certificateDirectory: UnsafePointer<CChar>,
    _ stunTargets: UnsafePointer<CChar>,
    _ onFrame: NativeXQuicFrameCallback?,
    _ onClosed: NativeXQuicClosedCallback?,
    _ userData: UnsafeMutableRawPointer?
) -> Int64

@_silgen_name("piko_xquic_server_port")
private func piko_xquic_server_port(_ serverHandle: Int64) -> Int32

@_silgen_name("piko_xquic_mapped_endpoint")
private func piko_xquic_mapped_endpoint(_ serverHandle: Int64) -> UnsafePointer<CChar>?

@_silgen_name("piko_xquic_stun_probe_results")
private func piko_xquic_stun_probe_results(_ serverHandle: Int64) -> UnsafePointer<CChar>?

@_silgen_name("piko_xquic_close_server")
private func piko_xquic_close_server(_ serverHandle: Int64)

@_silgen_name("piko_xquic_open_client")
private func piko_xquic_open_client(
    _ host: UnsafePointer<CChar>,
    _ port: Int32,
    _ timeoutMillis: Int32,
    _ alpn: UnsafePointer<CChar>,
    _ onFrame: NativeXQuicFrameCallback?,
    _ onClosed: NativeXQuicClosedCallback?,
    _ userData: UnsafeMutableRawPointer?
) -> Int64

@_silgen_name("piko_xquic_send_frame")
private func piko_xquic_send_frame(_ channelHandle: Int64, _ data: UnsafePointer<UInt8>, _ size: Int32) -> Int32

@_silgen_name("piko_xquic_close_channel")
private func piko_xquic_close_channel(_ channelHandle: Int64)
#endif

@MainActor
private final class NativeXQuicEventTarget {
    let onFrame: (Int64, Data) -> Void
    let onClosed: (Int64) -> Void

    init(onFrame: @escaping (Int64, Data) -> Void, onClosed: @escaping (Int64) -> Void = { _ in }) {
        self.onFrame = onFrame
        self.onClosed = onClosed
    }
}

#if PIKO_XQUIC_NATIVE
private let nativeXQuicFrameCallback: NativeXQuicFrameCallback = { channelHandle, data, size, userData in
    guard let data, size > 0, let userData else {
        return
    }
    let frame = Data(bytes: data, count: Int(size))
    let retainedTarget = Unmanaged<NativeXQuicEventTarget>.fromOpaque(userData).retain().toOpaque()
    let retainedTargetAddress = UInt(bitPattern: retainedTarget)
    Task { @MainActor in
        guard let retainedTarget = UnsafeMutableRawPointer(bitPattern: retainedTargetAddress) else {
            return
        }
        let target = Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).takeRetainedValue()
        target.onFrame(channelHandle, frame)
    }
}

private let nativeXQuicClosedCallback: NativeXQuicClosedCallback = { channelHandle, userData in
    guard let userData else {
        return
    }
    let retainedTarget = Unmanaged<NativeXQuicEventTarget>.fromOpaque(userData).retain().toOpaque()
    let retainedTargetAddress = UInt(bitPattern: retainedTarget)
    Task { @MainActor in
        guard let retainedTarget = UnsafeMutableRawPointer(bitPattern: retainedTargetAddress) else {
            return
        }
        let target = Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).takeRetainedValue()
        target.onClosed(channelHandle)
    }
}
#endif

@MainActor
private final class NativeXQuicDirectChannel: NativeP2PDirectChannel {
    private let handle: Int64
    private let retainedTarget: UnsafeMutableRawPointer?
    private var closed = false

    init(handle: Int64, retainedTarget: UnsafeMutableRawPointer? = nil) {
        self.handle = handle
        self.retainedTarget = retainedTarget
    }

    func send(_ data: Data) async -> Bool {
        guard !closed, data.count > 0, data.count <= nativeP2PMaxDirectFrameBytes else {
            return false
        }
#if PIKO_XQUIC_NATIVE
        return data.withUnsafeBytes { buffer in
            guard let baseAddress = buffer.bindMemory(to: UInt8.self).baseAddress else {
                return false
            }
            return piko_xquic_send_frame(handle, baseAddress, Int32(data.count)) != 0
        }
#else
        return false
#endif
    }

    func close() {
        guard !closed else {
            return
        }
        closed = true
#if PIKO_XQUIC_NATIVE
        piko_xquic_close_channel(handle)
#endif
        if let retainedTarget {
            Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).release()
        }
    }
}

@MainActor
private final class NativeXQuicDirectServer {
    let port: UInt16
    let mappedHost: String?
    let mappedPort: Int
    let stunProbeResultsRaw: String?
    private let handle: Int64
    private let retainedTarget: UnsafeMutableRawPointer
    private var closed = false

    init(handle: Int64, port: UInt16, mappedHost: String?, mappedPort: Int, stunProbeResultsRaw: String?, retainedTarget: UnsafeMutableRawPointer) {
        self.handle = handle
        self.port = port
        self.mappedHost = mappedHost
        self.mappedPort = mappedPort
        self.stunProbeResultsRaw = stunProbeResultsRaw
        self.retainedTarget = retainedTarget
    }

    func close() {
        guard !closed else {
            return
        }
        closed = true
#if PIKO_XQUIC_NATIVE
        piko_xquic_close_server(handle)
#endif
        Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).release()
    }
}

@MainActor
private enum NativeXQuicDirectTransport {
    static var isAvailable: Bool {
#if PIKO_XQUIC_NATIVE
        return piko_xquic_is_linked() != 0
#else
        return false
#endif
    }

    static func openServer(sessionId: String, iceServers: [NativeIceServerConfig], receiver: NativeP2PReceiver) -> NativeXQuicDirectServer? {
#if PIKO_XQUIC_NATIVE
        guard isAvailable else {
            return nil
        }
        let target = NativeXQuicEventTarget { channelHandle, frame in
            let channel = NativeXQuicDirectChannel(handle: channelHandle)
            receiver.attach(channel: channel)
            receiver.receive(frame)
        }
        let retainedTarget = Unmanaged.passRetained(target).toOpaque()
        let cacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let stunTargetsText = iceServers.allStunUrls.joined(separator: "\n")
        let handle = "::".withCString { bindHost in
            "piko-v3".withCString { alpn in
                cacheDirectory.path.withCString { certDir in
                    stunTargetsText.withCString { stunTargets in
                        piko_xquic_open_server(bindHost, alpn, certDir, stunTargets, nativeXQuicFrameCallback, nativeXQuicClosedCallback, retainedTarget)
                    }
                }
            }
        }
        guard handle != 0 else {
            Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).release()
            return nil
        }
        let port = piko_xquic_server_port(handle)
        guard port > 0, port <= 65535 else {
            piko_xquic_close_server(handle)
            Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).release()
            return nil
        }
        let mapped = piko_xquic_mapped_endpoint(handle).flatMap { String(cString: $0).nativeXQuicMappedEndpoint() }
        let probeResultsRaw = piko_xquic_stun_probe_results(handle).flatMap { String(cString: $0) }
        return NativeXQuicDirectServer(
            handle: handle,
            port: UInt16(port),
            mappedHost: mapped?.host,
            mappedPort: mapped?.port ?? 0,
            stunProbeResultsRaw: probeResultsRaw,
            retainedTarget: retainedTarget
        )
#else
        return nil
#endif
    }

    static func openClient(endpoint: NativeDirectEndpoint, timeoutSeconds: TimeInterval, onFrame: @escaping (Data) -> Void) async -> NativeP2PDirectChannel? {
#if PIKO_XQUIC_NATIVE
        guard isAvailable else {
            return nil
        }
        let target = NativeXQuicEventTarget { _, frame in
            onFrame(frame)
        }
        let retainedTarget = Unmanaged.passRetained(target).toOpaque()
        let handle = endpoint.host.withCString { host in
            "piko-v3".withCString { alpn in
                piko_xquic_open_client(
                    host,
                    Int32(endpoint.port),
                    Int32(max(timeoutSeconds, 0) * 1000),
                    alpn,
                    nativeXQuicFrameCallback,
                    nativeXQuicClosedCallback,
                    retainedTarget
                )
            }
        }
        guard handle != 0 else {
            Unmanaged<NativeXQuicEventTarget>.fromOpaque(retainedTarget).release()
            return nil
        }
        return NativeXQuicDirectChannel(handle: handle, retainedTarget: retainedTarget)
#else
        return nil
#endif
    }
}

@MainActor
private final class NativeP2PDirectServer {
    private let listener: NWListener?
    private let xquicServer: NativeXQuicDirectServer?
    private var channels: [NativeP2PDirectChannel] = []

    private init(listener: NWListener?, xquicServer: NativeXQuicDirectServer?) {
        self.listener = listener
        self.xquicServer = xquicServer
    }

    static func start(sessionId: String, signalingClient: NativeSignalingClient, receiver: NativeP2PReceiver) -> NativeP2PDirectServer? {
        let addresses = publicIpv6Addresses()
        let xquicServer = NativeXQuicDirectTransport.openServer(sessionId: sessionId, iceServers: receiver.iceServers, receiver: receiver)
        guard !addresses.isEmpty,
              let port = NWEndpoint.Port(rawValue: 0),
              let listener = try? NWListener(using: .tcp, on: port) else {
            if let xquicServer {
                return startQuicOnly(
                    sessionId: sessionId,
                    signalingClient: signalingClient,
                    receiver: receiver,
                    addresses: addresses,
                    xquicServer: xquicServer
                )
            }
            xquicServer?.close()
            return nil
        }
        let server = NativeP2PDirectServer(listener: listener, xquicServer: xquicServer)
        listener.newConnectionHandler = { [weak server, weak receiver] connection in
            Task { @MainActor in
                guard let server, let receiver else {
                    connection.cancel()
                    return
                }
                let channel = NativeP2PFramedConnection(connection: connection) { data in
                    receiver.receive(data)
                }
                server.channels.append(channel)
                receiver.attach(channel: channel)
                channel.startAccepted()
            }
        }
        listener.start(queue: .global(qos: .userInitiated))
        guard let localPort = listener.port?.rawValue else {
            listener.cancel()
            xquicServer?.close()
            return nil
        }
        var endpoints: [[String: Any]] = []
        let stunProbeResultsList = parseNativeStunProbeResults(xquicServer?.stunProbeResultsRaw)
        let stunAggregation = aggregateNativeStunProbeResults(stunProbeResultsList)
        if let xquicServer {
            if let selectedPunchCandidate = stunAggregation.candidates.max(by: { $0.priority < $1.priority }) {
                endpoints.append([
                    "name": nativeP2PQuicUdpPunchEndpoint,
                    "host": selectedPunchCandidate.host,
                    "port": selectedPunchCandidate.port,
                ])
            }
            endpoints.append(
                contentsOf: addresses.map { address in
                    [
                        "name": nativeP2PQuicIpv6DirectEndpoint,
                        "host": address,
                        "port": Int(xquicServer.port),
                    ]
                }
            )
        }
        endpoints.append(
            contentsOf: addresses.map { address in
                [
                    "name": nativeP2PTcpIpv6DirectEndpoint,
                    "host": address,
                    "port": Int(localPort),
                ]
            }
        )
        let candidatesList: [[String: Any]] = stunAggregation.candidates.map { candidate in
            [
                "id": candidate.id,
                "transport": "quic",
                "protocol": "udp",
                "type": "srflx",
                "host": candidate.host,
                "port": candidate.port,
                "local_port": xquicServer.map { Int($0.port) } ?? 0,
                "foundation": candidate.mappingStable ? "udp4-stun-stable" : "udp4-stun-unknown",
                "priority": candidate.priority,
                "stun_server": candidate.stunServer,
                "mapping_stable": candidate.mappingStable,
            ]
        }
        let natDiagnostic: [String: Any] = [
            "udp_probe_result": stunAggregation.udpProbeResult,
            "mapping_behavior": stunAggregation.mappingBehavior,
            "stun_success_count": stunAggregation.stunSuccessCount,
            "stun_error_count": stunAggregation.stunErrorCount,
        ]
        signalingClient.send([
            "type": "direct_endpoint",
            "session_id": sessionId,
            "receiver_x25519_eph_pub_b64": receiver.receiverEphemeralPublicB64,
            "receiver_accept_signature_b64": receiver.receiverAcceptSignatureB64,
            "completed_chunks_bitmap_b64": receiver.completedBitmapB64 ?? "",
            "endpoints": endpoints,
            "candidates": candidatesList,
            "nat_diagnostic": natDiagnostic,
        ])
        return server
    }

    func close() {
        listener?.cancel()
        xquicServer?.close()
        channels.forEach { $0.close() }
        channels.removeAll()
    }

    private static func startQuicOnly(
        sessionId: String,
        signalingClient: NativeSignalingClient,
        receiver: NativeP2PReceiver,
        addresses: [String],
        xquicServer: NativeXQuicDirectServer
    ) -> NativeP2PDirectServer? {
        let server = NativeP2PDirectServer(listener: nil, xquicServer: xquicServer)
        var endpoints: [[String: Any]] = []
        let stunProbeResultsList = parseNativeStunProbeResults(xquicServer.stunProbeResultsRaw)
        let stunAggregation = aggregateNativeStunProbeResults(stunProbeResultsList)
        if let selectedPunchCandidate = stunAggregation.candidates.max(by: { $0.priority < $1.priority }) {
            endpoints.append([
                "name": nativeP2PQuicUdpPunchEndpoint,
                "host": selectedPunchCandidate.host,
                "port": selectedPunchCandidate.port,
            ])
        }
        endpoints.append(
            contentsOf: addresses.map { address in
                [
                    "name": nativeP2PQuicIpv6DirectEndpoint,
                    "host": address,
                    "port": Int(xquicServer.port),
                ]
            }
        )
        if endpoints.isEmpty {
            xquicServer.close()
            return nil
        }
        let candidatesList: [[String: Any]] = stunAggregation.candidates.map { candidate in
            [
                "id": candidate.id,
                "transport": "quic",
                "protocol": "udp",
                "type": "srflx",
                "host": candidate.host,
                "port": candidate.port,
                "local_port": Int(xquicServer.port),
                "foundation": candidate.mappingStable ? "udp4-stun-stable" : "udp4-stun-unknown",
                "priority": candidate.priority,
                "stun_server": candidate.stunServer,
                "mapping_stable": candidate.mappingStable,
            ]
        }
        let natDiagnostic: [String: Any] = [
            "udp_probe_result": stunAggregation.udpProbeResult,
            "mapping_behavior": stunAggregation.mappingBehavior,
            "stun_success_count": stunAggregation.stunSuccessCount,
            "stun_error_count": stunAggregation.stunErrorCount,
        ]
        signalingClient.send([
            "type": "direct_endpoint",
            "session_id": sessionId,
            "receiver_x25519_eph_pub_b64": receiver.receiverEphemeralPublicB64,
            "receiver_accept_signature_b64": receiver.receiverAcceptSignatureB64,
            "completed_chunks_bitmap_b64": receiver.completedBitmapB64 ?? "",
            "endpoints": endpoints,
            "candidates": candidatesList,
            "nat_diagnostic": natDiagnostic,
        ])
        return server
    }

    private static func publicIpv6Addresses() -> [String] {
        var interfacePointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfacePointer) == 0, let first = interfacePointer else {
            return []
        }
        defer { freeifaddrs(first) }
        var values = Set<String>()
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            guard (item.pointee.ifa_flags & UInt32(IFF_UP)) != 0,
                  (item.pointee.ifa_flags & UInt32(IFF_LOOPBACK)) == 0,
                  let address = item.pointee.ifa_addr,
                  Int32(address.pointee.sa_family) == AF_INET6 else {
                continue
            }
            let ipv6Sockaddr = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
            var bytes = ipv6Sockaddr.sin6_addr
            let isGlobalUnicast = withUnsafeBytes(of: &bytes) { rawBuffer -> Bool in
                guard let firstByte = rawBuffer.first else {
                    return false
                }
                return firstByte >= 0x20 && firstByte <= 0x3f
            }
            guard isGlobalUnicast else {
                continue
            }
            var mutableSockaddr = ipv6Sockaddr
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = withUnsafePointer(to: &mutableSockaddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getnameinfo($0, socklen_t(MemoryLayout<sockaddr_in6>.size), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                }
            }
            if result == 0 {
                let text = String(cString: host)
                values.insert(text.components(separatedBy: "%").first ?? text)
            }
        }
        return Array(values)
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

    func outputFileURL(transferId: String, fileIndex: Int) -> URL? {
        let dir = transferDir(transferId: transferId)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else {
            return nil
        }
        return dir.appendingPathComponent("\(fileIndex).part")
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

private extension URL {
    var fileSize: Int? {
        guard let values = try? resourceValues(forKeys: [.fileSizeKey]) else {
            return nil
        }
        return values.fileSize
    }
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
