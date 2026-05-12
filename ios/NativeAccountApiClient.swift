import Foundation

struct NativeAccountApiClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = NativeAppConfig.apiBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func register(
        email: String,
        password: String,
        username: String,
        nickname: String?
    ) async -> Result<NativeAuthSuccess, NativeAccountError> {
        var body: [String: Any] = [
            "email": email,
            "password": password,
            "username": username
        ]
        if let nickname {
            body["nickname"] = nickname
        }
        return await request(path: "/v1/auth/register", method: "POST", body: body, token: nil, parse: parseAuthSuccess)
    }

    func login(email: String, password: String) async -> Result<NativeAuthSuccess, NativeAccountError> {
        await request(
            path: "/v1/auth/login",
            method: "POST",
            body: ["email": email, "password": password],
            token: nil,
            parse: parseAuthSuccess
        )
    }

    func logout(token: String) async -> Result<Void, NativeAccountError> {
        await request(path: "/v1/auth/logout", method: "POST", body: nil, token: token) { _ in () }
    }

    func me(token: String) async -> Result<NativeUser, NativeAccountError> {
        await request(path: "/v1/users/me", method: "GET", body: nil, token: token) { json in
            guard
                let userJson = json["user"] as? [String: Any],
                let user = parseUser(userJson)
            else {
                throw NativeAccountError.networkUnavailable
            }
            return user
        }
    }

    private func request<T>(
        path: String,
        method: String,
        body: [String: Any]?,
        token: String?,
        parse: @escaping ([String: Any]) throws -> T
    ) async -> Result<T, NativeAccountError> {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            return .failure(.networkUnavailable)
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
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
                do {
                    return .success(try parse([:]))
                } catch let error as NativeAccountError {
                    return .failure(error)
                } catch {
                    return .failure(.networkUnavailable)
                }
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

    private func parseAuthSuccess(_ json: [String: Any]) throws -> NativeAuthSuccess {
        guard
            let token = json["token"] as? String,
            let userJson = json["user"] as? [String: Any],
            let user = parseUser(userJson)
        else {
            throw NativeAccountError.networkUnavailable
        }
        return NativeAuthSuccess(token: token, user: user)
    }

    private func parseUser(_ json: [String: Any]) -> NativeUser? {
        guard
            let id = json["id"] as? String,
            let email = json["email"] as? String,
            let username = json["username"] as? String
        else {
            return nil
        }
        return NativeUser(
            id: id,
            email: email,
            username: username,
            nickname: (json["nickname"] as? String)?.nilIfBlank
        )
    }
}
