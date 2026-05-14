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
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val P2P_MAX_IN_FLIGHT_CHUNKS = 8
private const val P2P_INITIAL_OPEN_TIMEOUT_SECONDS = 30L
private const val P2P_RESTART_OPEN_TIMEOUT_SECONDS = 45L

data class P2PTransferDiagnostic(
    val offerSent: Boolean = false,
    val answerReceived: Boolean = false,
    val localIceCount: Int = 0,
    val remoteIceCount: Int = 0,
    val iceServerUrls: String = "unknown",
    val localCandidateTypes: String = "none",
    val remoteCandidateTypes: String = "none",
    val localCandidateDetails: String = "none",
    val remoteCandidateDetails: String = "none",
    val iceConnectionState: String = "unknown",
    val peerConnectionState: String = "unknown",
    val iceGatheringState: String = "unknown",
    val signalingState: String = "unknown",
    val dataChannelState: String = "unknown",
    val iceCandidateErrors: String = "none",
    val selectedCandidatePair: String = "none",
    val iceCandidatePairStats: String = "none",
    val stunErrorRate: Double = 0.0,
    val gatheringIncomplete: Boolean = false,
    val symmetricNatSuspect: Boolean = false,
    val remoteOnlyMdns: Boolean = false,
    val failureReason: P2PFailureReason? = null,
)

enum class P2PFailureReason(
    val title: String,
    val body: String,
    val suggestion: String,
) {
    LOCAL_NO_CANDIDATE(
        title = "本机未能获取任何网络候选",
        body = "当前设备未能枚举出可用的网络地址，无法发起 P2P 连接。",
        suggestion = "请检查本机网络连接（Wi-Fi/移动数据），关闭可能拦截 UDP 的 VPN/代理后重试。",
    ),
    REMOTE_NO_CANDIDATE(
        title = "对方未能获取任何网络候选",
        body = "接收方设备未能枚举出可用的网络地址，无法接受 P2P 连接。",
        suggestion = "请确认对方处于联网状态，且未启用阻止 UDP 流量的 VPN/代理。",
    ),
    STUN_SERVERS_DEGRADED(
        title = "网络环境无法访问公共 STUN 服务",
        body = "多个 STUN 服务器解析或绑定失败，候选地址发现严重受阻。常见原因：DNS 劫持、UDP 拦截或 53/3478 等端口被防火墙阻断。",
        suggestion = "请尝试切换到其他网络（如个人热点或不同的 Wi-Fi），或与网络管理员确认 UDP 出站是否被拦截。",
    ),
    SYMMETRIC_NAT(
        title = "运营商 NAT 端口预测失败",
        body = "检测到一方的 NAT 对同一内网端口分配了多个不同的公网端口（对称 NAT），跨网直连无法穿透。",
        suggestion = "请尝试让双方接入同一 Wi-Fi，或切换到 5G/4G 个人热点后重试。",
    ),
    REMOTE_MDNS_ONLY(
        title = "对端处于本地网络保护模式",
        body = "接收方仅暴露 mDNS 主机名，跨网无法解析。常见原因：iOS 未授予本地网络权限或对方处于强隐私模式。",
        suggestion = "请提示对方在系统设置中授予「本地网络」权限，或切换到同一 Wi-Fi 后重试。",
    ),
    CONNECTIVITY_CHECK_FAILED(
        title = "候选连通性检查全部失败",
        body = "ICE 仍在收集候选时，已知候选对的连通性检查全部失败，双方网络可能互相不可达。",
        suggestion = "请确认两端的网络环境均允许出站 UDP，或切换到同一 Wi-Fi 后重试。",
    ),
    CGNAT_BOTH_SIDES(
        title = "双方均处于运营商级 NAT 后方",
        body = "双方网络均处于 CGNAT 后方，公网地址不可控，纯直连穿透在该网络组合下不可行。",
        suggestion = "请尝试让双方接入同一 Wi-Fi，或切换到家庭宽带/办公网络后重试。",
    ),
    NAT_INCOMPATIBLE(
        title = "双方 NAT 类型不兼容",
        body = "双方均只获取到 host/srflx 候选，跨 NAT 直连穿透未成功。",
        suggestion = "请尝试让双方接入同一 Wi-Fi 后重试。",
    ),
    GENERIC_TIMEOUT(
        title = "跨网直连超时",
        body = "ICE 在等待窗口内未能完成连接握手，原因不明。",
        suggestion = "请稍后重试，或切换到同一 Wi-Fi 环境。",
    ),
}

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
        val sentFrames = ConcurrentHashMap<ChunkKey, ByteArray>()
        val ackedChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
        val chunkByteCounts = ConcurrentHashMap<ChunkKey, Long>()
        val confirmedBytes = AtomicLong(0L)
        val inFlightPermits = Semaphore(P2P_MAX_IN_FLIGHT_CHUNKS)
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
                    chunkByteCounts = chunkByteCounts,
                    ackedChunks = ackedChunks,
                    ackLatch = ackLatch,
                    receiverReadyLatch = receiverReadyLatch,
                    inFlightPermits = inFlightPermits,
                    confirmedBytes = confirmedBytes,
                    totalCompletedBeforeTarget = totalCompletedBeforeTarget,
                    totalBytes = totalBytes,
                    callback = callback,
                )
            },
        )
        peers[session.sessionId] = peer
        peer.setAbortCallback {
            inFlightPermits.release(P2P_MAX_IN_FLIGHT_CHUNKS)
            while (ackLatch.count > 0) ackLatch.countDown()
            receiverReadyLatch.countDown()
        }
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
        if (!peer.awaitOpen(P2P_INITIAL_OPEN_TIMEOUT_SECONDS)) {
            if (peer.isAborted) {
                peer.close()
                peers.remove(session.sessionId)
                throw P2PTransferFailure(
                    stage = "data_channel_open",
                    transferId = transferId,
                    sessionId = session.sessionId,
                    originalReason = "对方已取消接收",
                    diagnostic = peer.diagnosticSnapshot(),
                )
            }
            peer.triggerIceRestart()
            if (!peer.awaitOpen(P2P_RESTART_OPEN_TIMEOUT_SECONDS)) {
                val baseDiag = peer.diagnosticSnapshot()
                val reason = crossNetworkDiagnosis(baseDiag)
                val diag = baseDiag.copy(failureReason = reason)
                peer.close()
                peers.remove(session.sessionId)
                throw P2PTransferFailure(
                    stage = "data_channel_open",
                    transferId = transferId,
                    sessionId = session.sessionId,
                    originalReason = reason.body,
                    diagnostic = diag,
                )
            }
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
        if (!receiverReadyLatch.await(120, TimeUnit.SECONDS) || peer.isAborted) {
            val abortedByPeer = peer.isAborted
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "receiver_ready",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = if (abortedByPeer) "对方已取消接收" else "P2P_RECEIVER_READY_TIMEOUT：等待接收端确认超时",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
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
                        chunkByteCounts[chunkKey] = read.toLong()
                        if (peerCompletedChunks[fileIndex]?.contains(chunkIndex) == true) {
                            if (ackedChunks.add(chunkKey)) {
                                ackLatch.countDown()
                                val completed = confirmedBytes.addAndGet(read.toLong())
                                callback(
                                    SendTransferEvent.Progress(
                                        transferId = transferId,
                                        completedBytes = (totalCompletedBeforeTarget + completed).coerceAtMost(totalBytes),
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                            chunkIndex += 1
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
                        if (!inFlightPermits.tryAcquire(30, TimeUnit.SECONDS) || peer.isAborted) {
                            val abortedByPeer = peer.isAborted
                            peer.close()
                            peers.remove(session.sessionId)
                            throw P2PTransferFailure(
                                stage = "ack",
                                transferId = transferId,
                                sessionId = session.sessionId,
                                originalReason = if (abortedByPeer) "对方已取消接收" else "P2P_ACK_TIMEOUT：跨网传输确认超时",
                                diagnostic = peer.diagnosticSnapshot(),
                            )
                        }
                        runCatching {
                            sendBinary(channel, chunkFrame)
                        }.getOrElse { error ->
                            inFlightPermits.release()
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
                        chunkIndex += 1
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
        if (totalChunks > 0 && (!ackLatch.await(30, TimeUnit.SECONDS) || peer.isAborted)) {
            val abortedByPeer = peer.isAborted
            peer.close()
            peers.remove(session.sessionId)
            throw P2PTransferFailure(
                stage = "ack",
                transferId = transferId,
                sessionId = session.sessionId,
                originalReason = if (abortedByPeer) "对方已取消接收" else "P2P_ACK_TIMEOUT：跨网传输确认超时",
                diagnostic = peer.diagnosticSnapshot(),
            )
        }
        channel.close()
        peer.close()
        peers.remove(session.sessionId)
        kotlinx.coroutines.runBlocking {
            tokenStore.load()?.let { token -> sessionsApi.finishSession(token, session.sessionId) }
        }
        return confirmedBytes.get()
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
        val receiver = receiversByTransferId.remove(transferId) ?: return
        val sessionId = receiver.sessionId
        receiver.cancel()
        pendingSignalsBySessionId.remove(sessionId)
        peers.remove(sessionId)?.close()
        signalingClient.send(
            JSONObject()
                .put("type", "bye")
                .put("session_id", sessionId)
                .put("reason", "receiver_canceled"),
        )
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
        @Volatile
        private var openLatch = CountDownLatch(1)
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
        private var iceServerUrls = iceServers.joinToString(",") { it.urls }.ifBlank { "none" }
        private val localCandidateTypes = ConcurrentHashMap.newKeySet<String>()
        private val remoteCandidateTypes = ConcurrentHashMap.newKeySet<String>()
        private val localCandidateDetails = ConcurrentHashMap.newKeySet<String>()
        private val remoteCandidateDetails = ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var iceConnectionState = "unknown"
        @Volatile
        private var peerConnectionState = "unknown"
        @Volatile
        private var iceGatheringState = "unknown"
        @Volatile
        private var signalingState = "unknown"
        @Volatile
        private var dataChannelState = "unknown"
        private val iceCandidateErrors = ConcurrentLinkedQueue<String>()
        @Volatile
        private var selectedCandidatePair = "none"
        @Volatile
        private var iceCandidatePairStats = "none"
        private var aborted = false
        @Volatile
        private var abortCallback: (() -> Unit)? = null
        private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
        private val peerConnection: PeerConnection = requireNotNull(
            factory.createPeerConnection(
                PeerConnection.RTCConfiguration(
                    iceServers.map { PeerConnection.IceServer.builder(it.urls).createIceServer() },
                ).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    iceCandidatePoolSize = 1
                },
                object : PeerConnection.Observer by NoopPeerObserver {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {
                        signalingState = state.name
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        iceConnectionState = state.name
                    }

                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                        peerConnectionState = state.name
                    }

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                        iceGatheringState = state.name
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        localIceCount += 1
                        localCandidateTypes.add(candidate.sdp.iceCandidateType())
                        localCandidateDetails.add(candidate.sdp.iceCandidateSummary())
                        signalingClient.send(
                            JSONObject()
                                .put("type", "ice_candidate")
                                .put("session_id", sessionId)
                                .put("candidate", candidate.sdp)
                                .put("candidate_type", candidate.sdp.iceCandidateType())
                                .put("sdp_mid", candidate.sdpMid)
                                .put("sdp_m_line_index", candidate.sdpMLineIndex),
                        )
                    }

                    override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                        iceCandidateErrors.add(
                            listOf(
                                "url=${event.url}",
                                "address=${event.address}:${event.port}",
                                "code=${event.errorCode}",
                                "text=${event.errorText}",
                            ).joinToString("|"),
                        )
                    }

                    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                        selectedCandidatePair = listOf(
                            "local=${event.local.sdp.iceCandidateType()}",
                            "remote=${event.remote.sdp.iceCandidateType()}",
                            "local_detail=${event.local.sdp.iceCandidateSummary()}",
                            "remote_detail=${event.remote.sdp.iceCandidateSummary()}",
                            "reason=${event.reason}",
                        ).joinToString("|")
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
            remoteCandidateTypes.add(message.optString("candidate_type").ifBlank { message.getString("candidate").iceCandidateType() })
            remoteCandidateDetails.add(message.getString("candidate").iceCandidateSummary())
            if (!hasRemoteDescription) {
                pendingIceCandidates.add(candidate)
                if (hasRemoteDescription) {
                    flushPendingIceCandidates()
                }
                return
            }
            peerConnection.addIceCandidate(candidate)
        }

        fun awaitOpen(seconds: Long): Boolean {
            val opened = openLatch.await(seconds, TimeUnit.SECONDS)
            return opened && !aborted
        }

        fun triggerIceRestart() {
            openLatch = CountDownLatch(1)
            peerConnection.restartIce()
            val offer = createSdp { observer -> peerConnection.createOffer(observer, MediaConstraints()) }
            setLocal(offer)
            signalingClient.send(JSONObject().put("type", "offer").put("session_id", sessionId).put("sdp", offer.description))
        }

        fun awaitPeerHandshake(): P2PPeerHandshake {
            check(peerEphemeralLatch.await(10, TimeUnit.SECONDS)) { "接收方临时公钥等待超时" }
            check(!aborted) { "对方已取消接收" }
            return P2PPeerHandshake(
                ephemeralPublicB64 = requireNotNull(peerEphemeralPublicB64) { "接收方临时公钥缺失" },
                acceptSignatureB64 = requireNotNull(peerAcceptSignatureB64) { "接收方签名缺失" },
                completedChunks = TransferProgressStore.decodeCompletedBitmap(peerCompletedBitmapB64),
            )
        }

        val isAborted: Boolean
            get() = aborted

        fun setAbortCallback(callback: () -> Unit) {
            abortCallback = callback
            if (aborted) callback()
        }

        fun diagnosticSnapshot(): P2PTransferDiagnostic = P2PTransferDiagnostic(
            offerSent = offerSent,
            answerReceived = answerReceived,
            localIceCount = localIceCount,
            remoteIceCount = remoteIceCount,
            iceServerUrls = iceServerUrls,
            localCandidateTypes = localCandidateTypes.candidateTypesDescription(),
            remoteCandidateTypes = remoteCandidateTypes.candidateTypesDescription(),
            localCandidateDetails = localCandidateDetails.candidateDetailsDescription(),
            remoteCandidateDetails = remoteCandidateDetails.candidateDetailsDescription(),
            iceConnectionState = iceConnectionState,
            peerConnectionState = peerConnectionState,
            iceGatheringState = iceGatheringState,
            signalingState = signalingState,
            dataChannelState = dataChannelState,
            iceCandidateErrors = iceCandidateErrors.joinToString(";").ifBlank { "none" },
            selectedCandidatePair = selectedCandidatePair,
            iceCandidatePairStats = refreshIceCandidatePairStats(),
            stunErrorRate = computeStunErrorRate(iceServerUrls, iceCandidateErrors.joinToString(";").ifBlank { "none" }),
            gatheringIncomplete = isGatheringIncomplete(iceGatheringState),
            symmetricNatSuspect =
                detectSymmetricNatSuspect(localCandidateDetails.candidateDetailsDescription()) ||
                detectSymmetricNatSuspect(remoteCandidateDetails.candidateDetailsDescription()),
            remoteOnlyMdns = detectRemoteOnlyMdns(
                remoteCandidateTypes.candidateTypesDescription(),
                remoteCandidateDetails.candidateDetailsDescription(),
            ),
        )

        private fun refreshIceCandidatePairStats(): String {
            val latch = CountDownLatch(1)
            val result = AtomicReference("stats_timeout")
            runCatching {
                peerConnection.getStats { report ->
                    result.set(report.iceCandidatePairStatsDescription())
                    latch.countDown()
                }
                if (!latch.await(700, TimeUnit.MILLISECONDS)) {
                    result.set("stats_timeout")
                }
            }.getOrElse { error ->
                result.set("stats_error=${error::class.java.simpleName}")
            }
            iceCandidatePairStats = result.get()
            return iceCandidatePairStats
        }

        fun close() {
            aborted = true
            openLatch.countDown()
            peerEphemeralLatch.countDown()
            abortCallback?.invoke()
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
    val sessionId: String,
    val transferId: String,
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
    private var canceled = false
    private var activeChannel: DataChannel? = null
    private var readySent = false
    private var didComplete = false

    fun attach(sessionId: String, channel: DataChannel) {
        activeChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) maybeSendReadyAndDrainPending()
                if (channel.state() == DataChannel.State.CLOSED) finishIfComplete(channel)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handle(bytes, channel)
            }
        })
        maybeSendReadyAndDrainPending()
    }

    private fun handle(bytes: ByteArray, channel: DataChannel) {
        if (canceled) return
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
                confirmed = confirmed || autoAccept
                onReceiveTransferEvent(
                    ReceiveTransferEvent.Started(
                        transferId = transferId,
                        senderName = senderName,
                        files = manifest.map { it.toReceiveHistoryFile(null) },
                        totalBytes = totalBytes,
                        requiresConfirmation = !confirmed,
                    ),
                )
                maybeSendReadyAndDrainPending()
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
        if (confirmed || canceled) return
        confirmed = true
        val manifestReady = manifest.isNotEmpty()
        onReceiveTransferEvent(
            ReceiveTransferEvent.Started(
                transferId = transferId,
                senderName = if (manifestReady) senderName else "",
                files = if (manifestReady) manifest.map { it.toReceiveHistoryFile(null) } else emptyList(),
                totalBytes = totalBytes,
                requiresConfirmation = false,
            ),
        )
        maybeSendReadyAndDrainPending()
    }

    fun cancel() {
        canceled = true
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

    private fun maybeSendReadyAndDrainPending() {
        val channel = activeChannel ?: return
        if (!confirmed || manifest.isEmpty() || readySent || channel.state() != DataChannel.State.OPEN) return
        sendReady(channel)
        readySent = true
        val pending = pendingChunkFrames.toList()
        pendingChunkFrames.clear()
        pending.forEach { (bytes, c) -> handle(bytes, c) }
    }
}

private object NoopPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit
    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
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
    chunkByteCounts: Map<ChunkKey, Long>,
    ackedChunks: MutableSet<ChunkKey>,
    ackLatch: CountDownLatch,
    receiverReadyLatch: CountDownLatch,
    inFlightPermits: Semaphore,
    confirmedBytes: AtomicLong,
    totalCompletedBeforeTarget: Long,
    totalBytes: Long,
    callback: (SendTransferEvent) -> Unit,
) {
    when (
        val frame = runCatching {
            TransferProtocolV3.decodeFrame(sessionKey, sessionId, transferId, bytes)
        }.getOrNull()
    ) {
        is TransferV3Frame.Ready -> receiverReadyLatch.countDown()
        is TransferV3Frame.Ack -> {
            val chunkKey = ChunkKey(frame.fileIndex, frame.chunkIndex)
            if (ackedChunks.add(chunkKey)) {
                ackLatch.countDown()
                inFlightPermits.release()
                val completed = confirmedBytes.addAndGet(chunkByteCounts[chunkKey] ?: 0L)
                callback(
                    SendTransferEvent.Progress(
                        transferId = transferId,
                        completedBytes = (totalCompletedBeforeTarget + completed).coerceAtMost(totalBytes),
                        totalBytes = totalBytes,
                    ),
                )
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

private fun String.iceCandidateType(): String =
    Regex("""\btyp\s+([A-Za-z0-9_-]+)""").find(this)?.groupValues?.get(1)?.lowercase() ?: "unknown"

private fun String.iceCandidateSummary(): String {
    val parts = trim().split(Regex("""\s+"""))
    val protocol = parts.getOrNull(2)?.lowercase()?.ifBlank { null } ?: "unknown"
    val address = parts.getOrNull(4)?.iceAddressKind() ?: "unknown"
    val port = parts.getOrNull(5)?.ifBlank { null } ?: "unknown"
    val relatedAddress = Regex("""\braddr\s+(\S+)""").find(this)?.groupValues?.getOrNull(1)?.iceAddressKind()
    val relatedPort = Regex("""\brport\s+(\S+)""").find(this)?.groupValues?.getOrNull(1)
    return buildList {
        add("type=${iceCandidateType()}")
        add("proto=$protocol")
        add("addr=$address")
        add("port=$port")
        if (!relatedAddress.isNullOrBlank()) add("raddr=$relatedAddress")
        if (!relatedPort.isNullOrBlank()) add("rport=$relatedPort")
    }.joinToString("/")
}

private fun String.iceAddressKind(): String {
    val value = lowercase()
    if (value.endsWith(".local")) return "mdns"
    if (":" in value) {
        return when {
            value.startsWith("fd") || value.startsWith("fc") || value.startsWith("fe80") -> "private-ipv6"
            else -> "ipv6"
        }
    }
    val octets = value.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return "unknown"
    val first = octets[0]
    val second = octets[1]
    return when {
        first == 10 -> "private-ipv4"
        first == 172 && second in 16..31 -> "private-ipv4"
        first == 192 && second == 168 -> "private-ipv4"
        first == 169 && second == 254 -> "link-local-ipv4"
        first == 100 && second in 64..127 -> "cgnat-ipv4"
        first == 127 -> "loopback-ipv4"
        else -> "public-ipv4"
    }
}

internal fun crossNetworkDiagnosis(diag: P2PTransferDiagnostic): P2PFailureReason {
    val localTypes = diag.localCandidateTypes.split(",").map { it.trim() }.toSet()
    val remoteTypes = diag.remoteCandidateTypes.split(",").map { it.trim() }.toSet()
    val hasRelay = "relay" in localTypes || "relay" in remoteTypes
    val onlySrflxAndHost = !hasRelay &&
        localTypes.all { it in setOf("host", "srflx", "none") } &&
        remoteTypes.all { it in setOf("host", "srflx", "none") }
    val hasCgnat = diag.localCandidateDetails.contains("cgnat") ||
        diag.remoteCandidateDetails.contains("cgnat")
    return when {
        diag.localCandidateTypes == "none" -> P2PFailureReason.LOCAL_NO_CANDIDATE
        diag.remoteCandidateTypes == "none" -> P2PFailureReason.REMOTE_NO_CANDIDATE
        diag.stunErrorRate >= 0.5 -> P2PFailureReason.STUN_SERVERS_DEGRADED
        diag.symmetricNatSuspect -> P2PFailureReason.SYMMETRIC_NAT
        diag.remoteOnlyMdns -> P2PFailureReason.REMOTE_MDNS_ONLY
        diag.gatheringIncomplete && diag.selectedCandidatePair == "none" ->
            P2PFailureReason.CONNECTIVITY_CHECK_FAILED
        onlySrflxAndHost && hasCgnat -> P2PFailureReason.CGNAT_BOTH_SIDES
        onlySrflxAndHost -> P2PFailureReason.NAT_INCOMPATIBLE
        else -> P2PFailureReason.GENERIC_TIMEOUT
    }
}

internal fun computeStunErrorRate(iceServerUrls: String, iceCandidateErrors: String): Double {
    val servers = iceServerUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (servers.isEmpty()) return 0.0
    if (iceCandidateErrors.isBlank() || iceCandidateErrors == "none") return 0.0
    val urlRegex = Regex("""url=([^|]+)""")
    val erroredUrls = iceCandidateErrors.split(";")
        .mapNotNull { urlRegex.find(it)?.groupValues?.get(1)?.trim()?.takeIf { url -> url.isNotBlank() } }
        .toSet()
    val degraded = servers.count { it in erroredUrls }
    return degraded.toDouble() / servers.size
}

internal fun isGatheringIncomplete(iceGatheringState: String): Boolean =
    !iceGatheringState.equals("COMPLETE", ignoreCase = true)

internal fun detectSymmetricNatSuspect(details: String): Boolean {
    if (details.isBlank() || details == "none") return false
    val portRegex = Regex("""(?:^|/)port=(\d+)""")
    val addrRegex = Regex("""(?:^|/)addr=([^/]+)""")
    val rportRegex = Regex("""rport=(\d+)""")
    val mappings = mutableMapOf<Pair<String, String>, MutableSet<String>>()
    for (entry in details.split(";")) {
        if (!entry.contains("type=srflx")) continue
        val addr = addrRegex.find(entry)?.groupValues?.get(1) ?: continue
        val port = portRegex.find(entry)?.groupValues?.get(1) ?: continue
        val rport = rportRegex.find(entry)?.groupValues?.get(1) ?: continue
        if (rport == "0") continue
        mappings.getOrPut(addr to rport) { mutableSetOf() }.add(port)
    }
    return mappings.any { it.value.size >= 2 }
}

internal fun detectRemoteOnlyMdns(remoteTypes: String, remoteDetails: String): Boolean {
    val types = remoteTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (types != setOf("host")) return false
    if (remoteDetails.isBlank() || remoteDetails == "none") return false
    val entries = remoteDetails.split(";").filter { it.contains("type=host") }
    if (entries.isEmpty()) return false
    return entries.all { it.contains("addr=mdns") }
}

private fun Set<String>.candidateTypesDescription(): String =
    filter { it.isNotBlank() }.sorted().joinToString(",").ifBlank { "none" }

private fun Set<String>.candidateDetailsDescription(): String =
    filter { it.isNotBlank() }.sorted().take(12).joinToString(";").ifBlank { "none" }

private fun RTCStatsReport.iceCandidatePairStatsDescription(): String {
    val stats = statsMap
    val candidateStats = stats.values
        .filter { it.type == "local-candidate" || it.type == "remote-candidate" }
        .associateBy { it.id }
    return stats.values
        .filter { it.type == "candidate-pair" }
        .sortedWith(
            compareByDescending<RTCStats> { it.members["selected"] == true }
                .thenByDescending { it.members["nominated"] == true }
                .thenBy { it.members["state"]?.toString().orEmpty() },
        )
        .take(12)
        .map { pair ->
            val members = pair.members
            val local = members["localCandidateId"]?.toString()?.let { candidateStats[it]?.candidateStatsSummary() } ?: "unknown"
            val remote = members["remoteCandidateId"]?.toString()?.let { candidateStats[it]?.candidateStatsSummary() } ?: "unknown"
            listOf(
                "id=${pair.id}",
                "state=${members["state"] ?: "unknown"}",
                "nominated=${members["nominated"] ?: "unknown"}",
                "selected=${members["selected"] ?: "unknown"}",
                "writable=${members["writable"] ?: "unknown"}",
                "rtt=${members["currentRoundTripTime"] ?: "unknown"}",
                "sent=${members["bytesSent"] ?: "unknown"}",
                "recv=${members["bytesReceived"] ?: "unknown"}",
                "local=$local",
                "remote=$remote",
            ).joinToString("|")
        }
        .joinToString(";")
        .ifBlank { "none" }
}

private fun RTCStats.candidateStatsSummary(): String {
    val members = members
    val address = members["address"]?.toString()
        ?: members["ip"]?.toString()
        ?: members["relatedAddress"]?.toString()
        ?: "unknown"
    return listOf(
        "type=${members["candidateType"] ?: "unknown"}",
        "proto=${members["protocol"] ?: "unknown"}",
        "addr=${address.iceAddressKind()}",
        "port=${members["port"] ?: "unknown"}",
        "network=${members["networkType"] ?: "unknown"}",
    ).joinToString("/")
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
