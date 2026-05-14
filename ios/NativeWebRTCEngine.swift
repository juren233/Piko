import Foundation
@preconcurrency import WebKit

struct NativeWebRTCDiagnostic {
    let offerSent: Bool
    let answerReceived: Bool
    let localIceCount: Int
    let remoteIceCount: Int
    let iceConnectionState: String
    let dataChannelState: String

    static let empty = NativeWebRTCDiagnostic(
        offerSent: false,
        answerReceived: false,
        localIceCount: 0,
        remoteIceCount: 0,
        iceConnectionState: "unknown",
        dataChannelState: "unknown"
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
    private var iceConnectionState = "unknown"
    private var dataChannelState = "unknown"
    private var peerEphemeralPublicB64: String?
    private(set) var peerAcceptSignatureB64: String?
    private(set) var peerCompletedBitmapB64: String?

    var diagnosticSnapshot: NativeWebRTCDiagnostic {
        NativeWebRTCDiagnostic(
            offerSent: offerSent,
            answerReceived: answerReceived,
            localIceCount: localIceCount,
            remoteIceCount: remoteIceCount,
            iceConnectionState: iceConnectionState,
            dataChannelState: dataChannelState
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
        let sdpMid = message["sdp_mid"] as? String
        let sdpMLineIndex = message["sdp_m_line_index"] as? Int ?? 0
        _ = await evaluate(
            "return await addCandidate(\(Self.jsonString(candidate)), \(Self.jsonString(sdpMid)), \(sdpMLineIndex));"
        )
    }

    func send(_ data: Data) async -> Bool {
        guard await waitUntilOpen(seconds: 15) else {
            return false
        }
        return await evaluate("return sendBase64(\(Self.jsonString(data.base64EncodedString())));")
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

    private static let html = """
    <!doctype html>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <script>
    const native = window.webkit.messageHandlers.pikoWebRTC;
    let pc = null;
    let channel = null;
    const pendingIceCandidates = [];
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
      pc = new RTCPeerConnection({ iceServers: urls.map((url) => ({ urls: url })) });
      pc.oniceconnectionstatechange = () => post({ kind: "ice_state", value: pc.iceConnectionState });
      pc.onicecandidate = (event) => {
        if (!event.candidate) { return; }
        post({
          kind: "signal",
          type: "ice_candidate",
          candidate: event.candidate.candidate,
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
    async function waitForWritableChannel() {
      if (!channel || channel.readyState !== "open") { return false; }
      const highWaterMark = 512 * 1024;
      if (channel.bufferedAmount <= highWaterMark) { return true; }
      return await new Promise((resolve) => {
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
    }
    async function sendBase64(value) {
      if (!await waitForWritableChannel()) { return false; }
      channel.send(fromBase64(value));
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
        case "data_channel_state":
            if let value = body["value"] as? String {
                dataChannelState = value
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
