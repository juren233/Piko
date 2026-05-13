package com.piko.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.piko.app.domain.TransferProtocolV3
import java.security.SecureRandom
import java.util.Base64

data class DeviceIdentity(
    val deviceId: String,
    val ed25519PublicB64: String,
    val x25519PublicB64: String,
    val ed25519PrivatePkcs8B64: String,
    val x25519PrivatePkcs8B64: String,
)

class DeviceIdentityStore private constructor(
    private val prefs: SharedPreferences,
) {
    fun loadOrCreate(): DeviceIdentity {
        val existing = load()
        if (existing != null) return existing

        val ed25519 = TransferProtocolV3.generateSigningKeyPair()
        val x25519 = TransferProtocolV3.generateAgreementKeyPair()
        val identity = DeviceIdentity(
            deviceId = newUlidLikeId(),
            ed25519PublicB64 = ed25519.public.encoded.takeLast(32).toByteArray().base64(),
            x25519PublicB64 = x25519.public.encoded.takeLast(32).toByteArray().base64(),
            ed25519PrivatePkcs8B64 = ed25519.private.encoded.base64(),
            x25519PrivatePkcs8B64 = x25519.private.encoded.base64(),
        )
        prefs.edit()
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_ED25519_PUBLIC, identity.ed25519PublicB64)
            .putString(KEY_X25519_PUBLIC, identity.x25519PublicB64)
            .putString(KEY_ED25519_PRIVATE, identity.ed25519PrivatePkcs8B64)
            .putString(KEY_X25519_PRIVATE, identity.x25519PrivatePkcs8B64)
            .apply()
        return identity
    }

    private fun load(): DeviceIdentity? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val ed25519Public = prefs.getString(KEY_ED25519_PUBLIC, null) ?: return null
        val x25519Public = prefs.getString(KEY_X25519_PUBLIC, null) ?: return null
        val ed25519Private = prefs.getString(KEY_ED25519_PRIVATE, null) ?: return null
        val x25519Private = prefs.getString(KEY_X25519_PRIVATE, null) ?: return null
        return DeviceIdentity(deviceId, ed25519Public, x25519Public, ed25519Private, x25519Private)
    }

    companion object {
        private const val PREFS_NAME = "piko_device_identity.enc"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ED25519_PUBLIC = "ed25519_public"
        private const val KEY_X25519_PUBLIC = "x25519_public"
        private const val KEY_ED25519_PRIVATE = "ed25519_private_pkcs8"
        private const val KEY_X25519_PRIVATE = "x25519_private_pkcs8"

        fun fromContext(context: Context): DeviceIdentityStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DeviceIdentityStore(prefs)
        }
    }
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun newUlidLikeId(): String {
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val now = System.currentTimeMillis()
    val random = ByteArray(10)
    SecureRandom().nextBytes(random)
    val chars = CharArray(26)
    var time = now
    for (index in 9 downTo 0) {
        chars[index] = alphabet[(time and 31).toInt()]
        time = time shr 5
    }
    var bitBuffer = 0
    var bitCount = 0
    var outputIndex = 10
    for (byte in random) {
        bitBuffer = (bitBuffer shl 8) or (byte.toInt() and 0xFF)
        bitCount += 8
        while (bitCount >= 5 && outputIndex < chars.size) {
            bitCount -= 5
            chars[outputIndex++] = alphabet[(bitBuffer shr bitCount) and 31]
        }
    }
    while (outputIndex < chars.size) {
        chars[outputIndex++] = alphabet[0]
    }
    return String(chars)
}
