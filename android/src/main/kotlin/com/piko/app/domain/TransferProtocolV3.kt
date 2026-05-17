package com.piko.app.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

object TransferProtocolV3 {
    const val chunkSize: Int = 64 * 1024
    private val magic = byteArrayOf(0x50, 0x49, 0x4B, 0x33)
    private const val frameManifest = 0x01
    private const val frameReady = 0x02
    private const val frameChunk = 0x03
    private const val frameAck = 0x04
    private const val frameRetry = 0x05
    private const val tagBits = 128
    private val x25519X509Prefix = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00,
    )
    private val ed25519X509Prefix = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private val curveProvider by lazy { BouncyCastleProvider() }

    fun generateEphemeralKeyPair(): TransferV3EphemeralKeyPair {
        val keyPair = generateAgreementKeyPair()
        return TransferV3EphemeralKeyPair(
            privateKeyPkcs8B64 = keyPair.private.encoded.base64(),
            publicKeyB64 = keyPair.public.encoded.takeLast(32).toByteArray().base64(),
        )
    }

    fun generateSigningKeyPair(): KeyPair = generateCurveKeyPair("Ed25519")

    fun generateAgreementKeyPair(): KeyPair = generateCurveKeyPair("X25519")

    fun deriveSessionKey(
        sessionId: String,
        transferId: String,
        localEphemeralPrivateKeyPkcs8B64: String,
        peerEphemeralPublicKeyB64: String,
        localStaticPrivateKeyPkcs8B64: String,
        peerStaticPublicKeyB64: String,
        role: TransferV3KeyAgreementRole,
    ): ByteArray {
        val firstSecret = when (role) {
            TransferV3KeyAgreementRole.Sender -> x25519(localEphemeralPrivateKeyPkcs8B64, peerStaticPublicKeyB64)
            TransferV3KeyAgreementRole.Receiver -> x25519(localStaticPrivateKeyPkcs8B64, peerEphemeralPublicKeyB64)
        }
        val secondSecret = when (role) {
            TransferV3KeyAgreementRole.Sender -> x25519(localStaticPrivateKeyPkcs8B64, peerEphemeralPublicKeyB64)
            TransferV3KeyAgreementRole.Receiver -> x25519(localEphemeralPrivateKeyPkcs8B64, peerStaticPublicKeyB64)
        }
        return hkdfSha256(
            salt = sha256(sessionId.toByteArray(Charsets.UTF_8)),
            ikm = firstSecret + secondSecret + x25519(localEphemeralPrivateKeyPkcs8B64, peerEphemeralPublicKeyB64),
            info = "piko-session-v3|$transferId".toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    fun signInvite(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        ed25519PrivateKeyPkcs8B64: String,
    ): String = signEd25519(
        ed25519PrivateKeyPkcs8B64,
        inviteSignaturePayload(transferId, manifestHashB64, senderEphemeralPublicKeyB64),
    ).base64()

    fun verifyInviteSignature(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        signatureB64: String,
        senderEd25519PublicKeyB64: String,
    ): Boolean = runCatching {
        verifyEd25519(
            senderEd25519PublicKeyB64,
            inviteSignaturePayload(transferId, manifestHashB64, senderEphemeralPublicKeyB64),
            signatureB64.decodeBase64(),
        )
    }.getOrDefault(false)

    fun signAccept(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String,
        ed25519PrivateKeyPkcs8B64: String,
    ): String = signEd25519(
        ed25519PrivateKeyPkcs8B64,
        acceptSignaturePayload(sessionId, transferId, manifestHashB64, senderEphemeralPublicKeyB64, receiverEphemeralPublicKeyB64),
    ).base64()

    fun verifyAcceptSignature(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String,
        signatureB64: String,
        receiverEd25519PublicKeyB64: String,
    ): Boolean = runCatching {
        verifyEd25519(
            receiverEd25519PublicKeyB64,
            acceptSignaturePayload(sessionId, transferId, manifestHashB64, senderEphemeralPublicKeyB64, receiverEphemeralPublicKeyB64),
            signatureB64.decodeBase64(),
        )
    }.getOrDefault(false)

    fun encodeManifest(
        sessionKey: ByteArray,
        sessionId: String,
        transferId: String,
        files: List<TransferV3ManifestInput>,
        senderName: String,
    ): ByteArray {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { output ->
            output.writeInt(3)
            output.writeString(senderName)
            output.writeInt(files.size)
            files.forEachIndexed { index, item ->
                output.writeInt(index)
                output.writeString(item.displayName)
                output.writeInt(item.fileType.v3Code)
                output.writeLong(item.sizeBytes)
                output.writeInt(chunkSize)
                output.writeInt(chunkCount(item.sizeBytes))
                output.write(item.fileHash)
            }
        }
        return encodeEncryptedFrame(sessionKey, sessionId, transferId, frameManifest, 0, 0, plain.toByteArray())
    }

    fun encodeChunk(
        sessionKey: ByteArray,
        sessionId: String,
        transferId: String,
        fileIndex: Int,
        chunkIndex: Int,
        plain: ByteArray,
    ): ByteArray = encodeEncryptedFrame(sessionKey, sessionId, transferId, frameChunk, fileIndex, chunkIndex, plain)

    fun encodeReady(): ByteArray = encodeControlFrame(frameReady, -1, -1)

    fun encodeAck(fileIndex: Int, chunkIndex: Int): ByteArray =
        encodeControlFrame(frameAck, fileIndex, chunkIndex)

    fun encodeRetry(fileIndex: Int, chunkIndex: Int): ByteArray =
        encodeControlFrame(frameRetry, fileIndex, chunkIndex)

    fun decodeFrame(
        sessionKey: ByteArray,
        sessionId: String,
        transferId: String,
        frame: ByteArray,
    ): TransferV3Frame {
        val input = DataInputStream(ByteArrayInputStream(frame))
        val decodedMagic = ByteArray(4)
        input.readFully(decodedMagic)
        require(decodedMagic.contentEquals(magic)) { "v3 frame magic 不匹配" }
        val type = input.readUnsignedByte()
        val fileIndex = input.readInt()
        val chunkIndex = input.readInt()
        val plainLength = input.readInt()
        val hash = ByteArray(32).also(input::readFully)
        val cipherLength = input.readInt()
        val cipherBytes = ByteArray(cipherLength).also(input::readFully)
        return when (type) {
            frameManifest -> {
                val plain = decrypt(sessionKey, nonce(sessionId, fileIndex, chunkIndex), aad(type, fileIndex, chunkIndex, plainLength, hash), cipherBytes)
                require(sha256(plain).contentEquals(hash)) { "manifest hash 不匹配" }
                TransferV3Frame.Manifest(parseManifest(plain))
            }
            frameChunk -> {
                val plain = decrypt(sessionKey, nonce(sessionId, fileIndex, chunkIndex), aad(type, fileIndex, chunkIndex, plainLength, hash), cipherBytes)
                require(plain.size == plainLength) { "chunk 长度不匹配" }
                require(sha256(plain).contentEquals(hash)) { "chunk hash 不匹配" }
                TransferV3Frame.Chunk(fileIndex, chunkIndex, plain)
            }
            frameReady -> TransferV3Frame.Ready
            frameAck -> TransferV3Frame.Ack(fileIndex, chunkIndex)
            frameRetry -> TransferV3Frame.Retry(fileIndex, chunkIndex)
            else -> error("未知 v3 frame 类型: $type")
        }
    }

    fun peekFrameHeader(frame: ByteArray): TransferV3FrameHeader? =
        runCatching {
            val input = DataInputStream(ByteArrayInputStream(frame))
            val decodedMagic = ByteArray(4)
            input.readFully(decodedMagic)
            if (!decodedMagic.contentEquals(magic)) return@runCatching null
            TransferV3FrameHeader(
                type = input.readUnsignedByte(),
                fileIndex = input.readInt(),
                chunkIndex = input.readInt(),
            )
        }.getOrNull()

    fun chunkCount(sizeBytes: Long): Int {
        if (sizeBytes <= 0L) return 0
        return ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
    }

    private fun encodeEncryptedFrame(
        sessionKey: ByteArray,
        sessionId: String,
        transferId: String,
        type: Int,
        fileIndex: Int,
        chunkIndex: Int,
        plain: ByteArray,
    ): ByteArray {
        val hash = sha256(plain)
        val aad = aad(type, fileIndex, chunkIndex, plain.size, hash)
        val cipherBytes = encrypt(sessionKey, nonce(sessionId, fileIndex, chunkIndex), aad, plain)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeByte(type)
                output.writeInt(fileIndex)
                output.writeInt(chunkIndex)
                output.writeInt(plain.size)
                output.write(hash)
                output.writeInt(cipherBytes.size)
                output.write(cipherBytes)
            }
        }.toByteArray()
    }

    private fun encodeControlFrame(type: Int, fileIndex: Int, chunkIndex: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeByte(type)
                output.writeInt(fileIndex)
                output.writeInt(chunkIndex)
                output.writeInt(0)
                output.write(ByteArray(32))
                output.writeInt(0)
            }
        }.toByteArray()

    private fun parseManifest(bytes: ByteArray): TransferV3Manifest {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version == 3) { "不支持的传输协议版本: $version" }
        val senderName = input.readString()
        val fileCount = input.readInt()
        val files = List(fileCount) {
            TransferV3File(
                index = input.readInt(),
                displayName = input.readString(),
                fileType = input.readInt().toSendFileType(),
                sizeBytes = input.readLong(),
                chunkSize = input.readInt(),
                chunkCount = input.readInt(),
                fileHash = ByteArray(32).also(input::readFully),
            )
        }
        return TransferV3Manifest(senderName = senderName, files = files)
    }

    private fun aad(type: Int, fileIndex: Int, chunkIndex: Int, plainLength: Int, hash: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeByte(type)
                output.writeInt(fileIndex)
                output.writeInt(chunkIndex)
                output.writeInt(plainLength)
                output.write(hash)
            }
        }.toByteArray()

    private fun nonce(sessionId: String, fileIndex: Int, chunkIndex: Int): ByteArray {
        val seed = sha256(sessionId.toByteArray(Charsets.UTF_8))
        return ByteArray(12).also { nonce ->
            seed.copyInto(nonce, endIndex = 4)
            nonce.writeInt(4, fileIndex)
            nonce.writeInt(8, chunkIndex)
        }
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    private fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipherBytes: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(cipherBytes)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
            updateAAD(aad)
        }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val result = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (result.size() < length) {
            previous = hmacSha256(prk, previous + info + counter.toByte())
            result.write(previous)
            counter += 1
        }
        return result.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun x25519(privateKeyPkcs8B64: String, peerPublicKeyB64: String): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519", curveProvider)
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8B64.decodeBase64()))
        val peerPublic = keyFactory.generatePublic(X509EncodedKeySpec(x25519X509Prefix + peerPublicKeyB64.decodeBase64()))
        return KeyAgreement.getInstance("X25519", curveProvider).run {
            init(privateKey)
            doPhase(peerPublic, true)
            generateSecret()
        }
    }

    private fun signEd25519(privateKeyPkcs8B64: String, payload: ByteArray): ByteArray {
        val privateKey = KeyFactory.getInstance("Ed25519", curveProvider).generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8B64.decodeBase64()))
        return Signature.getInstance("Ed25519", curveProvider).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private fun verifyEd25519(publicKeyB64: String, payload: ByteArray, signature: ByteArray): Boolean {
        val publicKey = KeyFactory.getInstance("Ed25519", curveProvider).generatePublic(X509EncodedKeySpec(ed25519X509Prefix + publicKeyB64.decodeBase64()))
        return Signature.getInstance("Ed25519", curveProvider).run {
            initVerify(publicKey)
            update(payload)
            verify(signature)
        }
    }

    private fun generateCurveKeyPair(algorithm: String): KeyPair =
        KeyPairGenerator.getInstance(algorithm, curveProvider)
            .apply { initialize(NamedParameterSpec(algorithm), SecureRandom()) }
            .generateKeyPair()

    private fun inviteSignaturePayload(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
    ): ByteArray = signaturePayload(
        "piko-invite-v3",
        transferId.toByteArray(Charsets.UTF_8),
        manifestHashB64.decodeBase64(),
        senderEphemeralPublicKeyB64.decodeBase64(),
    )

    private fun acceptSignaturePayload(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String,
    ): ByteArray = signaturePayload(
        "piko-accept-v3",
        sessionId.toByteArray(Charsets.UTF_8),
        transferId.toByteArray(Charsets.UTF_8),
        manifestHashB64.decodeBase64(),
        senderEphemeralPublicKeyB64.decodeBase64(),
        receiverEphemeralPublicKeyB64.decodeBase64(),
    )

    private fun signaturePayload(domain: String, vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeString(domain)
                parts.forEach { part ->
                    output.writeInt(part.size)
                    output.write(part)
                }
            }
        }.toByteArray()
}

enum class TransferV3KeyAgreementRole {
    Sender,
    Receiver,
}

data class TransferV3EphemeralKeyPair(
    val privateKeyPkcs8B64: String,
    val publicKeyB64: String,
)

data class TransferV3ManifestInput(
    val displayName: String,
    val fileType: SendFileType,
    val sizeBytes: Long,
    val fileHash: ByteArray,
) {
    init {
        require(fileHash.size == 32) { "file hash 必须是 32 字节 SHA-256" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferV3ManifestInput) return false
        return displayName == other.displayName &&
            fileType == other.fileType &&
            sizeBytes == other.sizeBytes &&
            fileHash.contentEquals(other.fileHash)
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + fileHash.contentHashCode()
        return result
    }
}

data class TransferV3Manifest(
    val senderName: String,
    val files: List<TransferV3File>,
)

data class TransferV3File(
    val index: Int,
    val displayName: String,
    val fileType: SendFileType,
    val sizeBytes: Long,
    val chunkSize: Int,
    val chunkCount: Int,
    val fileHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferV3File) return false
        return index == other.index &&
            displayName == other.displayName &&
            fileType == other.fileType &&
            sizeBytes == other.sizeBytes &&
            chunkSize == other.chunkSize &&
            chunkCount == other.chunkCount &&
            fileHash.contentEquals(other.fileHash)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + displayName.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + chunkSize
        result = 31 * result + chunkCount
        result = 31 * result + fileHash.contentHashCode()
        return result
    }
}

sealed class TransferV3Frame {
    data class Manifest(val manifest: TransferV3Manifest) : TransferV3Frame()
    data class Chunk(val fileIndex: Int, val chunkIndex: Int, val bytes: ByteArray) : TransferV3Frame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return fileIndex == other.fileIndex && chunkIndex == other.chunkIndex && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = fileIndex
            result = 31 * result + chunkIndex
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }
    data object Ready : TransferV3Frame()
    data class Ack(val fileIndex: Int, val chunkIndex: Int) : TransferV3Frame()
    data class Retry(val fileIndex: Int, val chunkIndex: Int) : TransferV3Frame()
}

data class TransferV3FrameHeader(
    val type: Int,
    val fileIndex: Int,
    val chunkIndex: Int,
) {
    val isChunk: Boolean get() = type == 0x03
}

private val SendFileType.v3Code: Int
    get() = when (this) {
        SendFileType.Document -> 0
        SendFileType.Spreadsheet -> 1
        SendFileType.Image -> 2
        SendFileType.Video -> 3
        SendFileType.Archive -> 4
        SendFileType.Other -> 5
    }

private fun Int.toSendFileType(): SendFileType =
    when (this) {
        0 -> SendFileType.Document
        1 -> SendFileType.Spreadsheet
        2 -> SendFileType.Image
        3 -> SendFileType.Video
        4 -> SendFileType.Archive
        else -> SendFileType.Other
    }

private fun ByteArray.writeInt(offset: Int, value: Int) {
    this[offset] = ((value ushr 24) and 0xFF).toByte()
    this[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 3] = (value and 0xFF).toByte()
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readString(): String {
    val length = readInt()
    require(length >= 0) { "字符串长度不合法" }
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}
