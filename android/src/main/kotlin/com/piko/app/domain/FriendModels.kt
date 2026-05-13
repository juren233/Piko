package com.piko.app.domain

data class FriendUser(
    val userId: String,
    val username: String,
    val nickname: String?,
    val online: Boolean,
    val lastSeenAt: Long?,
    val since: Long,
) {
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: "@$username"

    val presenceLabel: String
        get() = if (online) "在线" else "离线"
}

data class FriendRequest(
    val id: String,
    val direction: FriendRequestDirection,
    val otherUser: FriendUser,
    val status: FriendRequestStatus,
    val createdAt: Long,
)

enum class FriendRequestDirection {
    Incoming,
    Outgoing,
}

enum class FriendRequestStatus {
    Pending,
    Accepted,
    Rejected,
    Canceled,
}

enum class FriendRelationship {
    Self,
    None,
    PendingOut,
    PendingIn,
    Friend,
}

data class FriendSearchResult(
    val user: FriendUser,
    val relationship: FriendRelationship,
)

data class FriendsState(
    val friends: List<FriendUser>,
    val incoming: List<FriendRequest>,
    val outgoing: List<FriendRequest>,
    val searchResults: List<FriendSearchResult>,
    val isSearching: Boolean,
    val error: AccountError?,
) {
    val pendingIncomingCount: Int
        get() = incoming.count { it.status == FriendRequestStatus.Pending }

    fun withAccepted(requestId: String): FriendsState {
        val accepted = incoming.firstOrNull { it.id == requestId } ?: return this
        val nextFriend = accepted.otherUser
        return copy(
            friends = (friends + nextFriend).distinctBy { it.userId },
            incoming = incoming.map {
                if (it.id == requestId) it.copy(status = FriendRequestStatus.Accepted) else it
            },
        )
    }

    fun withRejected(requestId: String): FriendsState {
        return copy(
            incoming = incoming.map {
                if (it.id == requestId) it.copy(status = FriendRequestStatus.Rejected) else it
            },
        )
    }

    fun withCanceled(requestId: String): FriendsState {
        return copy(
            outgoing = outgoing.map {
                if (it.id == requestId) it.copy(status = FriendRequestStatus.Canceled) else it
            },
        )
    }

    fun withoutFriend(userId: String): FriendsState {
        return copy(friends = friends.filterNot { it.userId == userId })
    }

    companion object {
        val Empty = FriendsState(
            friends = emptyList(),
            incoming = emptyList(),
            outgoing = emptyList(),
            searchResults = emptyList(),
            isSearching = false,
            error = null,
        )
    }
}
