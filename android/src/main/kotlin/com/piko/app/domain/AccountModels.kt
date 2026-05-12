package com.piko.app.domain

data class User(
    val id: String,
    val email: String,
    val username: String,
    val nickname: String?,
)

data class AuthSuccess(
    val token: String,
    val user: User,
)

sealed class AccountError {
    object Network : AccountError()
    object InvalidCredentials : AccountError()
    object EmailTaken : AccountError()
    object UsernameTaken : AccountError()
    object InvalidEmail : AccountError()
    object InvalidPassword : AccountError()
    object InvalidUsername : AccountError()
    object InvalidNickname : AccountError()
    object SessionExpired : AccountError()
    data class Server(val code: String, val message: String) : AccountError()
}

sealed class AccountResult<out T> {
    data class Ok<out T>(val value: T) : AccountResult<T>()
    data class Err(val error: AccountError) : AccountResult<Nothing>()
}
