import Foundation

final class NativeSignalingClient {
    private let baseURL: URL
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var activeDeviceId: String?
    var onMessage: (([String: Any]) -> Void)?

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func connect(token: String, deviceId: String) {
        if task != nil, activeDeviceId == deviceId {
            return
        }
        close()
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
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
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
            }
        }
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        if object["type"] as? String == "ping" {
            send(["type": "pong"])
        } else {
            onMessage?(object)
        }
    }

    private func signalingURL(deviceId: String) -> URL? {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = baseURL.scheme == "http" ? "ws" : "wss"
        components?.path = "/v1/signaling/ws"
        components?.queryItems = [URLQueryItem(name: "device_id", value: deviceId)]
        return components?.url
    }
}
