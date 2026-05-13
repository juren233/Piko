import Foundation

struct NativeFriendApiClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func search(query: String, token: String) async -> Result<[NativeFriendSearchResult], NativeAccountError> {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return await request(path: "/v1/users/search?q=\(encoded)", method: "GET", body: nil, token: token) { json in
            guard let results = json["results"] as? [[String: Any]] else {
                throw NativeAccountError.networkUnavailable
            }
            return try results.map(parseSearchResult)
        }
    }

    func friends(token: String) async -> Result<[NativeFriendUser], NativeAccountError> {
        await request(path: "/v1/friends", method: "GET", body: nil, token: token) { json in
            guard let friends = json["friends"] as? [[String: Any]] else {
                throw NativeAccountError.networkUnavailable
            }
            return try friends.map(parseFriendUser)
        }
    }

    func friendDevices(userId: String, token: String) async -> Result<[NativeFriendDevice], NativeAccountError> {
        let encoded = userId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? userId
        return await request(path: "/v1/friends/\(encoded)/devices", method: "GET", body: nil, token: token) { json in
            guard let devices = json["devices"] as? [[String: Any]] else {
                throw NativeAccountError.networkUnavailable
            }
            return try devices.map { try parseFriendDevice($0, ownerUserId: userId) }
        }
    }

    func requests(direction: NativeFriendRequest.Direction, token: String) async -> Result<[NativeFriendRequest], NativeAccountError> {
        let value = direction == .incoming ? "incoming" : "outgoing"
        return await request(path: "/v1/friends/requests?direction=\(value)", method: "GET", body: nil, token: token) { json in
            guard let requests = json["requests"] as? [[String: Any]] else {
                throw NativeAccountError.networkUnavailable
            }
            return try requests.map { try parseRequest($0, direction: direction) }
        }
    }

    func sendRequest(to userId: String, token: String) async -> Result<NativeFriendRequest, NativeAccountError> {
        await request(
            path: "/v1/friends/requests",
            method: "POST",
            body: ["receiver_user_id": userId],
            token: token
        ) { json in
            guard let request = json["request"] as? [String: Any] else {
                throw NativeAccountError.networkUnavailable
            }
            return try parseRequest(request, direction: .outgoing)
        }
    }

    func accept(_ requestId: String, token: String) async -> Result<NativeFriendRequest, NativeAccountError> {
        await mutateRequest(requestId, action: "accept", direction: .incoming, token: token)
    }

    func reject(_ requestId: String, token: String) async -> Result<NativeFriendRequest, NativeAccountError> {
        await mutateRequest(requestId, action: "reject", direction: .incoming, token: token)
    }

    func cancel(_ requestId: String, token: String) async -> Result<NativeFriendRequest, NativeAccountError> {
        await request(path: "/v1/friends/requests/\(requestId)", method: "DELETE", body: nil, token: token) { json in
            guard let request = json["request"] as? [String: Any] else {
                throw NativeAccountError.networkUnavailable
            }
            return try parseRequest(request, direction: .outgoing)
        }
    }

    func removeFriend(_ userId: String, token: String) async -> Result<Void, NativeAccountError> {
        await request(path: "/v1/friends/\(userId)", method: "DELETE", body: nil, token: token) { _ in () }
    }

    func heartbeat(token: String) async -> Result<Void, NativeAccountError> {
        await request(path: "/v1/presence/heartbeat", method: "POST", body: nil, token: token) { _ in () }
    }

    private func mutateRequest(
        _ requestId: String,
        action: String,
        direction: NativeFriendRequest.Direction,
        token: String
    ) async -> Result<NativeFriendRequest, NativeAccountError> {
        await request(path: "/v1/friends/requests/\(requestId)/\(action)", method: "POST", body: nil, token: token) { json in
            guard let request = json["request"] as? [String: Any] else {
                throw NativeAccountError.networkUnavailable
            }
            return try parseRequest(request, direction: direction)
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
            if http.statusCode == 204 {
                return .success(try parse([:]))
            }
            guard (200...299).contains(http.statusCode) else {
                return .failure(.from(statusCode: http.statusCode, body: data))
            }
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return .failure(.networkUnavailable)
            }
            return .success(try parse(json))
        } catch let error as NativeAccountError {
            return .failure(error)
        } catch {
            return .failure(.networkUnavailable)
        }
    }
}

private func parseSearchResult(_ json: [String: Any]) throws -> NativeFriendSearchResult {
    guard let relationshipRaw = json["relationship"] as? String else {
        throw NativeAccountError.networkUnavailable
    }
    return NativeFriendSearchResult(
        user: try parseSearchUser(json),
        relationship: NativeFriendRelationship(rawValue: relationshipRaw) ?? .none
    )
}

private func parseSearchUser(_ json: [String: Any]) throws -> NativeFriendUser {
    guard
        let id = json["id"] as? String,
        let username = json["username"] as? String
    else {
        throw NativeAccountError.networkUnavailable
    }
    return NativeFriendUser(
        userId: id,
        username: username,
        nickname: (json["nickname"] as? String)?.nilIfBlank,
        online: false,
        lastSeenAt: nil,
        since: 0
    )
}

private func parseFriendUser(_ json: [String: Any]) throws -> NativeFriendUser {
    guard
        let id = json["user_id"] as? String,
        let username = json["username"] as? String
    else {
        throw NativeAccountError.networkUnavailable
    }
    return NativeFriendUser(
        userId: id,
        username: username,
        nickname: (json["nickname"] as? String)?.nilIfBlank,
        online: (json["online"] as? Bool) ?? false,
        lastSeenAt: json["last_seen_at"] as? Int,
        since: (json["since"] as? Int) ?? 0
    )
}

private func parseFriendDevice(_ json: [String: Any], ownerUserId: String) throws -> NativeFriendDevice {
    guard
        let deviceId = json["device_id"] as? String,
        let platform = json["platform"] as? String,
        let deviceName = json["device_name"] as? String,
        let ed25519PubB64 = json["ed25519_pub_b64"] as? String,
        let x25519PubB64 = json["x25519_pub_b64"] as? String
    else {
        throw NativeAccountError.networkUnavailable
    }
    return NativeFriendDevice(
        ownerUserId: ownerUserId,
        deviceId: deviceId,
        platform: platform,
        deviceName: deviceName,
        ed25519PubB64: ed25519PubB64,
        x25519PubB64: x25519PubB64,
        appVersion: (json["app_version"] as? String)?.nilIfBlank,
        lastSeenAt: json["last_seen_at"] as? Int,
        online: (json["online"] as? Bool) ?? false
    )
}

private func parseRequest(_ json: [String: Any], direction: NativeFriendRequest.Direction) throws -> NativeFriendRequest {
    guard
        let id = json["id"] as? String,
        let statusRaw = json["status"] as? String
    else {
        throw NativeAccountError.networkUnavailable
    }
    let other = json["other_user"] as? [String: Any]
    let otherUser = NativeFriendUser(
        userId: (other?["id"] as? String) ?? "",
        username: (other?["username"] as? String) ?? "",
        nickname: (other?["nickname"] as? String)?.nilIfBlank,
        online: false,
        lastSeenAt: nil,
        since: (json["created_at"] as? Int) ?? 0
    )
    return NativeFriendRequest(
        id: id,
        direction: direction,
        otherUser: otherUser,
        status: NativeFriendRequestStatus(rawValue: statusRaw) ?? .pending,
        createdAt: (json["created_at"] as? Int) ?? 0
    )
}
