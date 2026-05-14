package com.piko.app

import com.piko.app.domain.SendFileType
import com.piko.app.domain.TransferProtocolV3
import com.piko.app.domain.TransferV3Frame
import com.piko.app.domain.TransferV3KeyAgreementRole
import com.piko.app.domain.TransferV3ManifestInput
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TransferProtocolV3Test {
    @Test
    fun manifestAndChunkRoundTripUseAeadFrames() {
        val sessionId = "session-001"
        val transferId = "transfer-001"
        val key = sessionKey(sessionId, transferId)
        val files = listOf(
            TransferV3ManifestInput(
                displayName = "demo.jpg",
                fileType = SendFileType.Image,
                sizeBytes = 5,
                fileHash = TransferProtocolV3.sha256(byteArrayOf(1, 2, 3, 4, 5)),
            ),
        )

        val manifestFrame = TransferProtocolV3.decodeFrame(
            key,
            sessionId,
            transferId,
            TransferProtocolV3.encodeManifest(key, sessionId, transferId, files, "赤色星河"),
        )
        val manifest = assertIs<TransferV3Frame.Manifest>(manifestFrame).manifest
        assertEquals("赤色星河", manifest.senderName)
        assertEquals("demo.jpg", manifest.files.single().displayName)
        assertEquals(SendFileType.Image, manifest.files.single().fileType)
        assertContentEquals(TransferProtocolV3.sha256(byteArrayOf(1, 2, 3, 4, 5)), manifest.files.single().fileHash)

        val chunkBytes = byteArrayOf(1, 2, 3, 4, 5)
        val chunkFrame = TransferProtocolV3.decodeFrame(
            key,
            sessionId,
            transferId,
            TransferProtocolV3.encodeChunk(key, sessionId, transferId, fileIndex = 0, chunkIndex = 0, plain = chunkBytes),
        )
        val chunk = assertIs<TransferV3Frame.Chunk>(chunkFrame)
        assertEquals(0, chunk.fileIndex)
        assertEquals(0, chunk.chunkIndex)
        assertContentEquals(chunkBytes, chunk.bytes)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sessionId = "session-001"
        val transferId = "transfer-001"
        val key = sessionKey(sessionId, transferId)
        val frame = TransferProtocolV3.encodeChunk(
            key,
            sessionId,
            transferId,
            fileIndex = 0,
            chunkIndex = 1,
            plain = byteArrayOf(9, 8, 7),
        )
        frame[frame.lastIndex] = (frame.last() + 1).toByte()

        assertFailsWith<Exception> {
            TransferProtocolV3.decodeFrame(key, sessionId, transferId, frame)
        }
    }

    @Test
    fun readyAckAndRetryFramesRoundTripWithoutPayload() {
        val key = sessionKey("session-001", "transfer-001")
        val ready = TransferProtocolV3.decodeFrame(key, "session-001", "transfer-001", TransferProtocolV3.encodeReady())
        val ack = TransferProtocolV3.decodeFrame(key, "session-001", "transfer-001", TransferProtocolV3.encodeAck(2, 8))
        val retry = TransferProtocolV3.decodeFrame(key, "session-001", "transfer-001", TransferProtocolV3.encodeRetry(3, 9))

        assertEquals(TransferV3Frame.Ready, ready)
        assertEquals(TransferV3Frame.Ack(fileIndex = 2, chunkIndex = 8), ack)
        assertEquals(TransferV3Frame.Retry(fileIndex = 3, chunkIndex = 9), retry)
    }

    @Test
    fun ecdhSessionKeyMatchesAcrossPeers() {
        val senderEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val receiverEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val senderStatic = TransferProtocolV3.generateEphemeralKeyPair()
        val receiverStatic = TransferProtocolV3.generateEphemeralKeyPair()

        val senderKey = TransferProtocolV3.deriveSessionKey(
            sessionId = "session-ecdh",
            transferId = "transfer-ecdh",
            localEphemeralPrivateKeyPkcs8B64 = senderEphemeral.privateKeyPkcs8B64,
            peerEphemeralPublicKeyB64 = receiverEphemeral.publicKeyB64,
            localStaticPrivateKeyPkcs8B64 = senderStatic.privateKeyPkcs8B64,
            peerStaticPublicKeyB64 = receiverStatic.publicKeyB64,
            role = TransferV3KeyAgreementRole.Sender,
        )
        val receiverKey = TransferProtocolV3.deriveSessionKey(
            sessionId = "session-ecdh",
            transferId = "transfer-ecdh",
            localEphemeralPrivateKeyPkcs8B64 = receiverEphemeral.privateKeyPkcs8B64,
            peerEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
            localStaticPrivateKeyPkcs8B64 = receiverStatic.privateKeyPkcs8B64,
            peerStaticPublicKeyB64 = senderStatic.publicKeyB64,
            role = TransferV3KeyAgreementRole.Receiver,
        )

        assertContentEquals(senderKey, receiverKey)
    }

    @Test
    fun inviteAndAcceptSignaturesRejectTampering() {
        val senderSigning = TransferProtocolV3.generateSigningKeyPair()
        val receiverSigning = TransferProtocolV3.generateSigningKeyPair()
        val senderEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val receiverEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val manifestHashB64 = TransferProtocolV3.sha256(byteArrayOf(1, 2, 3)).base64()
        val inviteSignature = TransferProtocolV3.signInvite(
            transferId = "transfer-signed",
            manifestHashB64 = manifestHashB64,
            senderEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
            ed25519PrivateKeyPkcs8B64 = senderSigning.private.encoded.base64(),
        )

        assertEquals(
            true,
            TransferProtocolV3.verifyInviteSignature(
                transferId = "transfer-signed",
                manifestHashB64 = manifestHashB64,
                senderEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
                signatureB64 = inviteSignature,
                senderEd25519PublicKeyB64 = senderSigning.public.encoded.takeLast(32).toByteArray().base64(),
            ),
        )
        assertEquals(
            false,
            TransferProtocolV3.verifyInviteSignature(
                transferId = "transfer-tampered",
                manifestHashB64 = manifestHashB64,
                senderEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
                signatureB64 = inviteSignature,
                senderEd25519PublicKeyB64 = senderSigning.public.encoded.takeLast(32).toByteArray().base64(),
            ),
        )

        val acceptSignature = TransferProtocolV3.signAccept(
            sessionId = "session-signed",
            transferId = "transfer-signed",
            manifestHashB64 = manifestHashB64,
            senderEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
            receiverEphemeralPublicKeyB64 = receiverEphemeral.publicKeyB64,
            ed25519PrivateKeyPkcs8B64 = receiverSigning.private.encoded.base64(),
        )
        assertEquals(
            true,
            TransferProtocolV3.verifyAcceptSignature(
                sessionId = "session-signed",
                transferId = "transfer-signed",
                manifestHashB64 = manifestHashB64,
                senderEphemeralPublicKeyB64 = senderEphemeral.publicKeyB64,
                receiverEphemeralPublicKeyB64 = receiverEphemeral.publicKeyB64,
                signatureB64 = acceptSignature,
                receiverEd25519PublicKeyB64 = receiverSigning.public.encoded.takeLast(32).toByteArray().base64(),
            ),
        )
    }

    private fun sessionKey(sessionId: String, transferId: String): ByteArray {
        val senderEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val receiverEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val senderStatic = TransferProtocolV3.generateEphemeralKeyPair()
        val receiverStatic = TransferProtocolV3.generateEphemeralKeyPair()
        return TransferProtocolV3.deriveSessionKey(
            sessionId = sessionId,
            transferId = transferId,
            localEphemeralPrivateKeyPkcs8B64 = senderEphemeral.privateKeyPkcs8B64,
            peerEphemeralPublicKeyB64 = receiverEphemeral.publicKeyB64,
            localStaticPrivateKeyPkcs8B64 = senderStatic.privateKeyPkcs8B64,
            peerStaticPublicKeyB64 = receiverStatic.publicKeyB64,
            role = TransferV3KeyAgreementRole.Sender,
        )
    }
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
