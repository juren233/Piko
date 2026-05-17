package com.piko.app.app

import com.piko.app.domain.AccountError
import com.piko.app.domain.AuthState

data class AuthSection(
    val state: AuthState,
    val lastError: AccountError?,
    val onLogin: (email: String, password: String) -> Unit,
    val onRegister: (email: String, password: String, username: String, nickname: String?) -> Unit,
    val onSignOut: () -> Unit,
    val onErrorConsumed: () -> Unit,
) {
    companion object {
        val Empty = AuthSection(
            state = AuthState.Unauthenticated,
            lastError = null,
            onLogin = { _, _ -> },
            onRegister = { _, _, _, _ -> },
            onSignOut = {},
            onErrorConsumed = {},
        )
    }
}

data class FriendsEntry(
    val enabled: Boolean,
    val friendCount: Int,
    val pendingCount: Int,
    val onClick: () -> Unit,
) {
    companion object {
        val Empty = FriendsEntry(
            enabled = false,
            friendCount = 0,
            pendingCount = 0,
            onClick = {},
        )
    }
}
