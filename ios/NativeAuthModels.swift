import Foundation

struct NativeUser: Equatable {
    let id: String
    let email: String
    let username: String
    let nickname: String?
}

struct NativeAuthSuccess: Equatable {
    let token: String
    let user: NativeUser
}

enum NativeAuthState: Equatable {
    case unauthenticated
    case loading
    case authenticated(NativeUser)
}

enum NativeAccountError: Error, Equatable {
    case networkUnavailable
    case invalidCredentials
    case emailTaken
    case usernameTaken
    case invalidEmail
    case invalidPassword
    case invalidUsername
    case invalidNickname
    case sessionExpired
    case server(code: String, message: String)

    var displayMessage: String {
        switch self {
        case .networkUnavailable:
            return NativeAuthLabels.networkUnavailable
        case .invalidCredentials:
            return NativeAuthLabels.invalidCredentials
        case .emailTaken:
            return NativeAuthLabels.emailTaken
        case .usernameTaken:
            return NativeAuthLabels.usernameTaken
        case .invalidEmail:
            return NativeAuthLabels.invalidEmail
        case .invalidPassword:
            return NativeAuthLabels.weakPassword
        case .invalidUsername:
            return NativeAuthLabels.invalidUsername
        case .invalidNickname:
            return NativeAuthLabels.invalidNickname
        case .sessionExpired:
            return NativeAuthLabels.networkUnavailable
        case .server(_, let message):
            return message.nilIfBlank ?? NativeAuthLabels.networkUnavailable
        }
    }

    static func from(statusCode: Int, body: Data) -> NativeAccountError {
        guard
            let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
            let error = json["error"] as? [String: Any],
            let code = error["code"] as? String
        else {
            return .server(code: "HTTP_\(statusCode)", message: "HTTP \(statusCode)")
        }

        switch code {
        case "EMAIL_TAKEN":
            return .emailTaken
        case "USERNAME_TAKEN":
            return .usernameTaken
        case "INVALID_EMAIL":
            return .invalidEmail
        case "INVALID_PASSWORD":
            return .invalidPassword
        case "INVALID_USERNAME":
            return .invalidUsername
        case "INVALID_NICKNAME":
            return .invalidNickname
        case "INVALID_CREDENTIALS":
            return .invalidCredentials
        case "SESSION_EXPIRED":
            return .sessionExpired
        default:
            return .server(code: code, message: (error["message"] as? String) ?? "")
        }
    }
}
