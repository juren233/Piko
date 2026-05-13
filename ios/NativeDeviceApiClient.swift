import Foundation

struct NativeDeviceApiClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func registerDevice(
        identity: NativeDeviceIdentity,
        deviceName: String,
        appVersion: String?,
        token: String
    ) async -> Result<Void, NativeAccountError> {
        var body: [String: Any] = [
            "device_id": identity.deviceId,
            "platform": "ios",
            "device_name": deviceName,
            "ed25519_pub_b64": identity.ed25519PublicB64,
            "x25519_pub_b64": identity.x25519PublicB64
        ]
        if let appVersion {
            body["app_version"] = appVersion
        }
        return await request(path: "/v1/devices/keys", method: "POST", body: body, token: token) { _ in () }
    }

    private func request<T>(
        path: String,
        method: String,
        body: [String: Any]?,
        token: String,
        parse: @escaping ([String: Any]) throws -> T
    ) async -> Result<T, NativeAccountError> {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            return .failure(.networkUnavailable)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failure(.networkUnavailable)
            }
            if http.statusCode == 204 {
                return .success(try parse([:]))
            }
            guard (200...299).contains(http.statusCode) else {
                return .failure(.from(statusCode: http.statusCode, body: data))
            }
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return .success(try parse(json ?? [:]))
        } catch let error as NativeAccountError {
            return .failure(error)
        } catch {
            return .failure(.networkUnavailable)
        }
    }
}
