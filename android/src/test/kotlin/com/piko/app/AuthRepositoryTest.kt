package com.piko.app

import com.piko.app.data.AuthRepository
import com.piko.app.data.InMemoryTokenStorage
import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.AuthState
import com.piko.app.domain.AuthSuccess
import com.piko.app.domain.User
import com.piko.app.transport.AccountApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAccountApi(
    var registerResult: AccountResult<AuthSuccess> = AccountResult.Err(AccountError.Network),
    var loginResult: AccountResult<AuthSuccess> = AccountResult.Err(AccountError.Network),
    var logoutResult: AccountResult<Unit> = AccountResult.Ok(Unit),
    var meResult: AccountResult<User> = AccountResult.Err(AccountError.SessionExpired),
) : AccountApi {
    var lastLogoutToken: String? = null
    override suspend fun register(email: String, password: String, username: String, nickname: String?) = registerResult
    override suspend fun login(email: String, password: String) = loginResult
    override suspend fun logout(token: String): AccountResult<Unit> {
        lastLogoutToken = token
        return logoutResult
    }
    override suspend fun me(token: String) = meResult
}

class AuthRepositoryTest {

    private fun authSuccess(userId: String = "u-1", token: String = "t-" + "x".repeat(41)) = AuthSuccess(
        token = token,
        user = User(id = userId, email = "a@b.com", username = "alice", nickname = "Alice"),
    )

    @Test
    fun registerOkStoresTokenAndMovesToAuthenticated() = runBlocking {
        val api = FakeAccountApi(registerResult = AccountResult.Ok(authSuccess()))
        val storage = InMemoryTokenStorage()
        val repo = AuthRepository(api, storage)

        assertEquals(AuthState.Unauthenticated, repo.state.value)
        val result = repo.register("a@b.com", "hunter2hunter2", "alice", "Alice")

        assertTrue(result is AccountResult.Ok)
        assertEquals("alice", result.value.username)
        val authState = repo.state.value
        assertTrue(authState is AuthState.Authenticated, "Expected Authenticated, got $authState")
        assertEquals("alice", authState.user.username)
        assertEquals(authSuccess().token, storage.load())
    }

    @Test
    fun registerErrLeavesStateUnauthenticatedWithoutTouchingStorage() = runBlocking {
        val api = FakeAccountApi(registerResult = AccountResult.Err(AccountError.EmailTaken))
        val storage = InMemoryTokenStorage()
        val repo = AuthRepository(api, storage)

        val result = repo.register("a@b.com", "hunter2hunter2", "alice", null)

        assertTrue(result is AccountResult.Err)
        assertEquals(AccountError.EmailTaken, result.error)
        assertEquals(AuthState.Unauthenticated, repo.state.value)
        assertNull(storage.load())
    }

    @Test
    fun loginOkStoresTokenAndMovesToAuthenticated() = runBlocking {
        val success = authSuccess(token = "fresh-" + "y".repeat(37))
        val api = FakeAccountApi(loginResult = AccountResult.Ok(success))
        val storage = InMemoryTokenStorage().apply { save("stale-token") }
        val repo = AuthRepository(api, storage)

        val result = repo.login("a@b.com", "hunter2hunter2")

        assertTrue(result is AccountResult.Ok)
        assertEquals(success.token, storage.load())
        assertTrue(repo.state.value is AuthState.Authenticated)
    }

    @Test
    fun loginErrLeavesStateUnauthenticated() = runBlocking {
        val api = FakeAccountApi(loginResult = AccountResult.Err(AccountError.InvalidCredentials))
        val storage = InMemoryTokenStorage()
        val repo = AuthRepository(api, storage)

        val result = repo.login("a@b.com", "wrong")
        assertTrue(result is AccountResult.Err)
        assertEquals(AccountError.InvalidCredentials, result.error)
        assertEquals(AuthState.Unauthenticated, repo.state.value)
        assertNull(storage.load())
    }

    @Test
    fun logoutClearsTokenAndStateRegardlessOfApi() = runBlocking {
        val api = FakeAccountApi(logoutResult = AccountResult.Err(AccountError.Network))
        val storage = InMemoryTokenStorage().apply { save("known-token-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX") }
        val repo = AuthRepository(api, storage)

        repo.logout()

        assertEquals(AuthState.Unauthenticated, repo.state.value)
        assertNull(storage.load())
        assertEquals("known-token-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", api.lastLogoutToken)
    }

    @Test
    fun bootstrapWithoutTokenLeavesStateUnauthenticated() = runBlocking {
        val api = FakeAccountApi()
        val storage = InMemoryTokenStorage()
        val repo = AuthRepository(api, storage)

        repo.bootstrap()
        assertEquals(AuthState.Unauthenticated, repo.state.value)
    }

    @Test
    fun bootstrapWithValidTokenMovesToAuthenticated() = runBlocking {
        val user = User(id = "u1", email = "a@b.com", username = "alice", nickname = null)
        val api = FakeAccountApi(meResult = AccountResult.Ok(user))
        val storage = InMemoryTokenStorage().apply { save("tok-" + "x".repeat(39)) }
        val repo = AuthRepository(api, storage)

        repo.bootstrap()
        val st = repo.state.value
        assertTrue(st is AuthState.Authenticated)
        assertEquals("alice", st.user.username)
        assertEquals("tok-" + "x".repeat(39), storage.load())
    }

    @Test
    fun bootstrapWithExpiredTokenClearsItAndStaysUnauthenticated() = runBlocking {
        val api = FakeAccountApi(meResult = AccountResult.Err(AccountError.SessionExpired))
        val storage = InMemoryTokenStorage().apply { save("expired-" + "x".repeat(35)) }
        val repo = AuthRepository(api, storage)

        repo.bootstrap()
        assertEquals(AuthState.Unauthenticated, repo.state.value)
        assertNull(storage.load())
    }
}
