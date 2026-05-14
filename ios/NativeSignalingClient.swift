import Foundation

final class NativeSignalingClient {
    private let baseURL: URL
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var activeToken: String?
    private var activeDeviceId: String?
    private var reconnectAttempts = 0
    private var reconnectWorkItem: DispatchWorkItem?
    private var closedByUser = false
    var onMessage: (([String: Any]) -> Void)?

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func connect(token: String, deviceId: String) {
        if task != nil, activeDeviceId == deviceId, activeToken == token {
            return
        }
        closedByUser = false
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        reconnectAttempts = 0
        connectInternal(token: token, deviceId: deviceId)
    }

    private func connectInternal(token: String, deviceId: String) {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        activeToken = token
        activeDeviceId = deviceId
        guard let url = signalingURL(deviceId: deviceId) else {
            return
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let nextTask = session.webSocketTask(with: request)
        task = nextTask
        nextTask.resume()
        send(["type": "hello"])
        receiveLoop()
    }

    func send(_ message: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        task?.send(.string(text)) { _ in }
    }

    func close() {
        closedByUser = true
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        activeToken = nil
        activeDeviceId = nil
    }

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self else {
                return
            }
            switch result {
            case .success(.string(let text)):
                self.handle(text)
                self.receiveLoop()
            case .success:
                self.receiveLoop()
            case .failure:
                self.task = nil
                self.scheduleReconnect()
            }
        }
    }

    private func scheduleReconnect() {
        if closedByUser {
            return
        }
        guard let token = activeToken, let deviceId = activeDeviceId else {
            return
        }
        reconnectWorkItem?.cancel()
        let attempt = min(reconnectAttempts, 6)
        reconnectAttempts += 1
        let delaySeconds = min(Double(500 << attempt) / 1000.0, 30.0)
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, !self.closedByUser else {
                return
            }
            self.connectInternal(token: token, deviceId: deviceId)
        }
        reconnectWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delaySeconds, execute: workItem)
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        if object["type"] as? String == "ping" {
            send(["type": "pong"])
            return
        }
        reconnectAttempts = 0
        onMessage?(object)
    }

    private func signalingURL(deviceId: String) -> URL? {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = baseURL.scheme == "http" ? "ws" : "wss"
        components?.path = "/v1/signaling/ws"
        components?.queryItems = [URLQueryItem(name: "device_id", value: deviceId)]
        return components?.url
    }
}
