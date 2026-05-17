import Foundation
@preconcurrency import WebKit

private enum NativeWebRTCTiming {
    static let sendOpenWaitSeconds: TimeInterval = 30
}

enum NativeP2PFailureReason: String, CaseIterable {
    case localNoCandidate = "LOCAL_NO_CANDIDATE"
    case remoteNoCandidate = "REMOTE_NO_CANDIDATE"
    case stunServersDegraded = "STUN_SERVERS_DEGRADED"
    case symmetricNat = "SYMMETRIC_NAT"
    case remoteMdnsOnly = "REMOTE_MDNS_ONLY"
    case connectivityCheckFailed = "CONNECTIVITY_CHECK_FAILED"
    case cgnatBothSides = "CGNAT_BOTH_SIDES"
    case natIncompatible = "NAT_INCOMPATIBLE"
    case genericTimeout = "GENERIC_TIMEOUT"

    var title: String {
        switch self {
        case .localNoCandidate: return "本机未能获取任何网络候选"
        case .remoteNoCandidate: return "对方未能获取任何网络候选"
        case .stunServersDegraded: return "网络环境无法访问公共 STUN 服务"
        case .symmetricNat: return "运营商 NAT 端口预测失败"
        case .remoteMdnsOnly: return "对端处于本地网络保护模式"
        case .connectivityCheckFailed: return "候选连通性检查全部失败"
        case .cgnatBothSides: return "双方均处于运营商级 NAT 后方"
        case .natIncompatible: return "双方 NAT 类型不兼容"
        case .genericTimeout: return "跨网直连超时"
        }
    }

    var body: String {
        switch self {
        case .localNoCandidate:
            return "当前设备未能枚举出可用的网络地址，无法发起 P2P 连接。"
        case .remoteNoCandidate:
            return "接收方设备未能枚举出可用的网络地址，无法接受 P2P 连接。"
        case .stunServersDegraded:
            return "多个 STUN 服务器解析或绑定失败，候选地址发现严重受阻。常见原因：DNS 劫持、UDP 拦截或 53/3478 等端口被防火墙阻断。"
        case .symmetricNat:
            return "检测到一方的 NAT 对同一内网端口分配了多个不同的公网端口（对称 NAT），跨网直连无法穿透。"
        case .remoteMdnsOnly:
            return "接收方仅暴露 mDNS 主机名，跨网无法解析。常见原因：iOS 未授予本地网络权限或对方处于强隐私模式。"
        case .connectivityCheckFailed:
            return "ICE 仍在收集候选时，已知候选对的连通性检查全部失败，双方网络可能互相不可达。"
        case .cgnatBothSides:
            return "双方网络均处于 CGNAT 后方，公网地址不可控，纯直连穿透在该网络组合下不可行。"
        case .natIncompatible:
            return "双方均只获取到 host/srflx 候选，跨 NAT 直连穿透未成功。"
        case .genericTimeout:
            return "ICE 在等待窗口内未能完成连接握手，原因不明。"
        }
    }

    var suggestion: String {
        switch self {
        case .localNoCandidate:
            return "请检查本机网络连接（Wi-Fi/移动数据），关闭可能拦截 UDP 的 VPN/代理后重试。"
        case .remoteNoCandidate:
            return "请确认对方处于联网状态，且未启用阻止 UDP 流量的 VPN/代理。"
        case .stunServersDegraded:
            return "请尝试切换到其他网络（如个人热点或不同的 Wi-Fi），或与网络管理员确认 UDP 出站是否被拦截。"
        case .symmetricNat:
            return "请尝试让双方接入同一 Wi-Fi，或切换到 5G/4G 个人热点后重试。"
        case .remoteMdnsOnly:
            return "请提示对方在系统设置中授予「本地网络」权限，或切换到同一 Wi-Fi 后重试。"
        case .connectivityCheckFailed:
            return "请确认两端的网络环境均允许出站 UDP，或切换到同一 Wi-Fi 后重试。"
        case .cgnatBothSides:
            return "请尝试让双方接入同一 Wi-Fi，或切换到家庭宽带/办公网络后重试。"
        case .natIncompatible:
            return "请尝试让双方接入同一 Wi-Fi 后重试。"
        case .genericTimeout:
            return "请稍后重试，或切换到同一 Wi-Fi 环境。"
        }
    }
}

struct NativeWebRTCDiagnostic {
    var directAttemptPlan: String = "unknown"
    var directEndpointCount: Int = 0
    var directEndpoints: String = "none"
    var directSelected: String = "none"
    var directAttemptResult: String = "not_attempted"
    var directLastError: String = "none"
    let offerSent: Bool
    let answerReceived: Bool
    let localIceCount: Int
    let remoteIceCount: Int
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
    let stunErrorRate: Double
    let gatheringIncomplete: Bool
    let symmetricNatSuspect: Bool
    let remoteOnlyMdns: Bool
    var failureReason: NativeP2PFailureReason?

    static let empty = NativeWebRTCDiagnostic(
        offerSent: false,
        answerReceived: false,
        localIceCount: 0,
        remoteIceCount: 0,
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
        stunErrorRate: 0.0,
        gatheringIncomplete: false,
        symmetricNatSuspect: false,
        remoteOnlyMdns: false,
        failureReason: nil
    )
}

@MainActor
final class NativeWebRTCSession: NSObject {
    private let sessionId: String
    private let iceServers: [NativeIceServerConfig]
    private let sendSignal: ([String: Any]) -> Void
    private let onOpen: () -> Void
    private let onBinary: (Data) -> Void
    private let onClose: () -> Void
    private let answerExtras: [String: String]
    private let userContentController = WKUserContentController()
    private lazy var webView: WKWebView = {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = userContentController
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        return view
    }()
    private var readyContinuation: CheckedContinuation<Bool, Never>?
    private var openContinuation: CheckedContinuation<Bool, Never>?
    private var peerEphemeralContinuation: CheckedContinuation<String?, Never>?
    private var isReady = false
    private var isOpen = false
    private var offerSent = false
    private var answerReceived = false
    private var localIceCount = 0
    private var remoteIceCount = 0
    private var localCandidateTypes: Set<String> = []
    private var remoteCandidateTypes: Set<String> = []
    private var localCandidateDetails: Set<String> = []
    private var remoteCandidateDetails: Set<String> = []
    private var iceConnectionState = "unknown"
    private var peerConnectionState = "unknown"
    private var iceGatheringState = "unknown"
    private var signalingState = "unknown"
    private var dataChannelState = "unknown"
    private var sendFailure = "none"
    private var iceCandidateErrors: [String] = []
    private var selectedCandidatePair = "none"
    private var iceCandidatePairStats = "none"
    private var peerEphemeralPublicB64: String?
    private(set) var peerAcceptSignatureB64: String?
    private(set) var peerCompletedBitmapB64: String?

    var diagnosticSnapshot: NativeWebRTCDiagnostic {
        NativeWebRTCDiagnostic(
            offerSent: offerSent,
            answerReceived: answerReceived,
            localIceCount: localIceCount,
            remoteIceCount: remoteIceCount,
            iceServerUrls: iceServers.map(\.urls).joined(separator: ",").nilIfBlank ?? "none",
            localCandidateTypes: Self.candidateTypesDescription(localCandidateTypes),
            remoteCandidateTypes: Self.candidateTypesDescription(remoteCandidateTypes),
            localCandidateDetails: Self.candidateDetailsDescription(localCandidateDetails),
            remoteCandidateDetails: Self.candidateDetailsDescription(remoteCandidateDetails),
            iceConnectionState: iceConnectionState,
            peerConnectionState: peerConnectionState,
            iceGatheringState: iceGatheringState,
            signalingState: signalingState,
            dataChannelState: dataChannelState,
            sendFailure: sendFailure,
            iceCandidateErrors: iceCandidateErrors.joined(separator: ";").nilIfBlank ?? "none",
            selectedCandidatePair: selectedCandidatePair,
            iceCandidatePairStats: iceCandidatePairStats,
            stunErrorRate: NativeP2PDiagnostics.computeStunErrorRate(
                iceServerUrls: iceServers.map(\.urls).joined(separator: ",").nilIfBlank ?? "none",
                iceCandidateErrors: iceCandidateErrors.joined(separator: ";").nilIfBlank ?? "none"
            ),
            gatheringIncomplete: NativeP2PDiagnostics.isGatheringIncomplete(iceGatheringState: iceGatheringState),
            symmetricNatSuspect:
                NativeP2PDiagnostics.detectSymmetricNatSuspect(details: Self.candidateDetailsDescription(localCandidateDetails)) ||
                NativeP2PDiagnostics.detectSymmetricNatSuspect(details: Self.candidateDetailsDescription(remoteCandidateDetails)),
            remoteOnlyMdns: NativeP2PDiagnostics.detectRemoteOnlyMdns(
                remoteTypes: Self.candidateTypesDescription(remoteCandidateTypes),
                remoteDetails: Self.candidateDetailsDescription(remoteCandidateDetails)
            ),
            failureReason: nil
        )
    }

    init(
        sessionId: String,
        iceServers: [NativeIceServerConfig],
        sendSignal: @escaping ([String: Any]) -> Void,
        onOpen: @escaping () -> Void,
        onBinary: @escaping (Data) -> Void,
        onClose: @escaping () -> Void,
        answerExtras: [String: String] = [:]
    ) {
        self.sessionId = sessionId
        self.iceServers = iceServers
        self.sendSignal = sendSignal
        self.onOpen = onOpen
        self.onBinary = onBinary
        self.onClose = onClose
        self.answerExtras = answerExtras
        super.init()
        userContentController.add(self, name: "pikoWebRTC")
        webView.loadHTMLString(Self.html, baseURL: nil)
    }

    func createOffer() async -> Bool {
        guard await prepare() else {
            return false
        }
        let created = await evaluate("return await createOfferer();")
        if created {
            offerSent = true
        }
        return created
    }

    func acceptOffer(_ sdp: String) async -> Bool {
        guard await prepare() else {
            return false
        }
        return await evaluate("return await acceptOffer(\(Self.jsonString(sdp)), \(answerExtrasJSON()));")
    }

    func acceptAnswer(
        _ sdp: String,
        peerEphemeralPublicB64: String? = nil,
        peerAcceptSignatureB64: String? = nil,
        peerCompletedBitmapB64: String? = nil
    ) async {
        answerReceived = true
        if let peerEphemeralPublicB64 {
            self.peerEphemeralPublicB64 = peerEphemeralPublicB64
            peerEphemeralContinuation?.resume(returning: peerEphemeralPublicB64)
            peerEphemeralContinuation = nil
        }
        if let peerAcceptSignatureB64 {
            self.peerAcceptSignatureB64 = peerAcceptSignatureB64
        }
        if let peerCompletedBitmapB64 {
            self.peerCompletedBitmapB64 = peerCompletedBitmapB64
        }
        _ = await prepare()
        _ = await evaluate("return await acceptAnswer(\(Self.jsonString(sdp)));")
    }

    func addCandidate(_ message: [String: Any]) async {
        _ = await prepare()
        guard let candidate = message["candidate"] as? String else {
            return
        }
        remoteIceCount += 1
        remoteCandidateTypes.insert(Self.candidateType(candidate))
        remoteCandidateDetails.insert(Self.candidateSummary(candidate))
        let sdpMid = message["sdp_mid"] as? String
        let sdpMLineIndex = message["sdp_m_line_index"] as? Int ?? 0
        _ = await evaluate(
            "return await addCandidate(\(Self.jsonString(candidate)), \(Self.jsonString(sdpMid)), \(sdpMLineIndex));"
        )
    }

    func send(_ data: Data) async -> Bool {
        guard await waitUntilOpen(seconds: NativeWebRTCTiming.sendOpenWaitSeconds) else {
            return false
        }
        return await evaluate("return sendBase64(\(Self.jsonString(data.base64EncodedString())));")
    }

    func sendBatch(_ items: [Data]) async -> Bool {
        guard isOpen, !items.isEmpty else { return false }
        let elements = items.map { "\"\($0.base64EncodedString())\"" }
        return await evaluate("return await sendMultipleBase64([\(elements.joined(separator: ","))]);")
    }

    func restartIce() async -> Bool {
        isOpen = false
        let restarted = await evaluate("return await restartIce();")
        if restarted {
            offerSent = true
        }
        return restarted
    }

    func diagnosticSnapshotWithStats() async -> NativeWebRTCDiagnostic {
        _ = await evaluate("return await collectIceCandidatePairStats();")
        return diagnosticSnapshot
    }

    func waitUntilOpen(seconds: TimeInterval) async -> Bool {
        if isOpen {
            return true
        }
        return await withCheckedContinuation { continuation in
            openContinuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                Task { @MainActor in
                    guard let self, !self.isOpen else {
                        return
                    }
                    self.openContinuation?.resume(returning: false)
                    self.openContinuation = nil
                }
            }
        }
    }

    func waitForPeerEphemeralPublic(seconds: TimeInterval) async -> String? {
        if let peerEphemeralPublicB64 {
            return peerEphemeralPublicB64
        }
        return await withCheckedContinuation { continuation in
            peerEphemeralContinuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                Task { @MainActor in
                    guard let self, self.peerEphemeralPublicB64 == nil else {
                        return
                    }
                    self.peerEphemeralContinuation?.resume(returning: nil)
                    self.peerEphemeralContinuation = nil
                }
            }
        }
    }

    func close() {
        _ = Task { @MainActor in
            _ = await evaluate("closePeer(); return true;")
            userContentController.removeScriptMessageHandler(forName: "pikoWebRTC")
            openContinuation?.resume(returning: false)
            openContinuation = nil
            readyContinuation?.resume(returning: false)
            readyContinuation = nil
        }
    }

    private func prepare() async -> Bool {
        if isReady {
            return await evaluate("return setupPeer(\(iceServersJSON()));")
        }
        let didLoad = await withCheckedContinuation { continuation in
            readyContinuation = continuation
        }
        guard didLoad else {
            return false
        }
        return await evaluate("return setupPeer(\(iceServersJSON()));")
    }

    private func markReady() {
        isReady = true
        readyContinuation?.resume(returning: true)
        readyContinuation = nil
    }

    private func markOpen() {
        isOpen = true
        onOpen()
        openContinuation?.resume(returning: true)
        openContinuation = nil
    }

    private func evaluate(_ script: String) async -> Bool {
        do {
            let value = try await webView.callAsyncJavaScript(script, arguments: [:], in: nil, contentWorld: .page)
            if let bool = value as? Bool {
                return bool
            }
            if let number = value as? NSNumber {
                return number.boolValue
            }
            return true
        } catch {
            return false
        }
    }

    private func iceServersJSON() -> String {
        let urls = iceServers.map(\.urls)
        guard let data = try? JSONSerialization.data(withJSONObject: urls),
              let json = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return json
    }

    private func answerExtrasJSON() -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: answerExtras),
              let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }

    private static func jsonString(_ value: String?) -> String {
        guard let value,
              let data = try? JSONSerialization.data(withJSONObject: [value]),
              let json = String(data: data, encoding: .utf8) else {
            return "null"
        }
        return String(json.dropFirst().dropLast())
    }

    private static func candidateType(_ candidate: String) -> String {
        guard let range = candidate.range(of: #" typ ([A-Za-z0-9_-]+)"#, options: .regularExpression) else {
            return "unknown"
        }
        return candidate[range].split(separator: " ").last.map { String($0).lowercased() } ?? "unknown"
    }

    private static func candidateTypesDescription(_ types: Set<String>) -> String {
        let values = types.filter { !$0.isEmpty }.sorted()
        return values.isEmpty ? "none" : values.joined(separator: ",")
    }

    private static func candidateDetailsDescription(_ details: Set<String>) -> String {
        let values = details.filter { !$0.isEmpty }.sorted().prefix(12)
        return values.isEmpty ? "none" : values.joined(separator: ";")
    }

    private static func candidateSummary(_ candidate: String) -> String {
        let parts = candidate
            .split(whereSeparator: { $0 == " " || $0 == "\t" })
            .map(String.init)
        let proto = parts.indices.contains(2) ? parts[2].lowercased() : "unknown"
        let address = parts.indices.contains(4) ? addressKind(parts[4]) : "unknown"
        let port = parts.indices.contains(5) && !parts[5].isEmpty ? parts[5] : "unknown"
        var values = [
            "type=\(candidateType(candidate))",
            "proto=\(proto)",
            "addr=\(address)",
            "port=\(port)"
        ]
        if let relatedAddress = regexCapture(pattern: #"\braddr\s+(\S+)"#, in: candidate) {
            values.append("raddr=\(addressKind(relatedAddress))")
        }
        if let relatedPort = regexCapture(pattern: #"\brport\s+(\S+)"#, in: candidate) {
            values.append("rport=\(relatedPort)")
        }
        return values.joined(separator: "/")
    }

    private static func addressKind(_ value: String) -> String {
        let lowercased = value.lowercased()
        if lowercased.contains(":") {
            if lowercased == "::1" { return "loopback-ipv6" }
            if lowercased.hasPrefix("fe80:") { return "link-local-ipv6" }
            if lowercased.hasPrefix("fd") || lowercased.hasPrefix("fc") { return "ula-ipv6" }
            return "public-ipv6"
        }
        let octets = lowercased.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4 else {
            return "unknown"
        }
        let first = octets[0]
        let second = octets[1]
        if first == 10 { return "private-ipv4" }
        if first == 172 && (16...31).contains(second) { return "private-ipv4" }
        if first == 192 && second == 168 { return "private-ipv4" }
        if first == 169 && second == 254 { return "link-local-ipv4" }
        if first == 100 && (64...127).contains(second) { return "cgnat-ipv4" }
        if first == 127 { return "loopback-ipv4" }
        return "public-ipv4"
    }

    private static func regexCapture(pattern: String, in value: String) -> String? {
        guard let range = value.range(of: pattern, options: .regularExpression) else {
            return nil
        }
        return value[range].split(separator: " ").last.map(String.init)
    }

    private static let html = """
    <!doctype html>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <script>
    const native = window.webkit.messageHandlers.pikoWebRTC;
    let pc = null;
    let channel = null;
    const pendingIceCandidates = [];
    const IPV6_DIRECT_CANDIDATE_PRIORITY = 2130706431;
    function post(message) { native.postMessage(message); }
    function toBase64(buffer) {
      let binary = "";
      const bytes = new Uint8Array(buffer);
      for (let i = 0; i < bytes.length; i += 1) {
        binary += String.fromCharCode(bytes[i]);
      }
      return btoa(binary);
    }
    function fromBase64(value) {
      const binary = atob(value);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i);
      }
      return bytes;
    }
    function candidateType(candidate) {
      const match = candidate.match(/ typ ([A-Za-z0-9_-]+)/);
      return match ? match[1].toLowerCase() : "unknown";
    }
    function addressKind(value) {
      const lower = String(value || "unknown").toLowerCase();
      if (lower.includes(":")) {
        if (lower === "::1") { return "loopback-ipv6"; }
        if (lower.startsWith("fe80:")) { return "link-local-ipv6"; }
        if (lower.startsWith("fd") || lower.startsWith("fc")) { return "ula-ipv6"; }
        return "public-ipv6";
      }
      const octets = lower.split(".").map((part) => Number.parseInt(part, 10));
      if (octets.length !== 4 || octets.some((part) => Number.isNaN(part))) { return "unknown"; }
      if (octets[0] === 10) { return "private-ipv4"; }
      if (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) { return "private-ipv4"; }
      if (octets[0] === 192 && octets[1] === 168) { return "private-ipv4"; }
      if (octets[0] === 169 && octets[1] === 254) { return "link-local-ipv4"; }
      if (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127) { return "cgnat-ipv4"; }
      if (octets[0] === 127) { return "loopback-ipv4"; }
      return "public-ipv4";
    }
    function prioritizeCandidateForSignaling(candidate) {
      const parts = String(candidate || "").trim().split(/\\s+/);
      if (parts.length < 8) { return candidate; }
      if (parts[2].toLowerCase() !== "udp") { return candidate; }
      if (parts[6].toLowerCase() !== "typ") { return candidate; }
      if (parts[7].toLowerCase() !== "host") { return candidate; }
      if (addressKind(parts[4]) !== "public-ipv6") { return candidate; }
      const priority = Number.parseInt(parts[3], 10);
      if (!Number.isFinite(priority) || priority >= IPV6_DIRECT_CANDIDATE_PRIORITY) { return candidate; }
      parts[3] = String(IPV6_DIRECT_CANDIDATE_PRIORITY);
      return parts.join(" ");
    }
    function candidateStatsSummary(stat) {
      const address = stat.address || stat.ip || stat.relatedAddress || "unknown";
      return [
        `type=${stat.candidateType || "unknown"}`,
        `proto=${stat.protocol || "unknown"}`,
        `addr=${addressKind(address)}`,
        `port=${stat.port || "unknown"}`,
        `network=${stat.networkType || "unknown"}`
      ].join("/");
    }
    async function collectIceCandidatePairStats() {
      if (!pc || !pc.getStats) {
        post({ kind: "ice_candidate_pair_stats", value: "stats_unavailable", selected_pair: "none" });
        return true;
      }
      try {
        const report = await pc.getStats();
        const candidates = new Map();
        const pairs = [];
        report.forEach((stat) => {
          if (stat.type === "local-candidate" || stat.type === "remote-candidate") {
            candidates.set(stat.id, stat);
          }
        });
        report.forEach((stat) => {
          if (stat.type !== "candidate-pair") { return; }
          const local = candidates.get(stat.localCandidateId);
          const remote = candidates.get(stat.remoteCandidateId);
          pairs.push({
            selected: stat.selected === true || (stat.nominated === true && stat.state === "succeeded"),
            text: [
              `id=${stat.id}`,
              `state=${stat.state || "unknown"}`,
              `nominated=${stat.nominated ?? "unknown"}`,
              `selected=${stat.selected ?? "unknown"}`,
              `rtt=${stat.currentRoundTripTime ?? "unknown"}`,
              `sent=${stat.bytesSent ?? "unknown"}`,
              `recv=${stat.bytesReceived ?? "unknown"}`,
              `local=${local ? candidateStatsSummary(local) : "unknown"}`,
              `remote=${remote ? candidateStatsSummary(remote) : "unknown"}`
            ].join("|")
          });
        });
        pairs.sort((a, b) => Number(b.selected) - Number(a.selected));
        const value = pairs.slice(0, 12).map((pair) => pair.text).join(";") || "none";
        const selected = pairs.find((pair) => pair.selected)?.text || "none";
        post({ kind: "ice_candidate_pair_stats", value, selected_pair: selected });
      } catch (error) {
        post({ kind: "ice_candidate_pair_stats", value: `stats_error=${error && error.name ? error.name : "unknown"}`, selected_pair: "none" });
      }
      return true;
    }
    function attachDataChannel(nextChannel) {
      channel = nextChannel;
      channel.binaryType = "arraybuffer";
      post({ kind: "data_channel_state", value: channel.readyState });
      channel.onopen = () => {
        post({ kind: "data_channel_state", value: channel.readyState });
        post({ kind: "open" });
      };
      channel.onclose = () => {
        post({ kind: "data_channel_state", value: channel.readyState });
        post({ kind: "close" });
      };
      channel.onerror = () => {
        post({ kind: "data_channel_state", value: channel.readyState });
        post({ kind: "close" });
      };
      channel.onmessage = async (event) => {
        const buffer = event.data instanceof ArrayBuffer ? event.data : await event.data.arrayBuffer();
        post({ kind: "binary", data: toBase64(buffer) });
      };
    }
    function setupPeer(urls) {
      if (pc) { return true; }
      pc = new RTCPeerConnection({
        iceServers: urls.map((url) => ({ urls: url })),
        iceCandidatePoolSize: 4,
        bundlePolicy: "max-bundle",
        rtcpMuxPolicy: "require",
        iceTransportPolicy: "all"
      });
      post({ kind: "ice_state", value: pc.iceConnectionState });
      post({ kind: "peer_connection_state", value: pc.connectionState || "unknown" });
      post({ kind: "ice_gathering_state", value: pc.iceGatheringState });
      post({ kind: "signaling_state", value: pc.signalingState });
      pc.oniceconnectionstatechange = () => post({ kind: "ice_state", value: pc.iceConnectionState });
      pc.onconnectionstatechange = () => post({ kind: "peer_connection_state", value: pc.connectionState || "unknown" });
      pc.onicegatheringstatechange = () => post({ kind: "ice_gathering_state", value: pc.iceGatheringState });
      pc.onsignalingstatechange = () => post({ kind: "signaling_state", value: pc.signalingState });
      pc.onicecandidateerror = (event) => {
        post({
          kind: "ice_candidate_error",
          url: event.url || "",
          address: event.address || "",
          port: event.port || 0,
          error_code: event.errorCode || 0,
          error_text: event.errorText || ""
        });
      };
      pc.onicecandidate = (event) => {
        if (!event.candidate) { return; }
        const candidate = prioritizeCandidateForSignaling(event.candidate.candidate);
        post({
          kind: "signal",
          type: "ice_candidate",
          candidate,
          candidate_type: candidateType(candidate),
          sdp_mid: event.candidate.sdpMid,
          sdp_m_line_index: event.candidate.sdpMLineIndex
        });
      };
      pc.ondatachannel = (event) => attachDataChannel(event.channel);
      return true;
    }
    async function flushPendingIceCandidates() {
      while (pendingIceCandidates.length > 0) {
        await pc.addIceCandidate(pendingIceCandidates.shift());
      }
    }
    async function createOfferer() {
      attachDataChannel(pc.createDataChannel("piko-v3", { ordered: true }));
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      post({ kind: "signal", type: "offer", sdp: offer.sdp });
      return true;
    }
    async function acceptOffer(sdp, answerExtras) {
      await pc.setRemoteDescription({ type: "offer", sdp });
      await flushPendingIceCandidates();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      post({ kind: "signal", type: "answer", sdp: answer.sdp, ...answerExtras });
      return true;
    }
    async function acceptAnswer(sdp) {
      await pc.setRemoteDescription({ type: "answer", sdp });
      await flushPendingIceCandidates();
      return true;
    }
    async function addCandidate(candidate, sdpMid, sdpMLineIndex) {
      const iceCandidate = { candidate, sdpMid, sdpMLineIndex };
      if (!pc.remoteDescription) {
        pendingIceCandidates.push(iceCandidate);
        return true;
      }
      await pc.addIceCandidate(iceCandidate);
      return true;
    }
    async function restartIce() {
      if (!pc) { return false; }
      pc.restartIce();
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      post({ kind: "signal", type: "offer", sdp: offer.sdp });
      return true;
    }
    function recordSendFailure(reason, frameBytes) {
      post({
        kind: "send_failure",
        reason: reason || "unknown",
        state: channel ? channel.readyState : "missing",
        buffered_bytes: channel ? channel.bufferedAmount : 0,
        frame_bytes: frameBytes || 0
      });
    }
    async function waitForWritableChannel(frameBytes) {
      if (!channel || channel.readyState !== "open") {
        recordSendFailure("channel_not_open", frameBytes);
        return false;
      }
      const highWaterMark = 512 * 1024;
      if (channel.bufferedAmount <= highWaterMark) { return true; }
      const writable = await new Promise((resolve) => {
        const timeout = setTimeout(() => {
          cleanup();
          resolve(channel && channel.readyState === "open" && channel.bufferedAmount <= highWaterMark);
        }, 15000);
        const cleanup = () => {
          clearTimeout(timeout);
          if (channel) { channel.onbufferedamountlow = null; }
        };
        channel.bufferedAmountLowThreshold = 256 * 1024;
        channel.onbufferedamountlow = () => {
          cleanup();
          resolve(channel && channel.readyState === "open");
        };
      });
      if (!writable) { recordSendFailure("buffer_timeout", frameBytes); }
      return writable;
    }
    async function sendBase64(value) {
      const bytes = fromBase64(value);
      if (!await waitForWritableChannel(bytes.byteLength)) { return false; }
      try {
        channel.send(bytes);
        return true;
      } catch (error) {
        recordSendFailure(`send_exception=${error && error.name ? error.name : "unknown"}`, bytes.byteLength);
        return false;
      }
    }
    async function sendMultipleBase64(arr) {
      for (const b64 of arr) {
        const bytes = fromBase64(b64);
        if (!await waitForWritableChannel(bytes.byteLength)) { return false; }
        try {
          channel.send(bytes);
        } catch (error) {
          recordSendFailure(`send_exception=${error && error.name ? error.name : "unknown"}`, bytes.byteLength);
          return false;
        }
      }
      return true;
    }
    function closePeer() {
      if (channel) { channel.close(); }
      if (pc) { pc.close(); }
      channel = null;
      pc = null;
    }
    post({ kind: "ready" });
    </script>
    """
}

extension NativeWebRTCSession: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        markReady()
    }
}

extension NativeWebRTCSession: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let kind = body["kind"] as? String else {
            return
        }
        switch kind {
        case "ready":
            markReady()
        case "open":
            markOpen()
        case "close":
            isOpen = false
            onClose()
        case "ice_state":
            if let value = body["value"] as? String {
                iceConnectionState = value
            }
        case "peer_connection_state":
            if let value = body["value"] as? String {
                peerConnectionState = value
            }
        case "ice_gathering_state":
            if let value = body["value"] as? String {
                iceGatheringState = value
            }
        case "signaling_state":
            if let value = body["value"] as? String {
                signalingState = value
            }
        case "data_channel_state":
            if let value = body["value"] as? String {
                dataChannelState = value
            }
        case "send_failure":
            let reason = (body["reason"] as? String)?.nilIfBlank ?? "unknown"
            let state = (body["state"] as? String)?.nilIfBlank ?? "unknown"
            let bufferedBytes = body["buffered_bytes"].map { "\($0)" } ?? "unknown"
            let frameBytes = body["frame_bytes"].map { "\($0)" } ?? "unknown"
            sendFailure = "reason=\(reason)|state=\(state)|buffered_bytes=\(bufferedBytes)|frame_bytes=\(frameBytes)"
        case "ice_candidate_error":
            let url = (body["url"] as? String)?.nilIfBlank ?? "unknown-url"
            let address = (body["address"] as? String)?.nilIfBlank ?? "unknown-address"
            let port = body["port"] as? Int ?? 0
            let code = body["error_code"] as? Int ?? 0
            let text = (body["error_text"] as? String)?.nilIfBlank ?? "unknown-error"
            iceCandidateErrors.append("url=\(url)|address=\(address):\(port)|code=\(code)|text=\(text)")
        case "ice_candidate_pair_stats":
            if let value = (body["value"] as? String)?.nilIfBlank {
                iceCandidatePairStats = value
            }
            if let selectedPair = (body["selected_pair"] as? String)?.nilIfBlank,
               selectedPair != "none" {
                selectedCandidatePair = selectedPair
            }
        case "binary":
            guard let base64 = body["data"] as? String,
                  let data = Data(base64Encoded: base64) else {
                return
            }
            onBinary(data)
        case "signal":
            var signal = body
            signal.removeValue(forKey: "kind")
            signal["session_id"] = sessionId
            if signal["type"] as? String == "ice_candidate" {
                localIceCount += 1
                if let candidate = signal["candidate"] as? String {
                    localCandidateDetails.insert(Self.candidateSummary(candidate))
                }
                if let candidateType = signal["candidate_type"] as? String {
                    localCandidateTypes.insert(candidateType)
                } else if let candidate = signal["candidate"] as? String {
                    localCandidateTypes.insert(Self.candidateType(candidate))
                }
            }
            if let peerKey = signal["receiver_x25519_eph_pub_b64"] as? String {
                peerEphemeralPublicB64 = peerKey
                peerEphemeralContinuation?.resume(returning: peerKey)
                peerEphemeralContinuation = nil
            }
            if let peerSignature = signal["receiver_accept_signature_b64"] as? String {
                peerAcceptSignatureB64 = peerSignature
            }
            if let peerBitmap = signal["completed_chunks_bitmap_b64"] as? String {
                peerCompletedBitmapB64 = peerBitmap
            }
            sendSignal(signal)
        default:
            break
        }
    }
}

enum NativeP2PDiagnostics {
    static func computeStunErrorRate(iceServerUrls: String, iceCandidateErrors: String) -> Double {
        let servers = Set(
            iceServerUrls.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        )
        guard !servers.isEmpty else { return 0.0 }
        guard !iceCandidateErrors.isEmpty, iceCandidateErrors != "none" else { return 0.0 }
        let urlRegex = try? NSRegularExpression(pattern: "url=([^|]+)")
        var erroredUrls: Set<String> = []
        for entry in iceCandidateErrors.split(separator: ";") {
            let s = String(entry)
            let range = NSRange(s.startIndex..<s.endIndex, in: s)
            guard let match = urlRegex?.firstMatch(in: s, options: [], range: range),
                  match.numberOfRanges >= 2,
                  let captured = Range(match.range(at: 1), in: s) else {
                continue
            }
            let url = s[captured].trimmingCharacters(in: .whitespaces)
            if !url.isEmpty { erroredUrls.insert(url) }
        }
        let degraded = servers.filter { erroredUrls.contains($0) }.count
        return Double(degraded) / Double(servers.count)
    }

    static func isGatheringIncomplete(iceGatheringState: String) -> Bool {
        iceGatheringState.lowercased() != "complete"
    }

    static func shouldContinueWaitingForIce(_ diag: NativeWebRTCDiagnostic) -> Bool {
        if diag.dataChannelState.lowercased() == "open" { return false }
        if diag.iceConnectionState.lowercased() == "connected" { return false }
        if diag.iceConnectionState.lowercased() == "completed" { return false }
        let gatheringComplete = diag.iceGatheringState.lowercased() == "complete"
        if gatheringComplete && diag.localIceCount == 0 { return false }
        if gatheringComplete && diag.remoteIceCount == 0 { return false }
        if gatheringComplete && diag.localCandidateTypes == "none" { return false }
        if gatheringComplete && diag.remoteCandidateTypes == "none" { return false }
        return true
    }

    static func detectSymmetricNatSuspect(details: String) -> Bool {
        guard !details.isEmpty, details != "none" else { return false }
        let addrRegex = try? NSRegularExpression(pattern: "(?:^|/)addr=([^/]+)")
        let portRegex = try? NSRegularExpression(pattern: "(?:^|/)port=([0-9]+)")
        let rportRegex = try? NSRegularExpression(pattern: "rport=([0-9]+)")
        var mappings: [String: Set<String>] = [:]
        for entry in details.split(separator: ";") {
            let s = String(entry)
            guard s.contains("type=srflx") else { continue }
            guard let addr = NativeP2PDiagnostics.firstCapture(regex: addrRegex, in: s),
                  let port = NativeP2PDiagnostics.firstCapture(regex: portRegex, in: s),
                  let rport = NativeP2PDiagnostics.firstCapture(regex: rportRegex, in: s)
            else { continue }
            guard rport != "0" else { continue }
            let key = "\(addr)|\(rport)"
            mappings[key, default: []].insert(port)
        }
        return mappings.values.contains { $0.count >= 2 }
    }

    static func detectRemoteOnlyMdns(remoteTypes: String, remoteDetails: String) -> Bool {
        let types = Set(
            remoteTypes.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        )
        guard types == ["host"] else { return false }
        guard !remoteDetails.isEmpty, remoteDetails != "none" else { return false }
        let entries = remoteDetails.split(separator: ";").map(String.init).filter { $0.contains("type=host") }
        guard !entries.isEmpty else { return false }
        return entries.allSatisfy { $0.contains("addr=mdns") }
    }

    static func crossNetworkDiagnosis(_ diag: NativeWebRTCDiagnostic) -> NativeP2PFailureReason {
        let localTypes = Set(
            diag.localCandidateTypes.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
        )
        let remoteTypes = Set(
            diag.remoteCandidateTypes.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
        )
        let hasRelay = localTypes.contains("relay") || remoteTypes.contains("relay")
        let allowed: Set<String> = ["host", "srflx", "none"]
        let onlySrflxAndHost = !hasRelay && localTypes.isSubset(of: allowed) && remoteTypes.isSubset(of: allowed)
        let hasCgnat =
            diag.localCandidateDetails.contains("cgnat") ||
            diag.remoteCandidateDetails.contains("cgnat")

        if diag.localCandidateTypes == "none" { return .localNoCandidate }
        if diag.remoteCandidateTypes == "none" { return .remoteNoCandidate }
        if diag.stunErrorRate >= 0.5 { return .stunServersDegraded }
        if diag.symmetricNatSuspect { return .symmetricNat }
        if diag.remoteOnlyMdns { return .remoteMdnsOnly }
        if diag.gatheringIncomplete && diag.selectedCandidatePair == "none" {
            return .connectivityCheckFailed
        }
        if onlySrflxAndHost && hasCgnat { return .cgnatBothSides }
        if onlySrflxAndHost { return .natIncompatible }
        return .genericTimeout
    }

    private static func firstCapture(regex: NSRegularExpression?, in s: String) -> String? {
        guard let regex else { return nil }
        let range = NSRange(s.startIndex..<s.endIndex, in: s)
        guard let match = regex.firstMatch(in: s, options: [], range: range),
              match.numberOfRanges >= 2,
              let captured = Range(match.range(at: 1), in: s) else {
            return nil
        }
        return String(s[captured])
    }
}
