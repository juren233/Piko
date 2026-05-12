package com.piko.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface TokenStorage {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

class AuthTokenStore private constructor(
    private val prefs: SharedPreferences,
) : TokenStorage {

    override fun load(): String? = prefs.getString(KEY_TOKEN, null)

    override fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREFS_NAME = "piko_auth.enc"
        private const val KEY_TOKEN = "auth_token"

        fun fromContext(context: Context): AuthTokenStore {
            val applicationContext = context.applicationContext
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return AuthTokenStore(prefs)
        }
    }
}

/**
 * 测试用：纯内存 token store。
 */
class InMemoryTokenStorage : TokenStorage {
    private var token: String? = null
    override fun load(): String? = token
    override fun save(token: String) { this.token = token }
    override fun clear() { this.token = null }
}
