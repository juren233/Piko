import Foundation

struct NativeFriendUser: Identifiable, Equatable {
    let userId: String
    let username: String
    let nickname: String?
    let online: Bool
    let lastSeenAt: Int?
    let since: Int

    var id: String { userId }

    var displayName: String {
        nickname?.nilIfBlank ?? "@\(username)"
    }

    var presence: NativePresenceStatus {
        NativePresenceStatus(online: online, lastSeenAt: lastSeenAt)
    }
}

struct NativeFriendDevice: Identifiable, Equatable {
    let ownerUserId: String
    let deviceId: String
    let platform: String
    let deviceName: String
    let ed25519PubB64: String
    let x25519PubB64: String
    let appVersion: String?
    let lastSeenAt: Int?
    let online: Bool

    var id: String { deviceId }

    var subtitle: String {
        "\(platformLabel) · \(online ? "在线" : "离线")"
    }

    private var platformLabel: String {
        switch platform.lowercased() {
        case "ios": return "iOS"
        case "android": return "Android"
        case "macos": return "macOS"
        case "windows": return "Windows"
        default: return platform
        }
    }
}

struct NativeFriendRequest: Identifiable, Equatable {
    enum Direction {
        case incoming
        case outgoing
    }

    let id: String
    let direction: Direction
    let otherUser: NativeFriendUser
    let status: NativeFriendRequestStatus
    let createdAt: Int
}

struct NativeFriendSearchResult: Identifiable, Equatable {
    let user: NativeFriendUser
    let relationship: NativeFriendRelationship

    var id: String { user.userId }
}

enum NativeFriendRelationship: String {
    case selfUser = "self"
    case none
    case pendingOut = "pending-out"
    case pendingIn = "pending-in"
    case friend

    var label: String {
        switch self {
        case .selfUser: return "自己"
        case .none: return "未添加"
        case .pendingOut: return "已申请"
        case .pendingIn: return "待你处理"
        case .friend: return "已是好友"
        }
    }
}

enum NativeFriendRequestStatus: String {
    case pending
    case accepted
    case rejected
    case canceled

    var label: String {
        switch self {
        case .pending: return "待处理"
        case .accepted: return "已同意"
        case .rejected: return "已拒绝"
        case .canceled: return "已撤回"
        }
    }
}

struct NativePresenceStatus: Equatable {
    let online: Bool
    let lastSeenAt: Int?

    var subtitleLabel: String {
        online ? "在线" : "离线"
    }
}
