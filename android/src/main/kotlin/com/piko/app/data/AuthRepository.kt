package com.piko.app.data

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.AuthState
import com.piko.app.domain.User
import com.piko.app.transport.AccountApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepository(
    private val api: AccountApi,
    private val tokenStore: TokenStorage,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * 启动时调用。若磁盘有 token，调用 /me 拉用户；token 失效则清掉。
     */
    suspend fun bootstrap() {
        val token = tokenStore.load() ?: return
        _state.value = AuthState.Loading
        when (val res = api.me(token)) {
            is AccountResult.Ok -> _state.value = AuthState.Authenticated(res.value)
            is AccountResult.Err -> {
                if (res.error is AccountError.SessionExpired) tokenStore.clear()
                _state.value = AuthState.Unauthenticated
            }
        }
    }

    suspend fun register(
        email: String,
        password: String,
        username: String,
        nickname: String?,
    ): AccountResult<User> {
        _state.value = AuthState.Loading
        return when (val res = api.register(email, password, username, nickname)) {
            is AccountResult.Ok -> {
                tokenStore.save(res.value.token)
                _state.value = AuthState.Authenticated(res.value.user)
                AccountResult.Ok(res.value.user)
            }
            is AccountResult.Err -> {
                _state.value = AuthState.Unauthenticated
                AccountResult.Err(res.error)
            }
        }
    }

    suspend fun login(email: String, password: String): AccountResult<User> {
        _state.value = AuthState.Loading
        return when (val res = api.login(email, password)) {
            is AccountResult.Ok -> {
                tokenStore.save(res.value.token)
                _state.value = AuthState.Authenticated(res.value.user)
                AccountResult.Ok(res.value.user)
            }
            is AccountResult.Err -> {
                _state.value = AuthState.Unauthenticated
                AccountResult.Err(res.error)
            }
        }
    }

    suspend fun logout() {
        val token = tokenStore.load()
        if (token != null) {
            // 即使 API 调用失败也要本地清掉
            runCatching { api.logout(token) }
        }
        tokenStore.clear()
        _state.value = AuthState.Unauthenticated
    }
}
