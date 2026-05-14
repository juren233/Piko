import Foundation

struct NativeIceServerConfig: Equatable {
    let urls: String

    static let defaultP2P: [NativeIceServerConfig] = [
        NativeIceServerConfig(urls: "stun:stun.cloudflare.com:3478"),
        NativeIceServerConfig(urls: "stun:stun.cloudflare.com:53")
    ]

    static func parse(_ rawServers: [[String: Any]]?) -> [NativeIceServerConfig] {
        let servers = rawServers?.compactMap { server in
            (server["urls"] as? String).flatMap { urls in
                urls.isEmpty ? nil : NativeIceServerConfig(urls: urls)
            }
        } ?? []
        return servers.isEmpty ? defaultP2P : servers
    }
}

struct NativeTransferSessionConfig: Equatable {
    let sessionId: String
    let iceServers: [NativeIceServerConfig]
    let expiresAt: Int
}

struct NativeTransferSessionApiClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func createSession(
        receiverUserId: String,
        receiverDeviceId: String,
        transferId: String,
        manifestHashB64: String,
        senderX25519EphPubB64: String,
        senderDeviceId: String,
        senderInviteSignatureB64: String,
        token: String
    ) async -> Result<NativeTransferSessionConfig, NativeAccountError> {
        await request(
            path: "/v1/transfers/sessions",
            method: "POST",
            body: [
                "receiver_user_id": receiverUserId,
                "receiver_device_id": receiverDeviceId,
                "transfer_id": transferId,
                "manifest_hash_b64": manifestHashB64,
                "sender_x25519_eph_pub_b64": senderX25519EphPubB64,
                "sender_device_id": senderDeviceId,
                "sender_invite_signature_b64": senderInviteSignatureB64
            ],
            token: token
        ) { json in
            guard let sessionId = json["session_id"] as? String,
                  let expiresAt = json["expires_at"] as? Int else {
                throw NativeAccountError.networkUnavailable
            }
            return NativeTransferSessionConfig(
                sessionId: sessionId,
                iceServers: NativeIceServerConfig.parse(json["ice_servers"] as? [[String: Any]]),
                expiresAt: expiresAt
            )
        }
    }

    func finishSession(token: String, sessionId: String) async -> Result<Void, NativeAccountError> {
        await request(
            path: "/v1/transfers/sessions/\(sessionId)/finish",
            method: "POST",
            body: nil,
            token: token
        ) { _ in
            ()
        }
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
