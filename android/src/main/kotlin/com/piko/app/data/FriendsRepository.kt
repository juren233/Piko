package com.piko.app.data

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.FriendDevice
import com.piko.app.domain.FriendRequestDirection
import com.piko.app.domain.FriendsState
import com.piko.app.transport.FriendApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FriendsRepository(
    private val api: FriendApi,
    private val tokenStore: TokenStorage,
) {
    private val _state = MutableStateFlow(FriendsState.Empty)
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    suspend fun refreshAll() {
        val token = tokenStore.load() ?: run {
            _state.value = FriendsState.Empty
            return
        }
        val friends = api.friends(token)
        val incoming = api.requests(token, FriendRequestDirection.Incoming)
        val outgoing = api.requests(token, FriendRequestDirection.Outgoing)
        if (friends is AccountResult.Ok && incoming is AccountResult.Ok && outgoing is AccountResult.Ok) {
            val friendDevices = mutableMapOf<String, List<FriendDevice>>()
            var deviceError: AccountError? = null
            friends.value.forEach { friend ->
                when (val devices = api.friendDevices(token, friend.userId)) {
                    is AccountResult.Ok -> friendDevices[friend.userId] = devices.value
                    is AccountResult.Err -> deviceError = deviceError ?: devices.error
                }
            }
            _state.value = _state.value.copy(
                friends = friends.value,
                friendDevices = friendDevices,
                incoming = incoming.value,
                outgoing = outgoing.value,
                error = deviceError,
            )
            return
        }
        _state.value = _state.value.copy(error = firstError(friends, incoming, outgoing))
    }

    suspend fun search(query: String) {
        val token = tokenStore.load() ?: return
        if (query.trim().length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false, error = null)
            return
        }
        _state.value = _state.value.copy(isSearching = true)
        when (val res = api.search(token, query.trim())) {
            is AccountResult.Ok -> _state.value = _state.value.copy(
                searchResults = res.value,
                isSearching = false,
                error = null,
            )
            is AccountResult.Err -> _state.value = _state.value.copy(isSearching = false, error = res.error)
        }
    }

    suspend fun sendRequest(userId: String) {
        val token = tokenStore.load() ?: return
        when (val res = api.sendRequest(token, userId)) {
            is AccountResult.Ok -> refreshAll()
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    suspend fun accept(requestId: String) {
        val token = tokenStore.load() ?: return
        when (val res = api.accept(token, requestId)) {
            is AccountResult.Ok -> {
                _state.value = _state.value.withAccepted(requestId)
                refreshAll()
            }
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    suspend fun reject(requestId: String) {
        val token = tokenStore.load() ?: return
        when (val res = api.reject(token, requestId)) {
            is AccountResult.Ok -> _state.value = _state.value.withRejected(requestId)
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    suspend fun cancel(requestId: String) {
        val token = tokenStore.load() ?: return
        when (val res = api.cancel(token, requestId)) {
            is AccountResult.Ok -> _state.value = _state.value.withCanceled(requestId)
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    suspend fun removeFriend(userId: String) {
        val token = tokenStore.load() ?: return
        when (val res = api.removeFriend(token, userId)) {
            is AccountResult.Ok -> _state.value = _state.value.withoutFriend(userId)
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    suspend fun heartbeat() {
        val token = tokenStore.load() ?: return
        when (val res = api.heartbeat(token)) {
            is AccountResult.Ok -> Unit
            is AccountResult.Err -> _state.value = _state.value.copy(error = res.error)
        }
    }

    fun clear() {
        _state.value = FriendsState.Empty
    }
}

private fun firstError(vararg results: AccountResult<*>): AccountError? {
    return results.firstNotNullOfOrNull { result ->
        (result as? AccountResult.Err)?.error
    }
}
