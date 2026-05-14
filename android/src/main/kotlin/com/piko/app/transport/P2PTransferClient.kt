package com.piko.app.transport

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.piko.app.data.DeviceIdentityStore
import com.piko.app.data.TokenStorage
import com.piko.app.data.TransferProgressStore
import com.piko.app.domain.AccountResult
import com.piko.app.domain.ReceiveFileType
import com.piko.app.domain.ReceiveHistoryFile
import com.piko.app.domain.ReceiveTransferEvent
import com.piko.app.domain.SendDevice
import com.piko.app.domain.SendFileType
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferItem
import com.piko.app.domain.TransferProtocolV3
import com.piko.app.domain.TransferV3EphemeralKeyPair
import com.piko.app.domain.TransferV3File
import com.piko.app.domain.TransferV3Frame
import com.piko.app.domain.TransferV3KeyAgreementRole
import com.piko.app.domain.TransferV3ManifestInput
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

data class P2PTransferDiagnostic(
    val offerSent: Boolean = false,
    val answerReceived: Boolean = false,
    val localIceCount: Int = 0,
    val remoteIceCount: Int = 0,
    val iceConnectionState: String = "unknown",
    val dataChannelState: String = "unknown",
)

class P2PTransferFailure(
    val stage: String,
    val transferId: String,
    val sessionId: String?,
    val originalReason: String,
    val diagnostic: P2PTransferDiagnostic = P2PTransferDiagnostic(),
    cause: Throwable? = null,
) : IllegalStateException(originalReason, cause)

class P2PTransferClient(
    private val context: Context,
    private val tokenStore: TokenStorage,
    private val identityStore: DeviceIdentityStore,
    private val sessionsApi: TransferSessionApiClient,
    private val signalingClient: SignalingWebSocketClient,
    private val senderName: String,
    private val onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
) {
    private val peers = ConcurrentHashMap<String, P2PPeer>()
    private val receiversByTransferId = ConcurrentHashMap<String, P2PReceiver>()
    private val pendingSignalsBySessionId = ConcurrentHashMap<String, ConcurrentLinkedQueue<JSONObject>>()
    private val progressStore = TransferProgressStore(context)
    private val signalExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "piko-p2p-signal").apply { isDaemon = true }
    }

    init {
        initializeFactory(context)
        signalingClient.addListener { message -> signalExecutor.execute { handleSignal(message) } }
    }

    fun send(
        target: SendDevice,
        transferId: String,
        items: List<SendTransferItem>,
        totalCompletedBeforeTarget: Long,
        totalBytes: Long,
        callback: (SendTransferEvent) -> Unit,
        ensureActive: () -> Unit,
    ): Long {
        val manifestFiles = runCatching {
            items.toManifestInputs(context.contentResolver)
        }.getOrElse { error ->
            throw P2PTransferFailure(
                stage = "send_manifest",
                transferId = transferId,
                sessionId = null,
                originalReason = error.message ?: "无法读取待发送文件清单",
                cause = error,
            )
        }
        val sessionContext = try {
            kotlinx.coroutines.runBlocking { createSession(target, transferId, manifestFiles) }
        } catch (failure: P2PTransferFailure) {
            throw failure
        } catch (error: Throwable) {
            throw P2PTransferFailure(
                stage = "create_session",
                transferId = transferId,
                sessionId = null,
                originalReason = error.message ?: "跨网传输会话创建失败",
                cause = error,
            )
        }
        val session = sessionContext.config
        if (session.iceServers.any { it.urls.contains("google", ignoreCase = true) }) {
            throw P2PTransferFailure(
                stage = "create_session",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = "ICE 配置包含非 Cloudflare STUN",
            )
        }
        val sentFrames = ConcurrentHashMap<ChunkKey, ByteArray>()
        val ackedChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
        val totalChunks = manifestFiles.sumOf { TransferProtocolV3.chunkCount(it.sizeBytes) }
        val ackLatch = CountDownLatch(totalChunks)
        val receiverReadyLatch = CountDownLatch(1)
        val peer = P2PPeer(
            session.sessionId,
            session.iceServers,
            signalingClient,
            receiver = null,
            onBinary = { bytes, channel ->
                handleSenderControlFrame(
                    sessionKey = sessionContext.sessionKey,
                    sessionId = session.sessionId,
                    transferId = transferId,
                    bytes = bytes,
                    channel = channel,
                    sentFrames = sentFrames,
                    ackedChunks = ackedChunks,
                    ackLatch = ackLatch,
                    receiverReadyLatch = receiverReadyLatch,
                )
            },
        )
        peers[session.sessionId] = peer
        val channel = runCatching {
            peer.createOfferer()
        }.getOrElse { error ->
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "data_channel_open",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = error.message ?: "WebRTC offer 创建失败",
                diagnostic = peer.diagnosticSnapshot(),
                cause = error,
            )
        }
        if (!peer.awaitOpen()) {
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "data_channel_open",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = "DATA_CHANNEL_TIMEOUT：跨网直连超时",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
        val peerHandshake = runCatching {
            peer.awaitPeerHandshake()
        }.getOrElse { error ->
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "key_agreement",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = error.message ?: "接收方握手等待失败",
                diagnostic = peer.diagnosticSnapshot(),
                cause = error,
            )
        }
        if (
            !TransferProtocolV3.verifyAcceptSignature(
                sessionId = session.sessionId,
                transferId = transferId,
                manifestHashB64 = sessionContext.manifestHashB64,
                senderEphemeralPublicKeyB64 = sessionContext.ephemeral.publicKeyB64,
                receiverEphemeralPublicKeyB64 = peerHandshake.ephemeralPublicB64,
                signatureB64 = peerHandshake.acceptSignatureB64,
                receiverEd25519PublicKeyB64 = sessionContext.receiverEd25519PubB64,
            )
        ) {
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "key_agreement",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = "接收方签名校验失败",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
        val sessionKey = runCatching {
            TransferProtocolV3.deriveSessionKey(
                sessionId = session.sessionId,
                transferId = transferId,
                localEphemeralPrivateKeyPkcs8B64 = sessionContext.ephemeral.privateKeyPkcs8B64,
                peerEphemeralPublicKeyB64 = peerHandshake.ephemeralPublicB64,
                localStaticPrivateKeyPkcs8B64 = sessionContext.senderX25519PrivatePkcs8B64,
                peerStaticPublicKeyB64 = sessionContext.receiverX25519PubB64,
                role = TransferV3KeyAgreementRole.Sender,
            )
        }.getOrElse { error ->
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "key_agreement",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = error.message ?: "跨网密钥协商失败",
                diagnostic = peer.diagnosticSnapshot(),
                cause = error,
            )
        }
        sessionContext.sessionKey = sessionKey

        val peerCompletedChunks = peerHandshake.completedChunks
        val manifestFrame = runCatching {
            TransferProtocolV3.encodeManifest(
                sessionKey = sessionKey,
                sessionId = session.sessionId,
                transferId = transferId,
                files = manifestFiles,
                senderName = senderName,
            )
        }.getOrElse { error ->
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "send_manifest",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = error.message ?: "传输清单编码失败",
                diagnostic = peer.diagnosticSnapshot(),
                cause = error,
            )
        }
        runCatching {
            sendBinary(channel, manifestFrame)
        }.getOrElse { error ->
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "send_manifest",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = error.message ?: "传输清单发送失败",
                diagnostic = peer.diagnosticSnapshot(),
                cause = error,
            )
        }
        if (!receiverReadyLatch.await(120, TimeUnit.SECONDS)) {
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "receiver_ready",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = "P2P_RECEIVER_READY_TIMEOUT：等待接收端确认超时",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
        var sentBytes = 0L
        val buffer = ByteArray(TransferProtocolV3.chunkSize)
        items.forEachIndexed { fileIndex, item ->
            runCatching {
                ensureActive()
                context.contentResolver.openInputStream(Uri.parse(item.sourceUri)).use { input ->
                    requireNotNull(input) { "无法读取 ${item.displayName}" }
                    var chunkIndex = 0
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        val chunkKey = ChunkKey(fileIndex, chunkIndex)
                        if (peerCompletedChunks[fileIndex]?.contains(chunkIndex) == true) {
                            if (ackedChunks.add(chunkKey)) ackLatch.countDown()
                            sentBytes += read
                            chunkIndex += 1
                            callback(
                                SendTransferEvent.Progress(
                                    transferId = transferId,
                                    completedBytes = (totalCompletedBeforeTarget + sentBytes).coerceAtMost(totalBytes),
                                    totalBytes = totalBytes,
                                ),
                            )
                            continue
                        }
                        val chunkFrame = runCatching {
                            TransferProtocolV3.encodeChunk(
                                sessionKey = sessionKey,
                                sessionId = session.sessionId,
                                transferId = transferId,
                                fileIndex = fileIndex,
                                chunkIndex = chunkIndex,
                                plain = buffer.copyOf(read),
                            )
                        }.getOrElse { error ->
                            peer.close()
                            peers.remove(session.sessionId)
                            throw P2PTransferFailure(
                                stage = "send_chunk",
                                transferId = transferId,
                                sessionId = session.sessionId,
                                originalReason = error.message ?: "文件分片编码失败",
                                diagnostic = peer.diagnosticSnapshot(),
                                cause = error,
                            )
                        }
                        sentFrames[chunkKey] = chunkFrame
                        runCatching {
                            sendBinary(channel, chunkFrame)
                        }.getOrElse { error ->
                            peer.close()
                            peers.remove(session.sessionId)
                            throw P2PTransferFailure(
                                stage = "send_chunk",
                                transferId = transferId,
                                sessionId = session.sessionId,
                                originalReason = error.message ?: "文件分片发送失败",
                                diagnostic = peer.diagnosticSnapshot(),
                                cause = error,
                            )
                        }
                        sentBytes += read
                        chunkIndex += 1
                        callback(
                            SendTransferEvent.Progress(
                                transferId = transferId,
                                completedBytes = (totalCompletedBeforeTarget + sentBytes).coerceAtMost(totalBytes),
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
            }.getOrElse { error ->
                if (error is P2PTransferFailure) throw error
                peer.close()
                peers.remove(session.sessionId)
                throw P2PTransferFailure(
                    stage = "send_chunk",
                    transferId = transferId,
                    sessionId = session.sessionId,
                    originalReason = error.message ?: "文件分片读取失败",
                    diagnostic = peer.diagnosticSnapshot(),
                    cause = error,
                )
            }
        }
        if (totalChunks > 0 && !ackLatch.await(30, TimeUnit.SECONDS)) {
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "ack",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = "P2P_ACK_TIMEOUT：跨网传输确认超时",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
        channel.close()
        peer.close()
        peers.remove(session.sessionId)
        kotlinx.coroutines.runBlocking {
            tokenStore.load()?.let { token -> sessionsApi.finishSession(token, session.sessionId) }
        }
        return sentBytes
    }

    private suspend fun createSession(
        target: SendDevice,
        transferId: String,
        manifestFiles: List<TransferV3ManifestInput>,
    ): P2PSendSession {
        val token = tokenStore.load() ?: error("登录已过期")
        val receiverUserId = requireNotNull(target.receiverUserId) { "目标用户缺失" }
        val receiverDeviceId = requireNotNull(target.receiverDeviceId) { "目标设备缺失" }
        val receiverEd25519PubB64 = requireNotNull(target.receiverEd25519PubB64) { "目标签名公钥缺失" }
        val receiverX25519PubB64 = requireNotNull(target.receiverX25519PubB64) { "目标密钥协商公钥缺失" }
        val identity = identityStore.loadOrCreate()
        val ephemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val manifestHashB64 = manifestFiles.manifestHashB64()
        val inviteSignatureB64 = TransferProtocolV3.signInvite(
            transferId = transferId,
            manifestHashB64 = manifestHashB64,
            senderEphemeralPublicKeyB64 = ephemeral.publicKeyB64,
            ed25519PrivateKeyPkcs8B64 = identity.ed25519PrivatePkcs8B64,
        )
        val session = sessionsApi.createSession(
            token = token,
            receiverUserId = receiverUserId,
            receiverDeviceId = receiverDeviceId,
            transferId = transferId,
            manifestHashB64 = manifestHashB64,
            senderX25519EphPubB64 = ephemeral.publicKeyB64,
            senderDeviceId = identity.deviceId,
            senderInviteSignatureB64 = inviteSignatureB64,
        )
        return when (session) {
            is AccountResult.Ok -> P2PSendSession(
                config = session.value,
                ephemeral = ephemeral,
                senderX25519PrivatePkcs8B64 = identity.x25519PrivatePkcs8B64,
                receiverEd25519PubB64 = receiverEd25519PubB64,
                receiverX25519PubB64 = receiverX25519PubB64,
                manifestHashB64 = manifestHashB64,
            )
            is AccountResult.Err -> throw P2PTransferFailure(
                stage = "create_session",
                transferId = transferId,
                sessionId = null,
                originalReason = session.error.messageOrCode(),
            )
        }
    }

    private fun handleSignal(message: JSONObject) {
        val sessionId = message.optString("session_id").takeIf { it.isNotBlank() } ?: return
        when (message.optString("type")) {
            "invite" -> {
                val autoAccept = message.optBoolean("same_account", false)
                val transferId = message.optString("transfer_id", sessionId)
                val peer = peers[sessionId] ?: runCatching {
                    receiverPeer(
                        sessionId = sessionId,
                        transferId = transferId,
                        manifestHashB64 = message.optString("manifest_hash_b64"),
                        iceServers = message.optIceServers(),
                        completedBitmapB64 = progressStore.completedBitmapB64(
                            transferId = transferId,
                            manifestHashB64 = message.optString("manifest_hash_b64"),
                        ),
                        senderEphemeralPublicB64 = message.optString("sender_x25519_eph_pub_b64"),
                        senderInviteSignatureB64 = message.optString("sender_invite_signature_b64"),
                        senderEd25519PublicB64 = message.optString("sender_ed25519_pub_b64"),
                        senderX25519PublicB64 = message.optString("sender_x25519_pub_b64"),
                        autoAccept = autoAccept,
                    ).also { created -> peers[sessionId] = created }
                }.getOrNull() ?: return
                onReceiveTransferEvent(
                    ReceiveTransferEvent.Started(
                        transferId = transferId,
                        senderName = "",
                        files = emptyList(),
                        totalBytes = 0L,
                        requiresConfirmation = !autoAccept,
                    ),
                )
                flushPendingSignals(sessionId, peer)
            }
            "offer", "answer", "ice_candidate" -> {
                val peer = peers[sessionId]
                if (peer == null) {
                    bufferSignal(sessionId, message)
                } else {
                    dispatchSignal(peer, message)
                }
            }
            "bye" -> {
                pendingSignalsBySessionId.remove(sessionId)
                peers.remove(sessionId)?.close()
            }
        }
    }

    private fun bufferSignal(sessionId: String, message: JSONObject) {
        pendingSignalsBySessionId
            .computeIfAbsent(sessionId) { ConcurrentLinkedQueue() }
            .add(JSONObject(message.toString()))
    }

    private fun flushPendingSignals(sessionId: String, peer: P2PPeer) {
        val pending = pendingSignalsBySessionId.remove(sessionId) ?: return
        while (true) {
            dispatchSignal(peer, pending.poll() ?: return)
        }
    }

    private fun dispatchSignal(peer: P2PPeer, message: JSONObject) {
        when (message.optString("type")) {
            "offer" -> peer.acceptOffer(message.getString("sdp"))
            "answer" -> peer.acceptAnswer(message)
            "ice_candidate" -> peer.addCandidate(message)
        }
    }

    private fun receiverPeer(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        iceServers: List<IceServerConfig>,
        completedBitmapB64: String?,
        senderEphemeralPublicB64: String,
        senderInviteSignatureB64: String,
        senderEd25519PublicB64: String,
        senderX25519PublicB64: String,
        autoAccept: Boolean,
    ): P2PPeer {
        require(manifestHashB64.isNotBlank()) { "缺少 manifest hash" }
        require(senderEphemeralPublicB64.isNotBlank()) { "缺少发送方临时公钥" }
        require(
            TransferProtocolV3.verifyInviteSignature(
                transferId = transferId,
                manifestHashB64 = manifestHashB64,
                senderEphemeralPublicKeyB64 = senderEphemeralPublicB64,
                signatureB64 = senderInviteSignatureB64,
                senderEd25519PublicKeyB64 = senderEd25519PublicB64,
            ),
        ) { "发送方签名校验失败" }
        val identity = identityStore.loadOrCreate()
        val receiverEphemeral = TransferProtocolV3.generateEphemeralKeyPair()
        val acceptSignatureB64 = TransferProtocolV3.signAccept(
            sessionId = sessionId,
            transferId = transferId,
            manifestHashB64 = manifestHashB64,
            senderEphemeralPublicKeyB64 = senderEphemeralPublicB64,
            receiverEphemeralPublicKeyB64 = receiverEphemeral.publicKeyB64,
            ed25519PrivateKeyPkcs8B64 = identity.ed25519PrivatePkcs8B64,
        )
        val sessionKey = TransferProtocolV3.deriveSessionKey(
            sessionId = sessionId,
            transferId = transferId,
            localEphemeralPrivateKeyPkcs8B64 = receiverEphemeral.privateKeyPkcs8B64,
            peerEphemeralPublicKeyB64 = senderEphemeralPublicB64,
            localStaticPrivateKeyPkcs8B64 = identity.x25519PrivatePkcs8B64,
            peerStaticPublicKeyB64 = senderX25519PublicB64,
            role = TransferV3KeyAgreementRole.Receiver,
        )
        return P2PReceiver(
            contentResolver = context.contentResolver,
            progressStore = progressStore,
            sessionId = sessionId,
            transferId = transferId,
            manifestHashB64 = manifestHashB64,
            sessionKey = sessionKey,
            autoAccept = autoAccept,
            onReceiveTransferEvent = onReceiveTransferEvent,
        ).also { receiver ->
            receiversByTransferId[transferId] = receiver
        }.let { receiver ->
            P2PPeer(
                sessionId = sessionId,
                iceServers = iceServers,
                signalingClient = signalingClient,
                receiver = receiver,
                receiverEphemeralPublicB64 = receiverEphemeral.publicKeyB64,
                receiverAcceptSignatureB64 = acceptSignatureB64,
                receiverCompletedBitmapB64 = completedBitmapB64,
                onBinary = null,
            )
        }
    }

    fun acceptReceiveTransfer(transferId: String) {
        receiversByTransferId[transferId]?.accept()
    }

    fun cancelReceiveTransfer(transferId: String) {
        receiversByTransferId.remove(transferId)?.cancel()
    }

    private fun sendBinary(channel: DataChannel, bytes: ByteArray) {
        require(channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))) {
            "DataChannel 发送失败"
        }
    }

    companion object {
        @Volatile
        private var initialized = false
        private lateinit var factory: PeerConnectionFactory

        private fun initializeFactory(context: Context) {
            if (initialized) return
            synchronized(P2PTransferClient::class.java) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions(),
                )
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                initialized = true
            }
        }
    }

    private class P2PPeer(
        private val sessionId: String,
        iceServers: List<IceServerConfig>,
        private val signalingClient: SignalingWebSocketClient,
        private val receiver: P2PReceiver?,
        private val receiverEphemeralPublicB64: String? = null,
        private val receiverAcceptSignatureB64: String? = null,
        private val receiverCompletedBitmapB64: String? = null,
        private val onBinary: ((ByteArray, DataChannel) -> Unit)?,
    ) {
        private val openLatch = CountDownLatch(1)
        private val peerEphemeralLatch = CountDownLatch(1)
        @Volatile
        private var peerEphemeralPublicB64: String? = null
        @Volatile
        private var peerAcceptSignatureB64: String? = null
        @Volatile
        private var peerCompletedBitmapB64: String? = null
        @Volatile
        private var hasRemoteDescription = false
        @Volatile
        private var offerSent = false
        @Volatile
        private var answerReceived = false
        @Volatile
        private var localIceCount = 0
        @Volatile
        private var remoteIceCount = 0
        @Volatile
        private var iceConnectionState = "unknown"
        @Volatile
        private var dataChannelState = "unknown"
        private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
        private val peerConnection: PeerConnection = requireNotNull(
            factory.createPeerConnection(
                PeerConnection.RTCConfiguration(
                    iceServers.map { PeerConnection.IceServer.builder(it.urls).createIceServer() },
                ).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                object : PeerConnection.Observer by NoopPeerObserver {
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        iceConnectionState = state.name
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        localIceCount += 1
                        signalingClient.send(
                            JSONObject()
                                .put("type", "ice_candidate")
                                .put("session_id", sessionId)
                                .put("candidate", candidate.sdp)
                                .put("sdp_mid", candidate.sdpMid)
                                .put("sdp_m_line_index", candidate.sdpMLineIndex),
                        )
                    }

                    override fun onDataChannel(channel: DataChannel) {
                        dataChannelState = channel.state().name
                        receiver?.attach(sessionId, channel)
                    }
                },
            ),
        ) { "无法创建 WebRTC PeerConnection" }

        fun createOfferer(): DataChannel {
            val channel = peerConnection.createDataChannel("piko-v3", DataChannel.Init().apply { ordered = true })
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    dataChannelState = channel.state().name
                    if (channel.state() == DataChannel.State.OPEN) openLatch.countDown()
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    onBinary?.invoke(bytes, channel)
                }
            })
            val offer = createSdp { observer -> peerConnection.createOffer(observer, MediaConstraints()) }
            setLocal(offer)
            offerSent = signalingClient.send(JSONObject().put("type", "offer").put("session_id", sessionId).put("sdp", offer.description))
            return channel
        }

        fun acceptOffer(sdp: String) {
            setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
            flushPendingIceCandidates()
            val answer = createSdp { observer -> peerConnection.createAnswer(observer, MediaConstraints()) }
            setLocal(answer)
            signalingClient.send(
                JSONObject()
                    .put("type", "answer")
                    .put("session_id", sessionId)
                    .put("sdp", answer.description)
                    .put("receiver_x25519_eph_pub_b64", requireNotNull(receiverEphemeralPublicB64) { "缺少接收方临时公钥" })
                    .put("receiver_accept_signature_b64", requireNotNull(receiverAcceptSignatureB64) { "缺少接收方签名" })
                    .put("completed_chunks_bitmap_b64", receiverCompletedBitmapB64 ?: ""),
            )
        }

        fun acceptAnswer(message: JSONObject) {
            answerReceived = true
            peerEphemeralPublicB64 = message.optString("receiver_x25519_eph_pub_b64").takeIf { it.isNotBlank() }
            peerAcceptSignatureB64 = message.optString("receiver_accept_signature_b64").takeIf { it.isNotBlank() }
            peerCompletedBitmapB64 = message.optString("completed_chunks_bitmap_b64").takeIf { it.isNotBlank() }
            peerEphemeralLatch.countDown()
            setRemote(SessionDescription(SessionDescription.Type.ANSWER, message.getString("sdp")))
            flushPendingIceCandidates()
        }

        fun addCandidate(message: JSONObject) {
            val candidate = IceCandidate(
                message.optString("sdp_mid"),
                message.optInt("sdp_m_line_index"),
                message.getString("candidate"),
            )
            remoteIceCount += 1
            if (!hasRemoteDescription) {
                pendingIceCandidates.add(candidate)
                if (hasRemoteDescription) {
                    flushPendingIceCandidates()
                }
                return
            }
            peerConnection.addIceCandidate(candidate)
        }

        fun awaitOpen(): Boolean = openLatch.await(15, TimeUnit.SECONDS)

        fun awaitPeerHandshake(): P2PPeerHandshake {
            check(peerEphemeralLatch.await(10, TimeUnit.SECONDS)) { "接收方临时公钥等待超时" }
            return P2PPeerHandshake(
                ephemeralPublicB64 = requireNotNull(peerEphemeralPublicB64) { "接收方临时公钥缺失" },
                acceptSignatureB64 = requireNotNull(peerAcceptSignatureB64) { "接收方签名缺失" },
                completedChunks = TransferProgressStore.decodeCompletedBitmap(peerCompletedBitmapB64),
            )
        }

        fun diagnosticSnapshot(): P2PTransferDiagnostic =
            P2PTransferDiagnostic(
                offerSent = offerSent,
                answerReceived = answerReceived,
                localIceCount = localIceCount,
                remoteIceCount = remoteIceCount,
                iceConnectionState = iceConnectionState,
                dataChannelState = dataChannelState,
            )

        fun close() {
            pendingIceCandidates.clear()
            peerConnection.close()
            peerConnection.dispose()
        }

        private fun flushPendingIceCandidates() {
            while (true) {
                val candidate = pendingIceCandidates.poll() ?: return
                peerConnection.addIceCandidate(candidate)
            }
        }

        private fun createSdp(block: (SdpObserver) -> Unit): SessionDescription {
            val observer = BlockingSdpObserver()
            block(observer)
            return observer.awaitDescription()
        }

        private fun setLocal(description: SessionDescription) {
            val observer = BlockingSdpObserver()
            peerConnection.setLocalDescription(observer, description)
            observer.awaitSet()
        }

        private fun setRemote(description: SessionDescription) {
            val observer = BlockingSdpObserver()
            peerConnection.setRemoteDescription(observer, description)
            observer.awaitSet()
            hasRemoteDescription = true
        }
    }
}

private class P2PReceiver(
    private val contentResolver: ContentResolver,
    private val progressStore: TransferProgressStore,
    private val sessionId: String,
    private val transferId: String,
    private val manifestHashB64: String,
    private val sessionKey: ByteArray,
    private val autoAccept: Boolean,
    private val onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
) {
    private var senderName = "跨网设备"
    private var manifest: List<TransferV3File> = emptyList()
    private var totalBytes = 0L
    private var completedBytes = 0L
    private val transferDir = progressStore.transferDir(transferId)
    private val outputs = mutableMapOf<Int, RandomAccessFile>()
    private val outputFiles = mutableMapOf<Int, File>()
    private val completedChunks = mutableMapOf<Int, BooleanArray>()
    private val hashRetryCount = mutableMapOf<Int, Int>()
    private val pendingChunkFrames = mutableListOf<Pair<ByteArray, DataChannel>>()
    private val receivedFiles = mutableListOf<ReceiveHistoryFile>()
    private var confirmed = false
    private var activeChannel: DataChannel? = null
    private var didComplete = false

    fun attach(sessionId: String, channel: DataChannel) {
        activeChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) finishIfComplete(channel)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handle(bytes, channel)
            }
        })
    }

    private fun handle(bytes: ByteArray, channel: DataChannel) {
        val frame = runCatching {
            TransferProtocolV3.decodeFrame(sessionKey, sessionId, transferId, bytes)
        }.getOrElse {
            TransferProtocolV3.peekFrameHeader(bytes)
                ?.takeIf { header -> header.isChunk }
                ?.let { header -> sendRetry(channel, header.fileIndex, header.chunkIndex) }
            return
        }
        when (frame) {
            is TransferV3Frame.Manifest -> {
                senderName = frame.manifest.senderName
                manifest = frame.manifest.files
                totalBytes = manifest.sumOf { it.sizeBytes }
                val restoredChunks = progressStore.completedChunks(transferId, manifestHashB64, manifest)
                completedBytes = restoredChunks.entries.sumOf { (fileIndex, bitmap) ->
                    val file = manifest.firstOrNull { it.index == fileIndex } ?: return@sumOf 0L
                    bitmap.withIndex().sumOf { (chunkIndex, completed) ->
                        if (completed) expectedChunkLength(file, chunkIndex).toLong() else 0L
                    }
                }
                receivedFiles.clear()
                if (restoredChunks.isEmpty()) transferDir.deleteRecursively()
                require(transferDir.exists() || transferDir.mkdirs()) { "无法创建跨网接收缓存目录" }
                manifest.forEach { file ->
                    val tempFile = File(transferDir, "${file.index}.part")
                    outputFiles[file.index] = tempFile
                    outputs[file.index] = RandomAccessFile(tempFile, "rw").apply {
                        setLength(file.sizeBytes)
                    }
                    completedChunks[file.index] = restoredChunks[file.index] ?: BooleanArray(file.chunkCount)
                }
                progressStore.save(transferId, manifestHashB64, manifest, completedChunks)
                confirmed = autoAccept
                onReceiveTransferEvent(
                    ReceiveTransferEvent.Started(
                        transferId = transferId,
                        senderName = senderName,
                        files = manifest.map { it.toReceiveHistoryFile(null) },
                        totalBytes = totalBytes,
                        requiresConfirmation = !autoAccept,
                    ),
                )
                if (autoAccept) {
                    sendReady(channel)
                }
            }
            is TransferV3Frame.Chunk -> {
                if (!confirmed) {
                    pendingChunkFrames += bytes to channel
                    return
                }
                val file = manifest.firstOrNull { it.index == frame.fileIndex } ?: return
                val bitmap = completedChunks[frame.fileIndex] ?: return
                if (frame.chunkIndex !in bitmap.indices) {
                    sendRetry(channel, frame.fileIndex, frame.chunkIndex)
                    return
                }
                val expectedLength = expectedChunkLength(file, frame.chunkIndex)
                if (frame.bytes.size != expectedLength) {
                    sendRetry(channel, frame.fileIndex, frame.chunkIndex)
                    return
                }
                if (!bitmap[frame.chunkIndex]) {
                    val output = outputs[frame.fileIndex] ?: return
                    output.seek(frame.chunkIndex.toLong() * file.chunkSize.toLong())
                    output.write(frame.bytes)
                    bitmap[frame.chunkIndex] = true
                    completedBytes += frame.bytes.size
                    progressStore.save(transferId, manifestHashB64, manifest, completedChunks)
                }
                sendAck(channel, frame.fileIndex, frame.chunkIndex)
                onReceiveTransferEvent(ReceiveTransferEvent.Progress(transferId, completedBytes, totalBytes))
                finishIfComplete(channel)
            }
            is TransferV3Frame.Ready,
            is TransferV3Frame.Ack,
            is TransferV3Frame.Retry,
            -> Unit
        }
    }

    fun accept() {
        if (confirmed || manifest.isEmpty()) return
        confirmed = true
        onReceiveTransferEvent(
            ReceiveTransferEvent.Started(
                transferId = transferId,
                senderName = senderName,
                files = manifest.map { it.toReceiveHistoryFile(null) },
                totalBytes = totalBytes,
                requiresConfirmation = false,
            ),
        )
        activeChannel?.let(::sendReady)
        val pending = pendingChunkFrames.toList()
        pendingChunkFrames.clear()
        pending.forEach { (bytes, channel) -> handle(bytes, channel) }
    }

    fun cancel() {
        outputs.values.forEach { runCatching { it.close() } }
        outputs.clear()
        progressStore.clear(transferId)
        pendingChunkFrames.clear()
        onReceiveTransferEvent(ReceiveTransferEvent.Canceled(transferId))
    }

    private fun finishIfComplete(channel: DataChannel) {
        if (didComplete || manifest.isEmpty() || completedBytes < totalBytes) return
        if (completedChunks.values.any { bitmap -> bitmap.any { completed -> !completed } }) return

        manifest.forEach { file ->
            val tempFile = outputFiles[file.index] ?: return
            outputs[file.index]?.fd?.sync()
            if (!sha256File(tempFile).contentEquals(file.fileHash)) {
                resetFileForRetry(file, channel)
                return
            }
        }

        outputs.values.forEach { it.close() }
        outputs.clear()
        receivedFiles.clear()
        manifest.forEach { file ->
            val uri = contentResolver.createDownloadUri(file)
            val tempFile = requireNotNull(outputFiles[file.index]) { "缺少 ${file.displayName} 缓存文件" }
            requireNotNull(contentResolver.openOutputStream(uri)) { "无法写入 ${file.displayName}" }.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            }
            receivedFiles += file.toReceiveHistoryFile(uri.toString())
        }
        didComplete = true
        progressStore.clear(transferId)
        onReceiveTransferEvent(
            ReceiveTransferEvent.Completed(
                transferId = transferId,
                senderName = senderName,
                files = receivedFiles,
                receivedAtEpochMillis = System.currentTimeMillis(),
                receivedAtLabel = "刚刚",
            ),
        )
    }

    private fun resetFileForRetry(file: TransferV3File, channel: DataChannel) {
        val attempts = (hashRetryCount[file.index] ?: 0) + 1
        hashRetryCount[file.index] = attempts
        if (attempts > 3) {
            onReceiveTransferEvent(ReceiveTransferEvent.Failed(transferId, "文件校验失败"))
            return
        }
        val bitmap = completedChunks[file.index] ?: return
        completedBytes -= bitmap.withIndex().sumOf { (chunkIndex, completed) ->
            if (completed) expectedChunkLength(file, chunkIndex).toLong() else 0L
        }
        bitmap.fill(false)
        outputs[file.index]?.setLength(file.sizeBytes)
        progressStore.save(transferId, manifestHashB64, manifest, completedChunks)
        repeat(file.chunkCount) { chunkIndex ->
            sendRetry(channel, file.index, chunkIndex)
        }
    }

    private fun expectedChunkLength(file: TransferV3File, chunkIndex: Int): Int {
        val offset = chunkIndex.toLong() * file.chunkSize.toLong()
        return minOf(file.chunkSize.toLong(), file.sizeBytes - offset).toInt()
    }

    private fun sendAck(channel: DataChannel, fileIndex: Int, chunkIndex: Int) {
        require(channel.send(DataChannel.Buffer(ByteBuffer.wrap(TransferProtocolV3.encodeAck(fileIndex, chunkIndex)), true))) {
            "DataChannel ACK 发送失败"
        }
    }

    private fun sendReady(channel: DataChannel) {
        require(channel.send(DataChannel.Buffer(ByteBuffer.wrap(TransferProtocolV3.encodeReady()), true))) {
            "DataChannel READY 发送失败"
        }
    }

    private fun sendRetry(channel: DataChannel, fileIndex: Int, chunkIndex: Int) {
        require(channel.send(DataChannel.Buffer(ByteBuffer.wrap(TransferProtocolV3.encodeRetry(fileIndex, chunkIndex)), true))) {
            "DataChannel RETRY 发送失败"
        }
    }
}

private object NoopPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
}

private class BlockingSdpObserver : SdpObserver {
    private val latch = CountDownLatch(1)
    private var description: SessionDescription? = null
    private var error: String? = null

    override fun onCreateSuccess(description: SessionDescription) {
        this.description = description
        latch.countDown()
    }

    override fun onSetSuccess() {
        latch.countDown()
    }

    override fun onCreateFailure(error: String) {
        this.error = error
        latch.countDown()
    }

    override fun onSetFailure(error: String) {
        this.error = error
        latch.countDown()
    }

    fun awaitDescription(): SessionDescription {
        check(latch.await(10, TimeUnit.SECONDS)) { "WebRTC SDP 创建超时" }
        error?.let { throw IllegalStateException(it) }
        return requireNotNull(description) { "WebRTC SDP 创建失败" }
    }

    fun awaitSet() {
        check(latch.await(10, TimeUnit.SECONDS)) { "WebRTC SDP 设置超时" }
        error?.let { throw IllegalStateException(it) }
    }
}

private data class ChunkKey(val fileIndex: Int, val chunkIndex: Int)

private data class P2PSendSession(
    val config: TransferSessionConfig,
    val ephemeral: TransferV3EphemeralKeyPair,
    val senderX25519PrivatePkcs8B64: String,
    val receiverEd25519PubB64: String,
    val receiverX25519PubB64: String,
    val manifestHashB64: String,
) {
    @Volatile
    var sessionKey: ByteArray = ByteArray(0)
}

private data class P2PPeerHandshake(
    val ephemeralPublicB64: String,
    val acceptSignatureB64: String,
    val completedChunks: Map<Int, Set<Int>>,
)

private fun handleSenderControlFrame(
    sessionKey: ByteArray,
    sessionId: String,
    transferId: String,
    bytes: ByteArray,
    channel: DataChannel,
    sentFrames: Map<ChunkKey, ByteArray>,
    ackedChunks: MutableSet<ChunkKey>,
    ackLatch: CountDownLatch,
    receiverReadyLatch: CountDownLatch,
) {
    when (
        val frame = runCatching {
            TransferProtocolV3.decodeFrame(sessionKey, sessionId, transferId, bytes)
        }.getOrNull()
    ) {
        is TransferV3Frame.Ready -> receiverReadyLatch.countDown()
        is TransferV3Frame.Ack -> {
            if (ackedChunks.add(ChunkKey(frame.fileIndex, frame.chunkIndex))) {
                ackLatch.countDown()
            }
        }
        is TransferV3Frame.Retry -> {
            sentFrames[ChunkKey(frame.fileIndex, frame.chunkIndex)]?.let { chunkFrame ->
                require(channel.send(DataChannel.Buffer(ByteBuffer.wrap(chunkFrame), true))) {
                    "DataChannel RETRY 重传失败"
                }
            }
        }
        else -> Unit
    }
}

private fun ContentResolver.createDownloadUri(file: TransferV3File): Uri {
    return requireNotNull(
        insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, file.fileType.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Piko")
            },
        ),
    ) { "无法创建接收文件 ${file.displayName}" }
}

private fun TransferV3File.toReceiveHistoryFile(savedUri: String?): ReceiveHistoryFile =
    ReceiveHistoryFile(
        displayName = displayName,
        fileType = fileType.toReceiveFileType(),
        sizeBytes = sizeBytes,
        thumbnailBytes = null,
        savedUri = savedUri,
    )

private val SendFileType.mimeType: String
    get() = when (this) {
        SendFileType.Image -> "image/*"
        SendFileType.Document -> "application/octet-stream"
        SendFileType.Spreadsheet -> "application/octet-stream"
        SendFileType.Video -> "video/*"
        SendFileType.Archive -> "application/zip"
        SendFileType.Other -> "application/octet-stream"
    }

private fun SendFileType.toReceiveFileType(): ReceiveFileType =
    when (this) {
        SendFileType.Image -> ReceiveFileType.Image
        SendFileType.Document -> ReceiveFileType.Document
        SendFileType.Spreadsheet -> ReceiveFileType.Spreadsheet
        SendFileType.Video -> ReceiveFileType.Video
        SendFileType.Archive -> ReceiveFileType.Archive
        SendFileType.Other -> ReceiveFileType.Other
    }

private fun List<SendTransferItem>.toManifestInputs(contentResolver: ContentResolver): List<TransferV3ManifestInput> =
    map { item ->
        TransferV3ManifestInput(
            displayName = item.displayName,
            fileType = item.fileType,
            sizeBytes = item.sizeBytes,
            fileHash = contentResolver.sha256(Uri.parse(item.sourceUri)),
        )
    }

private fun JSONObject.optIceServers(): List<IceServerConfig> {
    val servers = optJSONArray("ice_servers") ?: return defaultP2PIceServers()
    return (0 until servers.length()).mapNotNull { index ->
        val server = servers.optJSONObject(index) ?: return@mapNotNull null
        server.optString("urls").takeIf { it.isNotBlank() }?.let(::IceServerConfig)
    }.ifEmpty { defaultP2PIceServers() }
}

private fun List<TransferV3ManifestInput>.manifestHashB64(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEachIndexed { index, item ->
        digest.update(index.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(item.displayName.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(item.fileType.name.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(item.sizeBytes.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(item.fileHash)
        digest.update(0.toByte())
    }
    return digest.digest().base64()
}

private fun ContentResolver.sha256(uri: Uri): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    requireNotNull(openInputStream(uri)) { "无法读取 $uri" }.use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

private fun sha256File(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun com.piko.app.domain.AccountError.messageOrCode(): String = when (this) {
    com.piko.app.domain.AccountError.Network -> "网络不可用"
    com.piko.app.domain.AccountError.SessionExpired -> "登录已过期"
    is com.piko.app.domain.AccountError.Server -> "${message.ifBlank { "服务端错误" }}（$code）"
    else -> "跨网传输会话创建失败"
}
