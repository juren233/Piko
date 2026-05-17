package com.piko.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAndroidUiParityTest {
    private val rootDir: File
        get() = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun iosContentUsesAndroidPageStructureAndCopy() {
        val sendView = readIos("NativeSendView.swift")
        val settingsView = readIos("NativeSettingsView.swift")
        val receiveView = readIos("NativeReceiveView.swift")

        listOf(sendView, settingsView).forEach { source ->
            assertFalse("NavigationView" in source)
            assertFalse("pageGradient" in source)
        }
        assertFalse("NavigationView" in receiveView)
        assertFalse("pageGradient" in receiveView)

        assertInOrder(sendView, "我的设备", "局域网设备", "我的好友", "Section(\"图片/视频\")", "Section(\"文件\")")
        assertInOrder(
            settingsView,
            "传输",
            "自动接收",
            "传输策略",
            "NativeAuthLabels.accountSectionTitle",
            "登录方式",
            "NativeAuthLabels.username",
            "NativeAuthLabels.nickname",
        )
        assertFalse("最近接收" in receiveView)
        assertTrue(".navigationTitle(\"Piko\")" in receiveView)
        assertTrue(".navigationTitle(\"发送\")" in sendView)
        assertTrue(".navigationTitle(\"设置\")" in settingsView)
        assertTrue("NativeReceiveHistoryRow" in receiveView)
    }

    @Test
    fun authLabelsParityAcrossPlatforms() {
        val androidLabels = readAndroid("feature/settings/SettingsRoute.kt")
        val iosLabels = readIos("NativeAuthLabels.swift")
        val expectedLabels = listOf(
            "账号",
            "邮箱",
            "密码",
            "用户名",
            "昵称（可选）",
            "登录",
            "注册",
            "登录 / 注册",
            "退出登录",
            "未设置",
            "邮箱已被注册",
            "用户名已被占用",
            "邮箱或密码错误",
            "网络不可用，请稍后重试",
            "密码至少 8 位",
            "邮箱格式有误",
            "用户名格式不合法",
            "昵称格式不合法",
        )

        expectedLabels.forEach { label ->
            assertTrue(label in androidLabels, "Android auth label missing: $label")
            assertTrue(label in iosLabels, "iOS auth label missing: $label")
        }
    }

    @Test
    fun friendSystemFilesAndEntryPointsAreWiredOnBothPlatforms() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/SendPageState.kt").readText()
        val androidHomeState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/PikoHomeState.kt").readText()
        val androidFriendModels = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/FriendModels.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidSettings = readAndroid("feature/settings/SettingsRoute.kt")
        val iosModel = readIos("NativePikoModel.swift")
        val iosSettings = readIos("NativeSettingsView.swift")
        val iosRoot = readIos("PikoRootView.swift")
        val iosLabels = readIos("NativeAuthLabels.swift")
        val iosFriendModels = readIos("NativeFriendModels.swift")
        val iosFriendApi = readIos("NativeFriendApiClient.swift")
        val iosFriendStore = readIos("NativeFriendStore.swift")
        val iosFriendsView = readIos("NativeFriendsView.swift")
        val iosFriendRequestsView = readIos("NativeFriendRequestsView.swift")
        val iosPresenceTicker = readIos("NativePresenceTicker.swift")
        val iosProject = File(rootDir, "ios/Piko.xcodeproj/project.pbxproj").readText()

        assertTrue("data class FriendsState" in androidFriendModels)
        assertTrue("fun replaceFriendDevices(devices: List<FriendDevice>)" in androidState)
        assertFalse("friend-demo-cavan" in androidState)
        assertTrue("friendsState = FriendsState.Empty" in androidHomeState)
        assertTrue("friendsRepository.refreshAll()" in androidApp)
        assertTrue("FriendsEntryRow" in androidSettings)
        assertTrue("friendsEntry" in androidSettings)

        assertTrue("let friendStore: NativeFriendStore" in iosModel)
        assertTrue("NativeFriendApiClient()" in iosModel)
        assertTrue("NativePresenceTicker" in iosModel)
        assertTrue("@MainActor\n    private lazy var p2pTransferClient" in iosModel)
        assertTrue("@MainActor\n    private var selectedSendTargets" in iosModel)
        assertTrue("@MainActor\n    var friendDevices: [NativeSendDevice]" in iosModel)
        assertTrue("friendStore.friendDevices.values.flatMap" in iosModel)
        assertTrue("@MainActor\n    func startPresence()" in iosModel)
        assertInOrder(
            iosModel,
            "@MainActor\n    func startPresence()",
            "_ = p2pTransferClient",
            "signalingClient.connect(token: token, deviceId: identity.deviceId)",
        )
        assertFalse("friend-laptop" in iosModel)
        assertTrue("NavigationLink" in iosSettings)
        assertTrue("NativeFriendsView" in iosSettings)
        assertTrue("NavigationStack" in iosRoot)
        assertFalse("NavigationView" in iosRoot)
        assertFalse("PikoSettingsNavigationContainer" in iosRoot)
        assertTrue("friendsEntry" in iosLabels)

        assertTrue("struct NativeFriendUser" in iosFriendModels)
        assertTrue("enum NativeFriendRelationship" in iosFriendModels)
        assertTrue("/v1/friends/requests" in iosFriendApi)
        assertTrue("func refreshAll()" in iosFriendStore)
        assertTrue("func search(query:" in iosFriendStore)
        assertTrue("List {" in iosFriendsView)
        assertTrue(".navigationTitle(NativeAuthLabels.friendsEntry)" in iosFriendsView)
        assertFalse("PikoCollapsingPageHeroHeader" in iosFriendsView)
        assertTrue("NativeFriendRequestsView" in iosFriendRequestsView)
        assertFalse("DispatchSourceTimer" in iosPresenceTicker)
        listOf(
            "NativeFriendModels.swift",
            "NativeFriendApiClient.swift",
            "NativeFriendStore.swift",
            "NativePresenceTicker.swift",
            "NativeFriendsView.swift",
            "NativeFriendRequestsView.swift",
            "NativeDeviceIdentity.swift",
            "NativeDeviceApiClient.swift",
            "NativeTransferSessionApiClient.swift",
            "NativeP2PTransferClient.swift",
            "NativeSignalingClient.swift",
            "NativeWebRTCEngine.swift",
            "NativeTransferProtocolV3.swift",
        ).forEach { fileName ->
            assertTrue(fileName in iosProject, "$fileName must be included in the Xcode project")
        }
        val androidSignaling = readAndroid("transport/SignalingWebSocketClient.kt")
        val androidSessionApi = readAndroid("transport/TransferSessionApiClient.kt")
        val androidP2P = readAndroid("transport/P2PTransferClient.kt")
        val androidProgressStore = readAndroid("data/TransferProgressStore.kt")
        val androidTransferV3 = readAndroid("domain/TransferProtocolV3.kt")
        val androidSendActions = readAndroid("platform/AndroidSendPlatformActions.kt")
        val androidIdentityStore = readAndroid("data/DeviceIdentityStore.kt")
        val iosSignaling = readIos("NativeSignalingClient.swift")
        val iosSessionApi = readIos("NativeTransferSessionApiClient.swift")
        val iosP2P = readIos("NativeP2PTransferClient.swift")
        val iosWebRTC = readIos("NativeWebRTCEngine.swift")
        val iosTransferV3 = readIos("NativeTransferProtocolV3.swift")
        val iosTransferModels = readIos("NativeTransferModels.swift")
        val backendIce = File(rootDir, "backend/src/ice.ts").readText()
        val androidBuildGradle = File(rootDir, "android/build.gradle.kts").readText()
        val xquicCmake = File(rootDir, "android/src/main/cpp/CMakeLists.txt").readText()
        val xquicJni = File(rootDir, "android/src/main/cpp/piko_xquic_jni.cpp").readText()
        val iosXquicHeader = File(rootDir, "ios/PikoXQuicBridge.h").readText()
        val iosXquicBridge = File(rootDir, "ios/PikoXQuicBridge.cpp").readText()
        val iosXquicShim = File(rootDir, "ios/PikoXQuicShim.c").readText()
        val iosXquicCmake = File(rootDir, "ios/xquic/CMakeLists.txt").readText()
        val iosBuildScript = File(rootDir, "scripts/ios/build-packages.sh").readText()
        assertTrue(androidSignaling.contains("/v1/signaling/ws?device_id="))
        assertTrue("fun addListener(listener: (JSONObject) -> Unit)" in androidSignaling)
        assertTrue("if (socket !== webSocket) return" in androidSignaling)
        assertTrue("receiveLoop(nextTask)" in iosSignaling)
        assertTrue("self.task === currentTask" in iosSignaling)
        val expectedIceServers = listOf(
            "stun:piko-ipv6.juren233.top:3478",
            "stun:stun.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
        )
        fun assertIceOrder(source: String) {
            var lastIndex = -1
            expectedIceServers.forEach { server ->
                val index = source.indexOf(server)
                assertTrue(index > lastIndex, "$server must appear after previous STUN server")
                lastIndex = index
            }
        }
        assertIceOrder(backendIce)
        assertFalse("turn:" in backendIce)
        assertTrue("fun defaultP2PIceServers()" in androidSessionApi)
        assertTrue("optJSONArray(\"ice_servers\")" in androidSessionApi)
        expectedIceServers.forEach { server ->
            assertTrue("IceServerConfig(\"$server\")" in androidSessionApi)
        }
        assertIceOrder(androidSessionApi)
        assertTrue("static let defaultP2P" in iosSessionApi)
        assertTrue("NativeIceServerConfig.parse(json[\"ice_servers\"] as? [[String: Any]])" in iosSessionApi)
        expectedIceServers.forEach { server ->
            assertTrue("NativeIceServerConfig(urls: \"$server\")" in iosSessionApi)
        }
        assertIceOrder(iosSessionApi)
        assertTrue("PeerConnectionFactory" in androidP2P)
        assertTrue("createDataChannel(\"piko-v3\"" in androidP2P)
        assertTrue("TransferProtocolV3.encodeManifest(" in androidP2P)
        assertTrue("TransferProtocolV3.encodeChunk(" in androidP2P)
        assertTrue("TransferProtocolV3.generateEphemeralKeyPair()" in androidP2P)
        assertTrue("receiver_x25519_eph_pub_b64" in androidP2P)
        assertTrue("sender_invite_signature_b64" in androidP2P)
        assertTrue("receiver_accept_signature_b64" in androidP2P)
        assertTrue("completed_chunks_bitmap_b64" in androidP2P)
        assertTrue("TransferProgressStore.decodeCompletedBitmap" in androidP2P)
        assertTrue("sanitizeCompletedChunks" in androidProgressStore)
        assertTrue("partFile.hasChunk" in androidProgressStore)
        assertTrue("autoAccept = message.optBoolean(\"same_account\", false)" in androidP2P)
        assertTrue("iceServers = message.optIceServers()" in androidP2P)
        assertTrue("iceServers = iceServers" in androidP2P)
        assertTrue("requiresConfirmation = !autoAccept" in androidP2P)
        assertTrue("verifyInviteSignature(" in androidP2P)
        assertTrue("verifyAcceptSignature(" in androidP2P)
        assertTrue("items.toManifestInputs(context.contentResolver)" in androidP2P)
        assertTrue("completedChunks = mutableMapOf<Int, BooleanArray>()" in androidP2P)
        assertTrue("fun acceptReceiveTransfer(transferId: String)" in androidP2P)
        assertTrue("private var readySent = false" in androidP2P)
        assertTrue("maybeSendReadyAndDrainPending()" in androidP2P)
        assertTrue("channel.state() == DataChannel.State.OPEN" in androidP2P)
        assertTrue("P2P_MAX_IN_FLIGHT_CHUNKS" in androidP2P)
        assertTrue("confirmedBytes = AtomicLong(0L)" in androidP2P)
        assertTrue("inFlightPermits.tryAcquire(30, TimeUnit.SECONDS)" in androidP2P)
        assertTrue("TransferProtocolV3.encodeRetry(fileIndex, chunkIndex)" in androidP2P)
        assertTrue("sha256File(tempFile).contentEquals(file.fileHash)" in androidP2P)
        assertFalse("SendTransportPath.P2P -> online &&" in androidState)
        assertFalse("return online &&" in iosModel)
        assertTrue("private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()" in androidP2P)
        assertInOrder(
            androidP2P,
            "fun acceptOffer(sdp: String)",
            "setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))",
            "flushPendingIceCandidates()",
        )
        assertInOrder(
            androidP2P,
            "fun addCandidate(message: JSONObject)",
            "if (!hasRemoteDescription)",
            "pendingIceCandidates.add(candidate)",
        )
        assertTrue("KeyAgreement.getInstance(\"X25519\", curveProvider)" in androidTransferV3)
        assertTrue("hkdfSha256(" in androidTransferV3)
        assertTrue("Signature.getInstance(\"Ed25519\", curveProvider)" in androidTransferV3)
        assertTrue("enum class TransferV3KeyAgreementRole" in androidTransferV3)
        assertTrue("AES/GCM/NoPadding" in androidTransferV3)
        assertTrue("frameReady = 0x02" in androidTransferV3)
        assertTrue("frameAck = 0x04" in androidTransferV3)
        assertTrue("data class TransferV3ManifestInput" in androidTransferV3)
        assertTrue("TransferProtocolV3.generateSigningKeyPair()" in androidIdentityStore)
        assertTrue("TransferProtocolV3.generateAgreementKeyPair()" in androidIdentityStore)
        assertFalse("KeyPairGenerator.getInstance(algorithm).generateKeyPair()" in androidIdentityStore)
        assertTrue("BouncyCastleProvider()" in androidTransferV3)
        assertTrue("KeyPairGenerator.getInstance(algorithm, curveProvider)" in androidTransferV3)
        assertTrue("KeyFactory.getInstance(\"Ed25519\", curveProvider)" in androidTransferV3)
        assertTrue("p2pTransferClient.send(" in androidSendActions)
        assertFalse("WebRTC DataChannel 尚未接入" in androidSendActions)
        assertTrue(iosSignaling.contains("/v1/signaling/ws"))
        assertTrue("NativeWebRTCSession" in iosWebRTC)
        assertTrue("new RTCPeerConnection" in iosWebRTC)
        assertTrue("pc.createDataChannel(\"piko-v3\"" in iosWebRTC)
        assertTrue("iceServers: NativeIceServerConfig.parse(message[\"ice_servers\"] as? [[String: Any]])" in iosP2P)
        assertTrue("iceServers: receiver.iceServers" in iosP2P)
        assertTrue("callAsyncJavaScript" in iosWebRTC)
        assertFalse("evaluateJavaScript" in iosWebRTC)
        assertTrue("return await createOfferer();" in iosWebRTC)
        assertTrue("return await acceptOffer(" in iosWebRTC)
        assertTrue("return await acceptAnswer(" in iosWebRTC)
        assertTrue("return await addCandidate(" in iosWebRTC)
        assertTrue("const pendingIceCandidates = []" in iosWebRTC)
        assertInOrder(
            iosWebRTC,
            "async function acceptAnswer(sdp)",
            "await pc.setRemoteDescription({ type: \"answer\", sdp });",
            "await flushPendingIceCandidates();",
        )
        assertInOrder(
            iosWebRTC,
            "async function addCandidate(candidate, sdpMid, sdpMLineIndex)",
            "if (!pc.remoteDescription)",
            "pendingIceCandidates.push(iceCandidate);",
        )
        assertTrue("NativeTransferProtocolV3.encodeManifest(" in iosP2P)
        assertTrue("NativeTransferProtocolV3.encodeChunk(" in iosP2P)
        assertTrue("NativeTransferProtocolV3.generateEphemeralKeyPair()" in iosP2P)
        assertTrue("private var readySent = false" in iosP2P)
        assertTrue("sendReadyAndDrainPending()" in iosP2P)
        assertTrue("private let maxInFlightChunks = 8" in iosP2P)
        assertTrue("waitForAckedCount(ackTarget, seconds: 30)" in iosP2P)
        assertTrue("confirmedBytes += chunkByteCounts[key] ?? 0" in iosP2P)
        assertTrue("receiver_x25519_eph_pub_b64" in iosP2P)
        assertTrue("sender_invite_signature_b64" in iosP2P)
        assertTrue("receiver_accept_signature_b64" in iosP2P)
        assertTrue("completed_chunks_bitmap_b64" in iosP2P)
        assertTrue("NativeTransferProgressStore.decodeCompletedBitmap" in iosP2P)
        assertTrue("sanitizeCompletedChunks" in iosP2P)
        assertTrue("chunkData(transferId: transferId, fileIndex: index, chunkIndex: chunkIndex)?.count" in iosP2P)
        assertTrue("autoAccept: (message[\"same_account\"] as? Bool) ?? false" in iosP2P)
        assertTrue("publishReceiveState(requiresConfirmation: !autoAccept)" in iosP2P)
        assertTrue("pendingSignalsBySessionId" in androidP2P)
        assertTrue("bufferSignal(sessionId, message)" in androidP2P)
        assertTrue("flushPendingSignals(sessionId, peer)" in androidP2P)
        assertTrue("private var pendingSignals: [String: [[String: Any]]] = [:]" in iosP2P)
        assertTrue("bufferSignal(message, for: sessionId)" in iosP2P)
        assertTrue("flushPendingSignals(for: sessionId)" in iosP2P)
        val androidShell = readAndroid("app/PikoAndroidAppShell.kt")
        assertTrue("ReceiveConfirmDialog(" in androidShell)
        assertTrue("receiveConfirmationMessage" in androidHomeState)
        assertTrue("sendPlatformActions.acceptReceiveTransfer(transferId)" in androidApp)
        assertTrue("ReceiveTransferEvent.Canceled(transferId)" in androidApp)
        assertTrue(".alert(\"确认接收\"" in iosRoot)
        assertTrue("receiveConfirmationPresented" in iosRoot)
        assertTrue("model.acceptReceiveTransfer()" in iosRoot)
        assertTrue("model.cancelReceiveTransfer()" in iosRoot)
        assertTrue("receiveConfirmationMessage" in iosTransferModels)
        assertTrue("TransferProtocolV3.encodeReady()" in androidP2P)
        assertTrue("NativeTransferProtocolV3.encodeReady()" in iosP2P)
        assertTrue("receiverReadyLatch.await" in androidP2P)
        assertTrue("NativeReceiverReadyTracker" in iosP2P)
        assertTrue("verifyInviteSignature(" in iosP2P)
        assertTrue("verifyAcceptSignature(" in iosP2P)
        assertTrue("waitForPeerEphemeralPublic(seconds: 10)" in iosP2P)
        assertTrue("items.toManifestInputs()" in iosP2P)
        assertTrue("private var completedChunks: [Int: Set<Int>] = [:]" in iosP2P)
        assertTrue("func acceptReceiveTransfer(_ transferId: String)" in iosP2P)
        assertTrue("NativeTransferProtocolV3.encodeRetry(fileIndex: fileIndex, chunkIndex: chunkIndex)" in iosP2P)
        assertTrue("SHA256.hashData(payload) == file.fileHash" in iosP2P)
        assertTrue("extension SHA256" in iosTransferV3)
        assertFalse("private extension SHA256" in iosTransferV3)
        assertTrue("Curve25519.KeyAgreement.PrivateKey()" in iosTransferV3)
        assertTrue("HKDF<SHA256>.deriveKey(" in iosTransferV3)
        assertTrue("Curve25519.Signing.PrivateKey" in iosTransferV3)
        assertTrue("enum NativeTransferV3KeyAgreementRole" in iosTransferV3)
        assertTrue("AES.GCM.seal" in iosTransferV3)
        assertFalse("sealed.combined" in iosTransferV3)
        assertFalse("AES.GCM.SealedBox(combined:" in iosTransferV3)
        assertTrue("cipherBytes.append(sealed.ciphertext)" in iosTransferV3)
        assertTrue("cipherBytes.append(sealed.tag)" in iosTransferV3)
        assertTrue("nonce: try AES.GCM.Nonce(data: nonce)" in iosTransferV3)
        assertTrue("waitForWritableChannel" in iosWebRTC)
        assertTrue("channel.bufferedAmount <= highWaterMark" in iosWebRTC)
        assertTrue("frameReady = 0x02" in iosTransferV3)
        assertTrue("frameAck = 0x04" in iosTransferV3)
        assertTrue("struct NativeTransferV3ManifestInput" in iosTransferV3)
        assertTrue("@MainActor\n    func sendSelectedItems()" in iosModel)
        assertTrue("@MainActor\n    func acceptReceiveTransfer()" in iosModel)
        assertTrue("@MainActor\n    func cancelReceiveTransfer()" in iosModel)
        assertTrue("p2pTransferClient.send(" in iosModel)
        assertTrue("@Published var transferFailureMessage: String?" in iosModel)
        assertTrue("p2pFailureMessage(target: target, transferId: transferId, error: error)" in iosModel)
        val iosSendView = readIos("NativeSendView.swift")
        val androidSendStarter = readAndroid("app/SendTransferStarter.kt")
        val androidSendScreen = readAndroid("feature/send/SendRoute.kt")
        assertTrue(".alert(\"P2P 传输失败\"" in iosSendView)
        assertTrue("Button(\"复制\")" in iosSendView)
        assertTrue("UIPasteboard.general.string = model.transferFailureMessage ?? \"\"" in iosSendView)
        assertTrue("SuperDialog(" in androidSendScreen)
        assertTrue("P2P direct 失败" in androidSendScreen)
        assertTrue("LocalContext.current" in androidSendScreen)
        assertTrue("context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager" in androidSendScreen)
        assertTrue("clipboardManager.setPrimaryClip(ClipData.newPlainText(\"P2P direct 失败\", message))" in androidSendScreen)
        assertTrue("text = \"复制诊断\"" in androidSendScreen)
        assertTrue("p2pFailureMessage(target = target, transferId = transferId, cause = error)" in androidSendActions)
        assertTrue("class P2PTransferFailure(" in androidP2P)
        assertTrue("private func p2pError(" in iosP2P)
        val p2pFailureDialogFields = listOf(
            "目标：",
            "用户：",
            "设备：",
            "传输：",
            "会话：",
            "路径：",
            "发送端：",
            "接收端：",
            "在线快照：",
            "阶段：",
            "原始原因：",
            "direct_attempt_plan：",
            "direct_endpoint_count：",
            "direct_endpoints：",
            "direct_selected：",
            "direct_attempt_result：",
            "direct_last_error：",
            "offer_sent：",
            "answer_received：",
            "local_ice_count：",
            "remote_ice_count：",
            "ice_server_urls：",
            "local_candidate_types：",
            "remote_candidate_types：",
            "local_candidate_details：",
            "remote_candidate_details：",
            "ice_connection_state：",
            "ice_gathering_state：",
            "signaling_state：",
            "data_channel_state：",
            "ice_candidate_errors：",
            "selected_candidate_pair：",
            "ice_candidate_pair_stats：",
            "stun_error_rate：",
            "gathering_incomplete：",
            "symmetric_nat_suspect：",
            "remote_only_mdns：",
            "failure_reason_code：",
        )
        p2pFailureDialogFields.forEach { field ->
            assertTrue(field in androidSendActions, "Android P2P failure dialog must include $field")
            assertTrue(field in iosModel, "iOS P2P failure dialog must include $field")
        }
        assertInOrder(androidSendActions, *p2pFailureDialogFields.map { "\"$it" }.toTypedArray())
        assertInOrder(iosModel, *p2pFailureDialogFields.map { "\"$it" }.toTypedArray())
        assertTrue("target.receiverUserId?.ifBlank { null } ?: \"未知用户\"" in androidSendActions)
        assertTrue("target.receiverDeviceId?.ifBlank { null } ?: \"未知设备\"" in androidSendActions)
        listOf(
            "data class TransportNotice(",
            "is SendTransferEvent.TransportNotice -> this",
        ).forEach { marker ->
            assertTrue(marker in androidState, "Android send state must keep transport notice out of card state marker $marker")
        }
        listOf(
            "onTransferNotice: (String) -> Unit = {}",
            "if (event is SendTransferEvent.TransportNotice)",
            "onTransferNotice(event.message)",
        ).forEach { marker ->
            assertTrue(marker in androidSendStarter, "Android shared send entry must forward transport notice marker $marker")
        }
        assertTrue(
            "Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()" in androidApp,
            "Android host must show transport notice as a short toast",
        )
        listOf(
            "data class P2PTransferDiagnostic(",
            "P2P_DIRECT_ENDPOINT_WAIT_SECONDS = 5L",
            "P2P_DIRECT_TRANSPORT_TIMEOUT_SECONDS = 5L",
            "ExecutorCompletionService<P2PPreparedChannel?>",
            "fun openDirectRaceChannel()",
            "fun openWebRtcRaceChannel()",
            "peer.awaitDirectEndpoints(P2P_DIRECT_ENDPOINT_WAIT_SECONDS)",
            "fun openDirectChannelRace(",
            "Thread(runnable, \"piko-p2p-direct-race\")",
            "AtomicBoolean(false)",
            "completion.submit { openDirectRaceChannel() }",
            "completion.submit { openWebRtcRaceChannel() }",
            "channel = webRtcChannel",
            "transportName = if (endpoint.name == \"quic_ipv6_direct\") \"QUIC 直连通道\" else \"TCP 直连通道\"",
            "transportName = \"WebRTC 通道\"",
            "SendTransferEvent.TransportNotice(transferId, \"正在同时尝试直连通道和 WebRTC 通道\")",
            "SendTransferEvent.TransportNotice(transferId, \"已连接\${selectedChannel.transportName}，开始传输文件\")",
            "XQuicDirectTransport",
            "System.loadLibrary(\"piko_xquic\")",
            "external fun openServer",
            "external fun openClient",
            "XQuicNativeBinaryChannel",
            "p2pDirectTransportAttemptPlan()",
            "\"quic_ipv6_direct\"",
            "\"tcp_ipv6_direct\"",
            "\"webrtc_ipv6_host\"",
            "\"webrtc_stun\"",
            "P2P_INITIAL_OPEN_TIMEOUT_SECONDS = 12L",
            "P2P_RESTART_OPEN_TIMEOUT_SECONDS = 45L",
            "private const val P2P_LOG_TAG = \"PikoP2P\"",
            "logTiming(\"create_session_done\"",
            "logTiming(\"direct_endpoint_wait_done\"",
            "logTiming(\"direct_open_start\"",
            "\"direct_open_done\"",
            "logTiming(\"webrtc_offer_sent\"",
            "logTiming(\"webrtc_early_ice_restart\"",
            "logTiming(\"webrtc_opened\"",
            "logTiming(\"race_winner\"",
            "receiver_direct_prewarm_start",
            "receiver_direct_prewarm_done",
            "peer.awaitOpen(P2P_INITIAL_OPEN_TIMEOUT_SECONDS)",
            "peer.awaitOpen(P2P_RESTART_OPEN_TIMEOUT_SECONDS)",
            "@Synchronized\n        fun recordDirectAttempt(",
            "if (directAttemptResult == \"connected\" && result != \"connected\") return",
            "fun diagnosticSnapshot()",
            "private val closed = AtomicBoolean(false)",
            "private val peerConnectionLock = Any()",
            "if (!closed.compareAndSet(false, true)) return",
            "synchronized(peerConnectionLock)",
            "if (peer.isClosed) return",
            "localIceCount += 1",
            "remoteIceCount += 1",
            "answerReceived = true",
            "iceServerUrls = iceServerUrls",
            "localCandidateTypes = localCandidateTypes.candidateTypesDescription()",
            "remoteCandidateTypes = remoteCandidateTypes.candidateTypesDescription()",
            "localCandidateDetails = localCandidateDetails.candidateDetailsDescription()",
            "remoteCandidateDetails = remoteCandidateDetails.candidateDetailsDescription()",
            "iceConnectionState = state.name",
            "iceGatheringState = state.name",
            "signalingState = state.name",
            "dataChannelState = channel.state().name",
            "signaledCandidate.iceCandidateType()",
            "onIceCandidateError(event: IceCandidateErrorEvent)",
            "onSelectedCandidatePairChanged(event: CandidatePairChangeEvent)",
            "iceCandidateErrors = iceCandidateErrors.joinToString",
            "selectedCandidatePair = selectedCandidatePair",
            "iceCandidatePairStats = selectedCandidatePair",
            "iceCandidatePairStats = iceCandidatePairStats",
        ).forEach { marker ->
            assertTrue(marker in androidP2P, "Android P2P must record WebRTC diagnostic marker $marker")
        }
        assertInOrder(
            androidP2P,
            "completion.submit { openDirectRaceChannel() }",
            "completion.submit { openWebRtcRaceChannel() }",
            "val selectedChannel = preparedChannel",
            "val channel = selectedChannel.channel",
            "val sessionKey = selectedChannel.sessionKey",
            "TransferProtocolV3.encodeManifest(",
        )
        assertFalse(
            "peerConnection.getStats" in androidP2P,
            "Android P2P 失败诊断不得直接调用 WebRTC getStats，native 崩溃无法被 Kotlin 保护",
        )
        listOf(
            "struct NativeWebRTCDiagnostic",
            "NativeWebRTCTiming.sendOpenWaitSeconds",
            "var diagnosticSnapshot",
            "localIceCount += 1",
            "remoteIceCount += 1",
            "answerReceived = true",
            "iceServerUrls: iceServers.map(\\.urls).joined(separator: \",\").nilIfBlank ?? \"none\"",
            "localCandidateTypes: Self.candidateTypesDescription(localCandidateTypes)",
            "remoteCandidateTypes: Self.candidateTypesDescription(remoteCandidateTypes)",
            "localCandidateDetails: Self.candidateDetailsDescription(localCandidateDetails)",
            "remoteCandidateDetails: Self.candidateDetailsDescription(remoteCandidateDetails)",
            "iceConnectionState = value",
            "iceGatheringState = value",
            "signalingState = value",
            "dataChannelState = value",
            "pc.onicecandidateerror",
            "iceCandidateErrors.append",
            "iceCandidateErrors: iceCandidateErrors.joined",
            "selectedCandidatePair: selectedCandidatePair",
            "iceCandidatePairStats: iceCandidatePairStats",
            "diagnosticSnapshotWithStats()",
            "collectIceCandidatePairStats()",
            "candidateType(candidate)",
            "candidateSummary(candidate)",
        ).forEach { marker ->
            assertTrue(marker in iosWebRTC, "iOS WebRTC must record diagnostic marker $marker")
        }
        listOf(
            "NativeP2PTiming.directEndpointWaitSeconds",
            "NativeP2PTiming.directTransportWaitSeconds",
            "NativeP2PTiming.initialOpenWaitSeconds",
            "NativeP2PTiming.restartOpenWaitSeconds",
            "static let initialOpenWaitSeconds: TimeInterval = 12",
            "import OSLog",
            "private let nativeP2PLogger = Logger(",
            "nativeP2PTimingLog(stage: \"create_session_done\"",
            "func openDirectRaceChannel() async -> NativePreparedP2PChannel?",
            "func openWebRTCRaceChannel() async -> NativePreparedP2PChannel?",
            "NativeP2PConnectionRace(expectedCount: 2)",
            "let directRace = NativeP2PConnectionRace(expectedCount: endpoints.count)",
            "nativeP2PTimingLog(stage: \"direct_endpoint_wait_done\"",
            "nativeP2PTimingLog(stage: \"direct_open_start\"",
            "nativeP2PTimingLog(stage: \"direct_open_done\"",
            "return await directRace.waitForWinner()",
            "let candidate = await openDirectRaceChannel()",
            "let candidate = await openWebRTCRaceChannel()",
            "guard let selectedChannel = await race.waitForWinner()",
            "sessionContext.sessionKey = selectedChannel.sessionKey",
            "selectedChannel.closeAfterSelectedUse()",
            "candidate.closeIfUnused()",
            "transportNotice(\"正在同时尝试直连通道和 WebRTC 通道\")",
            "transportNotice(\"已连接\\(selectedChannel.transportName)，开始传输文件\")",
            "transportName: endpoint.name == \"quic_ipv6_direct\" ? \"QUIC 直连通道\" : \"TCP 直连通道\"",
            "transportName: \"WebRTC 通道\"",
            "nativeP2PTimingLog(stage: \"webrtc_offer_sent\"",
            "nativeP2PTimingLog(stage: \"webrtc_early_ice_restart\"",
            "nativeP2PTimingLog(stage: \"webrtc_opened\"",
            "nativeP2PTimingLog(stage: \"race_winner\"",
            "receiver_direct_prewarm_start",
            "receiver_direct_prewarm_done",
            "if diagnostic.attemptResult == \"connected\", result != \"connected\"",
            "directTransportAttemptPlan()",
            "@MainActor\nprivate func directTransportAttemptPlan()",
            "NativeP2PDirectServer",
            "NativeP2PFramedConnection",
            "NativeXQuicDirectTransport",
            "NativeXQuicDirectChannel",
            "PIKO_XQUIC_NATIVE",
            "piko_xquic_open_server",
            "piko_xquic_open_client",
            "\"direct_endpoint\"",
            "\"quic_ipv6_direct\"",
            "\"tcp_ipv6_direct\"",
            "\"webrtc_ipv6_host\"",
            "\"webrtc_stun\"",
        ).forEach { marker ->
            assertTrue(marker in iosP2P, "iOS P2P must record WebRTC timing marker $marker")
        }
        assertInOrder(
            iosP2P,
            "let race = NativeP2PConnectionRace(expectedCount: 2)",
            "let candidate = await openDirectRaceChannel()",
            "let candidate = await openWebRTCRaceChannel()",
            "guard let selectedChannel = await race.waitForWinner()",
            "let sessionKey = selectedChannel.sessionKey",
            "NativeTransferProtocolV3.encodeManifest(",
        )
        listOf(
            "@Published var transferToastMessage: String?",
            "transportNotice: { message in",
            "self.transferToastMessage = message",
        ).forEach { marker ->
            assertTrue(marker in iosModel, "iOS model must publish transport notice marker $marker")
        }
        listOf(
            ".alert(\"传输状态\"",
            "model.transferToastMessage != nil",
            "model.transferToastMessage = nil",
        ).forEach { marker ->
            assertTrue(marker in iosSendView, "iOS send view must show transport notice marker $marker")
        }
        assertTrue("let ipv6Sockaddr = address.withMemoryRebound(to: sockaddr_in6.self" in iosP2P)
        assertTrue("var mutableSockaddr = ipv6Sockaddr" in iosP2P)
        assertFalse("let sockaddr = address.withMemoryRebound(to: sockaddr_in6.self" in iosP2P)
        assertFalse("): XQuicDirectServer? = null" in androidP2P)
        assertFalse("XQUIC native transport is not linked" in androidP2P)
        listOf(
            "externalNativeBuild",
            "src/main/cpp/CMakeLists.txt",
            "-DPIKO_XQUIC_GIT_TAG=v1.9.2",
        ).forEach { marker ->
            assertTrue(marker in androidBuildGradle, "Android Gradle must wire XQUIC native build marker $marker")
        }
        listOf(
            "FetchContent",
            "https://github.com/alibaba/xquic.git",
            "PIKO_XQUIC_GIT_TAG \"v1.9.2\"",
            "https://github.com/google/boringssl.git",
            "add_library(xquic_core STATIC",
            "add_library(piko_xquic SHARED",
            "piko_xquic_jni.cpp",
            "piko_xquic_shim.c",
        ).forEach { marker ->
            assertTrue(marker in xquicCmake, "CMake must build real XQUIC native transport marker $marker")
        }
        listOf(
            "-Wno-unused-value",
            "-Wno-pointer-sign",
        ).forEach { marker ->
            assertTrue(marker in xquicCmake, "CMake must scope third-party XQUIC warning option $marker")
        }
        assertInOrder(
            xquicCmake,
            "target_compile_options(xquic_core PRIVATE",
            "-Wno-unused-value",
            "-Wno-pointer-sign",
        )
        assertInOrder(
            xquicCmake,
            "set_source_files_properties(",
            "piko_xquic_shim.c",
            "COMPILE_OPTIONS \"-Wno-unused-value\"",
        )
        assertFalse("add_compile_options(-Wno-unused-value" in xquicCmake)
        assertFalse("add_compile_options(-Wno-pointer-sign" in xquicCmake)
        listOf(
            "JNI_OnLoad",
            "RegisterNatives",
            "xqc_engine_create",
            "xqc_conn_settings_t connSettings{}",
            "ctx->engine,\n        &connSettings,\n        nullptr,",
            "xqc_connect",
            "xqc_stream_create",
            "xqc_stream_send",
            "xqc_stream_recv",
            "handshakeComplete",
            "contextFromUserData",
            "channelFromUserData",
            "fdSnapshot(ctx.get())",
            "transport.conn_update_cid_notify = connUpdateCidNotify",
            "transport.save_token = saveToken",
            "transport.cert_verify_cb = verifyCertificate",
        ).forEach { marker ->
            assertTrue(marker in xquicJni, "JNI bridge must use real XQUIC API marker $marker")
        }
        assertTrue("std::atomic<bool> cleaned{false}" in xquicJni)
        assertInOrder(
            xquicJni,
            "void loopContext(std::shared_ptr<NativeContext> ctx)",
            "ctx->closed.store(true);",
            "cleanupContext(ctx);",
        )
        assertInOrder(
            xquicJni,
            "void closeContext(const std::shared_ptr<NativeContext> &ctx)",
            "if (ctx->worker.get_id() == std::this_thread::get_id())",
            "ctx->worker.detach();",
            "return;",
            "cleanupContext(ctx);",
        )
        listOf(
            "int32_t piko_xquic_is_linked",
            "piko_xquic_open_server",
            "piko_xquic_open_client",
            "int32_t piko_xquic_send_frame",
            "PikoXQuicFrameCallback",
        ).forEach { marker ->
            assertTrue(marker in iosXquicHeader, "iOS XQUIC header must expose marker $marker")
        }
        listOf(
            "extern \"C\" int32_t piko_xquic_is_linked",
            "xqc_engine_create",
            "xqc_conn_settings_t connSettings{}",
            "ctx->engine,\n        &connSettings,\n        nullptr,",
            "xqc_connect",
            "xqc_stream_create",
            "xqc_stream_send",
            "xqc_stream_recv",
            "handshakeComplete",
            "contextFromUserData",
            "channelFromUserData",
            "fdSnapshot(ctx.get())",
            "transport.conn_update_cid_notify = connUpdateCidNotify",
            "transport.save_token = saveToken",
            "transport.cert_verify_cb = verifyCertificate",
        ).forEach { marker ->
            assertTrue(marker in iosXquicBridge, "iOS XQUIC bridge must use real XQUIC API marker $marker")
        }
        listOf(
            "transport/xqc_conn.h",
            "transport/xqc_stream.h",
            "stream->stream_conn->proto_data",
        ).forEach { marker ->
            assertTrue(marker in iosXquicShim, "iOS XQUIC shim must expose complete XQUIC connection internals marker $marker")
        }
        listOf(
            "https://github.com/alibaba/xquic.git",
            "PIKO_XQUIC_GIT_TAG \"v1.9.2\"",
            "https://github.com/google/boringssl.git",
            "CMAKE_MACOSX_BUNDLE FALSE",
            "add_library(piko_xquic_ios STATIC",
            "PikoXQuicBridge.cpp",
            "PikoXQuicShim.c",
        ).forEach { marker ->
            assertTrue(marker in iosXquicCmake, "iOS CMake must build real XQUIC native transport marker $marker")
        }
        listOf(
            "build_xquic_native",
            "command -v cmake",
            "ios/xquic",
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_OSX_SYSROOT=iphoneos",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "cmake --build",
            "libpiko_xquic_ios.a",
            "libssl.a",
            "libcrypto.a",
            "-D PIKO_XQUIC_NATIVE",
            "-lc++",
        ).forEach { marker ->
            assertTrue(marker in iosBuildScript, "iOS packaging script must link XQUIC marker $marker")
        }
        listOf("create_session", "data_channel_open", "key_agreement", "send_manifest", "receiver_ready", "send_chunk", "ack").forEach { stage ->
            assertTrue(stage in androidP2P, "Android P2P must keep failure stage $stage")
            assertTrue(stage in iosP2P, "iOS P2P must keep failure stage $stage")
        }
        assertFalse("WebRTC DataChannel 尚未接入" in iosModel)
        assertTrue("signalingClient.connect(token, identity.deviceId)" in androidApp)
        assertTrue("signalingClient.connect(token: token, deviceId: identity.deviceId)" in iosModel)

        // STUN list cleanup: cloudflare:53 must be gone from every file that references the list.
        listOf(backendIce, androidSessionApi, iosSessionApi).forEach { source ->
            assertFalse("stun.cloudflare.com:53" in source, "cloudflare:53 STUN must be removed (broken on most ISPs)")
        }

        // P2P failure reason enum parity: every code must exist on both platforms.
        val failureReasonCodes = listOf(
            "LOCAL_NO_CANDIDATE",
            "REMOTE_NO_CANDIDATE",
            "STUN_SERVERS_DEGRADED",
            "SYMMETRIC_NAT",
            "REMOTE_MDNS_ONLY",
            "CONNECTIVITY_CHECK_FAILED",
            "CGNAT_BOTH_SIDES",
            "NAT_INCOMPATIBLE",
            "GENERIC_TIMEOUT",
        )
        failureReasonCodes.forEach { code ->
            assertTrue(code in androidP2P, "Android P2PFailureReason must declare $code")
            assertTrue(code in iosWebRTC, "iOS NativeP2PFailureReason must declare $code")
        }
        assertTrue("enum class P2PFailureReason" in androidP2P)
        assertTrue("enum NativeP2PFailureReason" in iosWebRTC)
        assertTrue("internal fun crossNetworkDiagnosis(diag: P2PTransferDiagnostic): P2PFailureReason" in androidP2P)
        assertTrue("static func crossNetworkDiagnosis(_ diag: NativeWebRTCDiagnostic) -> NativeP2PFailureReason" in iosWebRTC)

        // iOS RTCPeerConnection config must include the same knobs Android sets natively.
        assertTrue("iceCandidatePoolSize: 4" in iosWebRTC, "iOS RTCPeerConnection must set iceCandidatePoolSize")
        assertTrue("bundlePolicy: \"max-bundle\"" in iosWebRTC, "iOS RTCPeerConnection must set bundlePolicy=max-bundle")
        assertTrue("rtcpMuxPolicy: \"require\"" in iosWebRTC, "iOS RTCPeerConnection must set rtcpMuxPolicy=require")
        assertTrue("iceTransportPolicy: \"all\"" in iosWebRTC, "iOS RTCPeerConnection must set iceTransportPolicy=all")
        assertTrue("P2P_IPV6_DIRECT_CANDIDATE_PRIORITY = 2_130_706_431" in androidP2P)
        assertTrue("IPV6_DIRECT_CANDIDATE_PRIORITY = 2130706431" in iosWebRTC)
        assertTrue("prioritizeP2PIceCandidateForSignaling(candidate.sdp)" in androidP2P)
        assertTrue("prioritizeCandidateForSignaling(event.candidate.candidate)" in iosWebRTC)
        assertTrue("split(/\\\\s+/)" in iosWebRTC, "iOS WebRTC embedded JavaScript whitespace regex must be escaped for Swift")

        // New diagnostic fields must be present in both diagnostic types.
        listOf(
            "directAttemptPlan",
            "directEndpointCount",
            "directEndpoints",
            "directSelected",
            "directAttemptResult",
            "directLastError",
            "stunErrorRate",
            "gatheringIncomplete",
            "symmetricNatSuspect",
            "remoteOnlyMdns",
            "failureReason",
        ).forEach { field ->
            assertTrue(field in androidP2P, "Android P2PTransferDiagnostic must declare $field")
            assertTrue(field in iosWebRTC, "iOS NativeWebRTCDiagnostic must declare $field")
        }
    }

    @Test
    fun androidP2PReceiverCleansReceiveSessionAfterTerminalEvents() {
        val androidP2P = readAndroid("transport/P2PTransferClient.kt")
        val iosP2P = readIos("NativeP2PTransferClient.swift")

        assertTrue("private fun closeReceiveSession(sessionId: String, transferId: String? = null)" in androidP2P)
        assertInOrder(
            androidP2P,
            "private fun closeReceiveSession(sessionId: String, transferId: String? = null)",
            "receiversByTransferId.remove(transferId)",
            "receiversByTransferId.entries.removeIf { it.value.sessionId == sessionId }",
            "pendingSignalsBySessionId.remove(sessionId)",
            "directServersBySessionId.remove(sessionId)?.close()",
            "peers.remove(sessionId)?.close()",
        )
        assertTrue("onTerminal = { terminalSessionId, terminalTransferId ->" in androidP2P)
        assertTrue("signalExecutor.execute { closeReceiveSession(terminalSessionId, terminalTransferId) }" in androidP2P)
        assertInOrder(
            androidP2P,
            "ReceiveTransferEvent.Completed(",
            "cleanupTerminalSession()",
        )
        assertInOrder(
            androidP2P,
            "ReceiveTransferEvent.Failed(transferId, \"文件校验失败\")",
            "cleanupTerminalSession()",
        )
        assertInOrder(
            androidP2P,
            "fun cancelReceiveTransfer(transferId: String)",
            "receiver.cancel()",
            "closeReceiveSession(sessionId, transferId)",
        )
        assertInOrder(
            iosP2P,
            "onReceiveCompleted: { [weak self] item in",
            "self?.receivers.removeValue(forKey: sessionId)",
            "self?.sessions.removeValue(forKey: sessionId)",
            "self?.directServers.removeValue(forKey: sessionId)?.close()",
            "self?.directEndpointTrackers.removeValue(forKey: sessionId)",
        )
    }

    @Test
    fun androidSendTransferEventsAreDispatchedOnMainThread() {
        val androidSendActions = readAndroid("platform/AndroidSendPlatformActions.kt")

        assertInOrder(
            androidSendActions,
            "fun startTransfer(",
            "val emit: (SendTransferEvent) -> Unit = { event ->",
            "mainHandler.post { callback(event) }",
            "emit(SendTransferEvent.Started(transferId, request, request.totalBytes))",
        )
        assertTrue("callback = emit" in androidSendActions)
        listOf(
            "emit(SendTransferEvent.Completed(transferId))",
            "emit(SendTransferEvent.Paused(transferId))",
            "emit(SendTransferEvent.Canceled(transferId))",
            "emit(SendTransferEvent.Failed(transferId, message))",
        ).forEach { marker ->
            assertTrue(marker in androidSendActions, "Android send transfer event must be posted through main-thread emitter $marker")
        }
    }

    @Test
    fun presenceRefreshIsEventDrivenWithoutPeriodicTickers() {
        val androidApp = readAndroid("platform/AndroidPikoApp.kt")
        val androidScheduler = readAndroid("platform/PresenceHeartbeatScheduler.kt")
        val iosModel = readIos("NativePikoModel.swift")
        val iosPresenceTicker = readIos("NativePresenceTicker.swift")
        val iosRoot = readIos("PikoRootView.swift")

        assertFalse("delay(30_000)" in androidScheduler)
        assertFalse("while (isActive)" in androidScheduler)
        assertFalse("heartbeatScheduler.start()" in androidApp)
        assertFalse("friendsRepository.heartbeat()" in androidApp)
        assertTrue("refreshFriendsPresence()" in androidApp)
        assertTrue("AppLifecycleForegroundObserver" in androidApp)
        assertTrue("destination == PikoDestination.Send" in androidApp)
        assertTrue("destination == PikoDestination.Friends" in androidApp)
        assertTrue("friendsRepository.refreshAll()" in androidApp)
        assertInOrder(
            iosModel,
            "func refreshFriendsPresence()",
            "await friendStore.refreshAll()",
        )
        assertFalse("DispatchSourceTimer" in iosPresenceTicker)
        assertFalse("timer.schedule" in iosPresenceTicker)
        assertFalse("await friendStore.heartbeat()" in iosPresenceTicker)
        assertTrue("@Environment(\\.scenePhase)" in iosRoot)
        assertTrue(".onChange(of: scenePhase)" in iosRoot)
        assertTrue("model.refreshFriendsPresence()" in iosRoot)
        assertTrue("signalingClient.connect(token, identity.deviceId)" in androidApp)
        assertTrue("signalingClient.connect(token: token, deviceId: identity.deviceId)" in iosModel)
        assertFalse("model.stopPresence()" in iosRoot)
        assertFalse("onDisappear" in iosRoot)
    }

    @Test
    fun iosAuthStoreIsCreatedFromMainActorIsolatedModelInit() {
        val iosModel = readIos("NativePikoModel.swift").replace("\r\n", "\n")
        val iosAuthStore = readIos("NativeAuthStore.swift")

        assertTrue("@MainActor\nfinal class NativeAuthStore" in iosAuthStore)
        assertInOrder(
            iosModel,
            "let authStore: NativeAuthStore",
            "@MainActor\n    init()",
            "self.authStore = NativeAuthStore()",
        )
    }

    @Test
    fun androidUsesMiuixPaletteInsteadOfMaterialOrIosNamedColors() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/design/PikoMiuixTheme.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val appShell = File(rootDir, "android/src/main/kotlin/com/piko/app/app/PikoAndroidAppShell.kt").readText()

        assertTrue("PikoMiuixTheme" in androidTheme)
        assertTrue("MiuixTheme(" in androidTheme)
        assertTrue("ThemeController(" in androidTheme)
        assertTrue("lightColorScheme(" in androidTheme)
        assertTrue("darkColorScheme(" in androidTheme)
        assertTrue("PikoAndroidAppShell(" in androidApp)
        assertTrue("PikoMiuixTheme {" in appShell)
        assertTrue("NavigationBar(" in appShell)
        assertTrue("NavigationBarItem(" in appShell)
        assertFalse("InstallerXFloatingBottomBar(" in appShell)
        assertFalse("PikoGlassNavigationItem(" in appShell)
        assertFalse("PikoGlassSendAction(" in appShell)
        assertFalse("floatingActionButton =" in appShell)
        assertFalse("ExtendedFloatingActionButton(" in appShell)
        assertFalse("dynamicLightColorScheme" in androidTheme + androidApp + appShell)
        assertFalse("dynamicDarkColorScheme" in androidTheme + androidApp + appShell)
        assertFalse("IOS_SYSTEM_BLUE" in androidTheme)
        assertFalse("IOS_SYSTEM_BACKGROUND" in androidTheme)
    }

    @Test
    fun iosContentUsesSfSymbolsForSystemLevelNativePages() {
        val receiveView = readIos("NativeReceiveView.swift")
        val sendView = readIos("NativeSendView.swift")
        val rootView = readIos("PikoRootView.swift")
        val iosContent = receiveView + sendView + rootView

        listOf(
            "tray.and.arrow.down",
            "iphone",
            "doc",
            "photo",
            "paperplane",
            "gearshape",
            "checkmark.circle.fill",
        ).forEach { name ->
            assertTrue("systemName: \"$name\"" in iosContent || "systemImage: \"$name\"" in iosContent)
        }
        assertFalse("LucideTabIcon." in receiveView + sendView)
    }

    @Test
    fun iosSendDeviceSubtitleKeepsAndroidOptionalContract() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/SendPageState.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosSendView = readIos("NativeSendView.swift")

        assertTrue("val subtitle: String? = null" in androidState)
        assertTrue("let subtitle: String?" in iosModel)
        assertTrue("if let subtitle = device.subtitle" in iosSendView)
    }

    @Test
    fun iosWorkflowBuildsWithAvailablePhoneSdkWithoutInstallingSimulator() {
        val workflow = File(rootDir, ".github/workflows/build-packages.yml").readText()
        val iosScript = File(rootDir, "scripts/ios/build-packages.sh").readText()
        val xcodeCandidateLoop = workflow.substringBefore("if [[ -z \"\$selected_xcode\" ]]; then")

        assertFalse("xcodebuild -downloadPlatform iOS" in workflow)
        assertFalse("iphonesimulator" in workflow)
        assertFalse("iphonesimulator" in iosScript)
        assertFalse("-destination" in workflow)
        assertFalse("-destination" in iosScript)
        assertFalse("xcodebuild \\" in iosScript)
        assertFalse("SUPPORTED_PLATFORMS=iphoneos" in iosScript)
        assertTrue("sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"" in xcodeCandidateLoop)
        assertTrue("sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"" in iosScript)
        assertTrue("xcrun swiftc" in iosScript)
        assertTrue("DEPLOYMENT_TARGET=\"16.0\"" in iosScript)
        assertTrue("-target \"arm64-apple-ios\${DEPLOYMENT_TARGET}\"" in iosScript)
        assertTrue("sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"" in iosScript)
        assertTrue("\"CFBundleIdentifier\": bundle_id" in iosScript)
        assertTrue("\"MinimumOSVersion\": deployment_target" in iosScript)
        assertInOrder(
            xcodeCandidateLoop,
            "sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"",
            "sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"",
            "selected_xcode=\"\$candidate\"",
        )
        assertInOrder(
            iosScript,
            "sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"",
            "sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"",
            "xcrun swiftc",
            "-sdk \"\$sdk_path\"",
            "-parse-as-library",
            "-module-name \"\$SCHEME\"",
            "-o \"\$executable\"",
        )
    }

    @Test
    fun sendPagesDoNotExposeCurrentDeviceAndUseRandomNicknameBanner() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/SendPageState.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidDiscovery = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidSendPlatformActions.kt").readText()
        val androidReceive = readAndroid("feature/receive/ReceiveRoute.kt")
        val iosModel = readIos("NativePikoModel.swift")
        val iosLanDiscovery = readIos("NativeLanDiscoveryService.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosProject = File(rootDir, "ios/Piko.xcodeproj/project.pbxproj").readText()
        val iosEntitlements = File(rootDir, "ios/Piko.entitlements")

        assertTrue("myDevices = emptyList()" in androidState)
        assertTrue("var myDevices: [NativeSendDevice] { [] }" in iosModel)
        assertTrue("alias: nickname.title" in iosModel)
        assertTrue("name: serviceNickname.title" in iosLanDiscovery)
        assertTrue("subtitle: serviceNickname.code" in iosLanDiscovery)
        assertTrue("name: currentServiceName" in iosLanDiscovery)
        assertTrue("info.fingerprint != self.nickname().fingerprint" in iosLanDiscovery)
        assertFalse("name: currentDeviceName" in iosModel)
        assertFalse("UIDevice.current.name" in iosModel)
        assertFalse("identifierForVendor" in iosModel)
        assertFalse("iPhone A567" in iosModel)
        assertFalse(iosEntitlements.isFile)
        assertFalse("CODE_SIGN_ENTITLEMENTS = Piko.entitlements;" in iosProject)
        assertFalse("Settings.Global.DEVICE_NAME" in androidApp)
        assertFalse("Build.MODEL" in androidApp)
        assertTrue("MiuixIcons.Download" in androidReceive)
        assertTrue("title = deviceName" in androidReceive)
        assertTrue("可接收 · \$historyCount 条历史记录" in androidReceive)
        assertTrue("text = \"更换\"" in androidReceive)
        assertTrue("Image(systemName: \"iphone\")" in iosReceive)
        assertTrue("ReceiveStatusSummary(model: model)" in iosReceive)
        assertTrue("Button(\"更换\", action: model.resetDeviceNickname)" in iosReceive)
        assertFalse("Label(\"更换\", systemImage: \"arrow.clockwise\")" in iosReceive)
        assertFalse("Button(\"更换\", systemImage: \"arrow.clockwise\", action: model.resetDeviceNickname)" in iosReceive)
        assertTrue("Text(model.currentDeviceName)" in iosReceive)
        assertTrue("Text(\"本设备名称\")" in iosReceive)
        assertFalse("Image(uiImage: LucideTabIcon.inbox.image)" in iosReceive)
        assertFalse("UIDevice.current.name" in iosReceive)
        assertTrue("subtitle = nickname.code" in androidDiscovery)
        assertFalse("subtitle = resolvedService.host?.hostAddress" in androidDiscovery)
        assertTrue("registerServiceInfoCallback" in androidDiscovery)
        assertTrue("resolveRemoteServiceLegacy" in androidDiscovery)
        assertTrue("hostAddresses.firstOrNull()?.hostAddress" in androidDiscovery)
        assertFalse("resolvedService.host?.hostAddress" in androidDiscovery)
    }

    @Test
    fun receivePagesUseCompactActiveProgressAndMediaPreview() {
        val androidReceive = readAndroid("feature/receive/ReceiveRoute.kt")
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/PikoHomeState.kt").readText()
        val androidDiscovery = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidSendPlatformActions.kt").readText()
        val androidLocalSendServer = File(rootDir, "android/src/main/kotlin/com/piko/app/transport/LocalSendHttpServer.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosReceiveFileStore = readIos("NativeReceiveFileStore.swift")

        assertFalse("PikoInfoPill(text = \"最近接收\"" in androidReceive)
        assertFalse("PikoPill(text: \"最近接收\"" in iosReceive)
        assertFalse("CircularProgressIndicator" in androidReceive)
        assertFalse("Circle()\n                .trim" in iosReceive)
        assertTrue("LinearProgressIndicator(progress = transfer.progress)" in androidReceive)
        assertTrue("ProgressView(value: transfer.progress)" in iosReceive)
        assertTrue("BasicComponent(" in androidReceive)
        assertTrue(".font(.headline)" in iosReceive)
        assertFalse(".offset(x = (-8).dp)" in androidReceive)
        assertFalse(".offset(x: -8)" in iosReceive)
        assertTrue("mediaPreviewDescription" in androidState)
        assertTrue("isMediaPreview" in androidState)
        assertTrue("item.files.take(3)" in androidReceive)
        assertTrue("mediaPreviewData" in iosReceiveFileStore)
        assertTrue("NativeReceiveHistoryPreview" in iosReceive)
        assertTrue("UIImage(data: data)" in iosReceive)
        assertTrue("AVAssetImageGenerator" in iosReceiveFileStore)
        assertTrue("mediaPreviewImageData(" in iosReceiveFileStore)
        assertTrue("UIGraphicsImageRenderer" in iosReceiveFileStore)
        assertTrue("jpegData(compressionQuality: 0.82)" in iosReceiveFileStore)
        assertFalse("case .image:\n            return fallbackData" in iosReceiveFileStore.replace("\r\n", "\n"))
        assertFalse("UIImage(data: $0)?.receiveListPixelDescription" in iosReceive)
        assertTrue("mediaPreviewData" in iosReceive)
        assertFalse("receiveListImageDescription" in iosReceive)
        assertTrue("file.fileType.isMediaPreview" in androidDiscovery)
        assertTrue("file.fileType.isMediaPreview" in androidLocalSendServer)
    }

    @Test
    fun iosReceiveFileListUsesSwiftUiListAndKeepsRequiredControls() {
        val androidReceive = readAndroid("feature/receive/ReceiveRoute.kt")
        val iosReceive = readIos("NativeReceiveView.swift")

        assertTrue("LazyColumn(" in androidReceive)
        assertTrue("Card(modifier = Modifier.fillMaxWidth())" in androidReceive)
        assertTrue("List {" in iosReceive)
        assertTrue(".listStyle(.insetGrouped)" in iosReceive)
        assertTrue(".scrollContentBackground(.visible)" in iosReceive)
        assertTrue(".swipeActions(edge: .trailing, allowsFullSwipe: false)" in iosReceive)
        assertTrue(".confirmationDialog(" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertTrue("NativeReceiveHistoryPreview(item: item)" in iosReceive)
        assertTrue("NativeActiveReceiveRow(" in iosReceive)
        assertTrue("ProgressView(value: transfer.progress)" in iosReceive)
        assertTrue("Button(\"接收\", systemImage: \"checkmark.circle\"" in iosReceive)
        assertTrue("Text(transfer.title)" in iosReceive)
        assertTrue("Text(transfer.subtitle)" in iosReceive)
        assertTrue("Text(item.title)" in iosReceive)
        assertTrue("Text(item.subtitle)" in iosReceive)
        assertFalse("UIViewControllerRepresentable" in iosReceive)
        assertFalse("UITableViewController" in iosReceive)
        assertFalse("UIContextualAction" in iosReceive)
        assertFalse("UIAlertController" in iosReceive)
        assertFalse(".listStyle(.plain)" in iosReceive)
        assertFalse("trailing: 16" in iosReceive)
        assertFalse("static let contentTrailingInset: CGFloat = 0" in iosReceive)
        assertFalse("ScrollView(showsIndicators: false)" in iosReceive)
        assertFalse("LazyVStack" in iosReceive)
        assertFalse("NativeReceiveHistoryCard" in iosReceive)
        assertFalse("NativeActiveReceiveCard" in iosReceive)
        assertFalse("rowView(bottom: index < model.receiveHistory.count - 1" in iosReceive)
        assertFalse("static let historyRowSpacing" in iosReceive)
        assertFalse("NativeReceiveSwipeRow" in iosReceive)
        assertFalse("DragGesture(minimumDistance: 12)" in iosReceive)
        assertFalse("NativeReceiveTableRow" in iosReceive)
        assertFalse("performBatchUpdates" in iosReceive)
        assertFalse("cachedEmptyStateRowHeight" in iosReceive)
        assertFalse("historyEstimatedHeight" in iosReceive)
    }

    @Test
    fun receiveHistoryPersistsAndIosSavesInDocumentsRoot() {
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/PikoHomeState.kt").readText()
        val androidStore = File(rootDir, "android/src/main/kotlin/com/piko/app/data/ReceiveHistoryStore.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosReceiveHistoryStore = readIos("NativeReceiveHistoryStore.swift")

        assertTrue("ReceiveHistoryStore.fromContext(appContext)" in androidApp)
        assertTrue("receiveHistory = receiveHistoryStore.load()" in androidApp)
        assertTrue("receiveHistoryStore.save(nextState.receiveHistory)" in androidApp)
        assertTrue("receiveHistory: List<ReceiveHistoryItem> = emptyList()" in androidState)
        assertTrue("receive_history.json" in androidStore)
        assertTrue("NativeReceiveHistoryStore.load()" in iosModel)
        assertTrue("NativeReceiveHistoryStore.save(receiveHistory)" in iosModel)
        assertTrue("FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first" in iosReceiveHistoryStore)
        assertFalse(".appendingPathComponent(\"Piko\", isDirectory: true)" in iosModel)
    }

    @Test
    fun iosTransfersStreamFilesAndKeepReceivePersistenceOutOfModel() {
        val iosModel = readIos("NativePikoModel.swift")
        val iosPickers = readIos("NativePickers.swift")
        val iosProtocol = readIos("NativeTransferProtocol.swift")
        val iosProject = File(rootDir, "ios/Piko.xcodeproj/project.pbxproj").readText()
        val iosTransferModels = readIos("NativeTransferModels.swift")
        val iosReceiveHistoryStore = readIos("NativeReceiveHistoryStore.swift")
        val iosTransferClient = readIos("NativeTransferClient.swift")
        val iosReceiveFileStore = readIos("NativeReceiveFileStore.swift")
        val iosLocalSendSessionStore = readIos("NativeLocalSendSessionStore.swift")
        val iosLanDiscovery = readIos("NativeLanDiscoveryService.swift")
        val iosTransferStateMachine = readIos("NativeTransferStateMachine.swift")

        assertTrue("let fileURL: URL" in iosTransferModels)
        assertTrue("let sizeBytes: Int" in iosTransferModels)
        assertTrue("let previewData: Data?" in iosTransferModels)
        assertTrue("struct NativeReceivedFile" in iosTransferModels)
        assertFalse("let data: Data" in iosTransferModels)
        assertFalse("private enum NativeReceiveHistoryStore" in iosModel)
        assertTrue("enum NativeReceiveHistoryStore" in iosReceiveHistoryStore)
        assertTrue("Int32(bitPattern:" in iosProtocol)
        assertTrue("final class NativeTransferClient" in iosTransferClient)
        assertTrue("final class NativeReceiveFileStore" in iosReceiveFileStore)
        assertTrue("final class NativeLocalSendSessionStore" in iosLocalSendSessionStore)
        assertTrue("final class NativeLanDiscoveryService" in iosLanDiscovery)
        assertTrue("final class NativeTransferStateMachine" in iosTransferStateMachine)
        assertTrue("NativeTransferModels.swift in Sources" in iosProject)
        assertTrue("NativeReceiveHistoryStore.swift in Sources" in iosProject)
        assertTrue("NativeTransferClient.swift in Sources" in iosProject)
        assertTrue("NativeReceiveFileStore.swift in Sources" in iosProject)
        assertTrue("NativeLocalSendSessionStore.swift in Sources" in iosProject)
        assertTrue("NativeLanDiscoveryService.swift in Sources" in iosProject)
        assertTrue("NativeTransferStateMachine.swift in Sources" in iosProject)

        assertFalse("Data(contentsOf: url)" in iosPickers)
        assertFalse("loadDataRepresentation" in iosPickers)
        assertTrue("loadFileRepresentation" in iosPickers)
        assertTrue("copyTransferItem" in iosPickers)

        assertFalse("item.data" in iosModel)
        assertFalse("file.data" in iosModel)
        assertFalse("Data(contentsOf: finalURL)" in iosModel)
        assertFalse("Data(contentsOf: temporaryURL)" in iosModel)
        assertFalse("savedFiles.reduce(0) { $0 + $1.data.count }" in iosModel)
        assertTrue("sendHttpFileRequest(" in iosTransferClient)
        assertTrue("sendFile(" in iosTransferClient)
        assertTrue("transferClient.send(" in iosModel)
        assertTrue("receiveFileStore.save(" in iosModel)
        assertTrue("lanDiscovery.startPresence()" in iosModel)
        assertTrue("lanDiscovery.startDiscovery()" in iosModel)
        assertTrue("transferStateMachine.pauseSend()" in iosModel)
        assertTrue("transferStateMachine.cancelSend()" in iosModel)
        assertTrue("transferStateMachine.updateSendProgress(" in iosModel)
        assertFalse("private func sendLocalSendItems" in iosModel)
        assertFalse("private func sendLegacyItems" in iosModel)
        assertFalse("private func saveReceivedTransfer" in iosModel)
        assertFalse("private var localSendSessions" in iosModel)
        assertFalse("private var listener: NWListener?" in iosModel)
        assertFalse("private var browser: NWBrowser?" in iosModel)
        assertFalse("private var multicastDiscovery" in iosModel)
        assertFalse("private var activeSendConnection" in iosModel)
        assertFalse("private var activeReceiveConnection" in iosModel)
        assertFalse("private func startLocalSendMulticast" in iosModel)
        assertTrue("NWBrowser(for: .bonjourWithTXTRecord" in iosLanDiscovery)
        assertTrue("NWListener.Service(" in iosLanDiscovery)
        assertTrue("NativeLocalSendMulticast(" in iosLanDiscovery)
        assertTrue("private var activeSendConnection: NWConnection?" in iosTransferStateMachine)
        assertTrue("private var activeReceiveConnection: NWConnection?" in iosTransferStateMachine)
        assertTrue("func pauseSend()" in iosTransferStateMachine)
        assertTrue("func cancelSend()" in iosTransferStateMachine)
        assertTrue(iosModel.lineSequence().count() < 1100, "NativePikoModel should stay below 1100 lines after P1-4 split")
        assertTrue("NativeReceivedPayloadFile" in iosProtocol)
        assertFalse("item.data.count" in iosProtocol)
    }

    @Test
    fun iosTransferClientUsesAsyncAwaitWithoutBlockingWait() {
        val iosModel = readIos("NativePikoModel.swift")
        val iosTransferClient = readIos("NativeTransferClient.swift")
        val transferSources = iosModel + iosTransferClient

        assertTrue(") async -> Int?" in iosTransferClient)
        assertTrue("await self.transferClient.send(" in iosModel)
        assertTrue("withCheckedContinuation" in iosTransferClient)
        assertTrue("withTaskCancellationHandler" in iosTransferClient)
        assertTrue("NativeConnectionReadyWaiter" in iosTransferClient)
        assertFalse("DispatchSemaphore" in transferSources)
        assertFalse(".wait()" in transferSources)
        assertFalse(".wait(" in transferSources)
        assertFalse("queue.async" in transferSources)
    }

    @Test
    fun androidCoreFilesUseArchitecturePackages() {
        val expectedPackages = mapOf(
            "domain/SendPageState.kt" to "package com.piko.app.domain",
            "domain/PikoHomeState.kt" to "package com.piko.app.domain",
            "domain/SendTransferProtocol.kt" to "package com.piko.app.domain",
            "data/ReceiveHistoryStore.kt" to "package com.piko.app.data",
            "data/ReceiveMediaSaveLocation.kt" to "package com.piko.app.data",
            "data/LocalSendSessionStore.kt" to "package com.piko.app.data",
            "transport/LocalSendProtocol.kt" to "package com.piko.app.transport",
            "transport/LocalSendHttpRoute.kt" to "package com.piko.app.transport",
            "transport/AndroidLocalSendMulticast.kt" to "package com.piko.app.transport",
            "transport/LocalSendHttpUploadClient.kt" to "package com.piko.app.transport",
            "transport/LocalSendHttpServer.kt" to "package com.piko.app.transport",
            "app/AuthModels.kt" to "package com.piko.app.app",
            "app/PikoAndroidAppShell.kt" to "package com.piko.app.app",
            "app/SendTransferStarter.kt" to "package com.piko.app.app",
            "design/PikoMiuixTheme.kt" to "package com.piko.app.design",
            "design/PikoMiuixComponents.kt" to "package com.piko.app.design",
            "feature/receive/ReceiveRoute.kt" to "package com.piko.app.feature.receive",
            "feature/send/SendRoute.kt" to "package com.piko.app.feature.send",
            "feature/settings/SettingsRoute.kt" to "package com.piko.app.feature.settings",
            "feature/friends/FriendsRoute.kt" to "package com.piko.app.feature.friends",
            "platform/DeviceNickname.kt" to "package com.piko.app.platform",
            "platform/SendPlatformActions.kt" to "package com.piko.app.platform",
            "platform/AndroidReceivePreferences.kt" to "package com.piko.app.platform",
            "platform/AndroidSendPlatformActions.kt" to "package com.piko.app.platform",
            "platform/AndroidPikoApp.kt" to "package com.piko.app.platform",
        )

        expectedPackages.forEach { (fileName, packageLine) ->
            val source = File(rootDir, "android/src/main/kotlin/com/piko/app/$fileName").readText()
            assertTrue(source.startsWith(packageLine), "$fileName should start with $packageLine")
        }

        val mainActivity = File(rootDir, "android/src/main/kotlin/com/piko/app/MainActivity.kt").readText()
        assertTrue("import com.piko.app.platform.AndroidPikoApp" in mainActivity)
        val legacyUiDir = File(rootDir, "android/src/main/kotlin/com/piko/app/ui")
        assertFalse(legacyUiDir.exists() && legacyUiDir.walkTopDown().any { it.isFile })
    }

    @Test
    fun receiveHistoryDeletionUsesSwipeConfirmationAndSavedFileReferencesOnBothPlatforms() {
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidReceive = readAndroid("feature/receive/ReceiveRoute.kt")
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/PikoHomeState.kt").readText()
        val androidStore = File(rootDir, "android/src/main/kotlin/com/piko/app/data/ReceiveHistoryStore.kt").readText()
        val androidLocalSendServer = File(rootDir, "android/src/main/kotlin/com/piko/app/transport/LocalSendHttpServer.kt").readText()
        val androidLegacyReceiver = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidSendPlatformActions.kt").readText()
        val iosReceive = readIos("NativeReceiveView.swift")
        val normalizedIosReceive = iosReceive.replace("\r\n", "\n")
        val iosModel = readIos("NativePikoModel.swift")
        val iosTransferModels = readIos("NativeTransferModels.swift")
        val iosReceiveFileStore = readIos("NativeReceiveFileStore.swift")

        assertTrue("ReceiveHistoryRow(" in androidReceive)
        assertTrue("TextButton(" in androidReceive)
        assertTrue("onClick = onDelete" in androidReceive)
        assertTrue("SuperDialog(" in androidReceive)
        assertTrue("summary = item.deleteConfirmationBody" in androidReceive)
        assertTrue("ButtonDefaults.buttonColorsPrimary()" in androidReceive)
        assertFalse("targetOffset" in androidReceive)
        assertFalse("val revealedWidth" in androidReceive)
        assertFalse(".background(MaterialTheme.colorScheme.error.copy(alpha = 0.28f" in androidReceive)
        assertTrue("DeleteReceiveHistoryDialog" in androidReceive)
        assertFalse("AlertDialog(" in androidReceive)
        assertTrue("onDeleteReceiveHistory(item, false)" in androidReceive)
        assertTrue("onDeleteReceiveHistory(item, true)" in androidReceive)
        assertTrue("只删记录" in androidReceive)
        assertTrue("删除记录与文件" in androidReceive)
        assertInOrder(androidReceive, "text = \"只删记录\"", "onClick = onDeleteRecord", "onClick = onDeleteRecordAndFiles")
        assertFalse("Checkbox(" in androidReceive)
        assertFalse("Dialog(onDismissRequest = onDismiss)" in androidReceive)
        assertFalse("同时删除文件" in androidReceive)
        assertTrue("删除" in androidReceive)
        assertTrue("真的要删除" in androidState)
        assertTrue("将会删除：" in androidState)
        assertTrue("removeReceiveHistory" in androidState)
        assertTrue("savedUri" in androidStore)
        assertTrue("savedUri = uri.toString()" in androidLocalSendServer)
        assertTrue("savedUri = uri.toString()" in androidLegacyReceiver)
        assertTrue("contentResolver.delete(Uri.parse(savedUri), null, null)" in androidApp)

        assertTrue("List {" in iosReceive)
        assertTrue(".listStyle(.insetGrouped)" in iosReceive)
        assertTrue(".swipeActions(edge: .trailing" in iosReceive)
        assertTrue(".confirmationDialog(" in iosReceive)
        assertTrue("Button(\"只删除记录\"" in iosReceive)
        assertTrue("Button(\"删除记录和文件\"" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertFalse("UITableView" in iosReceive)
        assertFalse("tableView." in iosReceive)
        assertFalse("swipeEditingIndexPath" in iosReceive)
        assertFalse("NativeSwipeToDeleteReceiveHistoryCard" in iosReceive)
        assertFalse("NativeReceiveHistoryCard" in iosReceive)
        assertFalse("NativeActiveReceiveCard" in iosReceive)
        assertFalse("static let historyRowSpacing" in iosReceive)
        assertFalse("let deleteWidth: CGFloat = 96" in iosReceive)
        assertFalse("DragGesture(minimumDistance: 12)" in iosReceive)
        assertFalse("pendingDeleteHistory = item" in iosReceive)
        assertTrue("delete(item, deleteFiles: false)" in iosReceive)
        assertTrue("delete(item, deleteFiles: true)" in iosReceive)
        assertFalse("NativeReceiveSwipeRow" in iosReceive)
        assertFalse("pendingDeleteItem" in iosReceive)
        assertFalse("UIImage(systemName:" in iosReceive)
        assertFalse("action.image" in iosReceive)
        assertFalse("NativeDeleteReceiveHistoryDialog" in iosReceive)
        assertFalse("deleteReceivedFiles" in iosReceive)
        assertFalse("同时删除文件" in iosReceive)
        assertTrue("deleteReceiveHistory" in iosModel)
        assertTrue("savedURLPath" in iosReceiveFileStore)
        assertTrue("photoAssetIdentifier" in iosReceiveFileStore)
        assertTrue("PHPhotoLibrary.requestAuthorization(for: .readWrite)" in iosReceiveFileStore)
        assertTrue("FileManager.default.removeItem(atPath: path)" in iosReceiveFileStore)
        assertTrue("真的要删除" in iosTransferModels)
        assertTrue("将会删除：" in iosTransferModels)
    }

    @Test
    fun iosReceiveRootUsesPageBackgroundThroughSystemBars() {
        val iosRoot = readIos("PikoRootView.swift")
        val iosReceive = readIos("NativeReceiveView.swift")

        assertTrue("TabView(selection:" in iosRoot)
        assertTrue("NavigationStack" in iosRoot)
        assertTrue("Label(\"接收\", systemImage: \"tray.and.arrow.down\")" in iosRoot)
        assertFalse("applyImmersiveConfiguration()" in iosRoot)
        assertFalse("PikoPalette.pageBackground.ignoresSafeArea()" in iosRoot)
        assertTrue("List {" in iosReceive)
        assertTrue(".scrollContentBackground(.visible)" in iosReceive)
        assertTrue(".navigationTitle(\"Piko\")" in iosReceive)
        assertTrue("NativeUnavailableRow(" in iosReceive)
        assertFalse("NativeReceiveTable(" in iosReceive)
        assertFalse("tableView." in iosReceive)
        assertFalse(".background(PikoPalette.pageBackground)" in iosReceive)
    }

    @Test
    fun appTextUsesAdaptiveTypographyForSmallAndLargeScreens() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/design/PikoMiuixTheme.kt").readText()
        val androidShell = File(rootDir, "android/src/main/kotlin/com/piko/app/app/PikoAndroidAppShell.kt").readText()
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/feature/receive/ReceiveRoute.kt").readText()
        val androidSend = File(rootDir, "android/src/main/kotlin/com/piko/app/feature/send/SendRoute.kt").readText()
        val androidSettings = File(rootDir, "android/src/main/kotlin/com/piko/app/feature/settings/SettingsRoute.kt").readText()
        val iosRoot = readIos("PikoRootView.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosSend = readIos("NativeSendView.swift")
        val iosSettings = readIos("NativeSettingsView.swift")
        val iosLogin = readIos("NativeLoginView.swift")
        val iosRegister = readIos("NativeRegisterView.swift")
        val iosFriends = readIos("NativeFriendsView.swift")
        val iosRequests = readIos("NativeFriendRequestsView.swift")
        val iosAppText = iosRoot + iosReceive + iosSend + iosSettings + iosLogin + iosRegister + iosFriends + iosRequests

        assertTrue("PikoMiuixTheme" in androidTheme)
        assertFalse("LocalConfiguration.current.screenWidthDp" in androidTheme)
        assertTrue("TopAppBar(" in androidShell)
        assertTrue("maxLines = 1" in androidReceive + androidSend)
        assertTrue("overflow = TextOverflow.Ellipsis" in androidReceive + androidSend)
        assertTrue("BasicComponent(" in androidSettings)

        assertTrue("List {" in iosReceive)
        assertTrue("List {" in iosSend)
        assertTrue("Form {" in iosSettings)
        assertTrue("Form {" in iosLogin)
        assertTrue("Form {" in iosRegister)
        assertTrue("List {" in iosFriends)
        assertTrue("List {" in iosRequests)
        assertTrue(".font(.headline)" in iosReceive + iosSend)
        assertTrue(".font(.subheadline)" in iosReceive + iosSend)
        assertTrue(".lineLimit(1)" in iosAppText)
        assertFalse("PikoCollapsingPageHeroHeader" in iosAppText)
        assertFalse(".font(PikoFont." in iosAppText)
    }

    private fun readIos(name: String): String =
        File(rootDir, "ios/$name").readText()

    private fun readAndroid(name: String): String =
        File(rootDir, "android/src/main/kotlin/com/piko/app/$name").readText()

    private fun assertInOrder(source: String, vararg snippets: String) {
        var cursor = 0
        snippets.forEach { snippet ->
            val index = source.indexOf(snippet, cursor)
            assertTrue(index >= 0, "Missing snippet in order: $snippet")
            cursor = index + snippet.length
        }
    }
}
