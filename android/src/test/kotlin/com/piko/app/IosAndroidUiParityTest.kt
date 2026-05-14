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
        val style = readIos("PikoStyle.swift")

        listOf(sendView, settingsView, receiveView).forEach { source ->
            assertFalse("NavigationView" in source)
            assertFalse("navigationTitle" in source)
            assertFalse("pageGradient" in source)
        }
        assertFalse("LinearGradient(" in style)

        assertInOrder(sendView, "我的设备", "局域网设备", "我的好友", "NativeImageSection", "NativeFileSection")
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
        assertTrue("NativeReceiveHistoryRow" in receiveView)
    }

    @Test
    fun authLabelsParityAcrossPlatforms() {
        val androidLabels = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/UiLabels.kt").readText()
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
        val androidSettings = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/SettingsScreen.kt").readText()
        val androidLabels = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/UiLabels.kt").readText()
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
        assertTrue("friendsEntry" in androidLabels)

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
        assertTrue("PikoCollapsingPageHeroHeader" in iosFriendsView)
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
        assertTrue(androidSignaling.contains("/v1/signaling/ws?device_id="))
        assertTrue("fun addListener(listener: (JSONObject) -> Unit)" in androidSignaling)
        assertTrue("stun:stun.cloudflare.com:3478" in backendIce)
        assertTrue("stun:stun.cloudflare.com:53" in backendIce)
        assertFalse("turn:" in backendIce)
        assertTrue("fun defaultP2PIceServers()" in androidSessionApi)
        assertTrue("optJSONArray(\"ice_servers\")" in androidSessionApi)
        assertTrue("IceServerConfig(\"stun:stun.cloudflare.com:3478\")" in androidSessionApi)
        assertTrue("IceServerConfig(\"stun:stun.cloudflare.com:53\")" in androidSessionApi)
        assertTrue("static let defaultP2P" in iosSessionApi)
        assertTrue("NativeIceServerConfig.parse(json[\"ice_servers\"] as? [[String: Any]])" in iosSessionApi)
        assertTrue("NativeIceServerConfig(urls: \"stun:stun.cloudflare.com:3478\")" in iosSessionApi)
        assertTrue("NativeIceServerConfig(urls: \"stun:stun.cloudflare.com:53\")" in iosSessionApi)
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
        assertTrue("receiver_x25519_eph_pub_b64" in iosP2P)
        assertTrue("sender_invite_signature_b64" in iosP2P)
        assertTrue("receiver_accept_signature_b64" in iosP2P)
        assertTrue("completed_chunks_bitmap_b64" in iosP2P)
        assertTrue("NativeTransferProgressStore.decodeCompletedBitmap" in iosP2P)
        assertTrue("sanitizeCompletedChunks" in iosP2P)
        assertTrue("chunkData(transferId: transferId, fileIndex: index, chunkIndex: chunkIndex)?.count" in iosP2P)
        assertTrue("autoAccept: (message[\"same_account\"] as? Bool) ?? false" in iosP2P)
        assertTrue("publishReceiveState(requiresConfirmation: !autoAccept)" in iosP2P)
        assertTrue("ReceiveConfirmDialog(" in androidApp)
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
        assertTrue("frameReady = 0x02" in iosTransferV3)
        assertTrue("frameAck = 0x04" in iosTransferV3)
        assertTrue("struct NativeTransferV3ManifestInput" in iosTransferV3)
        assertTrue("@MainActor\n    func sendSelectedItems()" in iosModel)
        assertTrue("@MainActor\n    func acceptReceiveTransfer()" in iosModel)
        assertTrue("@MainActor\n    func cancelReceiveTransfer()" in iosModel)
        assertTrue("p2pTransferClient.send(" in iosModel)
        assertTrue("@Published var transferFailureMessage: String?" in iosModel)
        assertTrue("p2pFailureMessage(target: target, transferId: transferId, error: error)" in iosModel)
        assertTrue(".alert(\"P2P 传输失败\"" in readIos("NativeSendView.swift"))
        assertTrue("AlertDialog(" in readAndroid("ui/SendScreen.kt"))
        assertTrue("P2P 传输失败" in readAndroid("ui/SendScreen.kt"))
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
            "offer_sent：",
            "answer_received：",
            "local_ice_count：",
            "remote_ice_count：",
            "ice_connection_state：",
            "data_channel_state：",
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
            "data class P2PTransferDiagnostic(",
            "fun diagnosticSnapshot()",
            "localIceCount += 1",
            "remoteIceCount += 1",
            "answerReceived = true",
            "iceConnectionState = state.name",
            "dataChannelState = channel.state().name",
        ).forEach { marker ->
            assertTrue(marker in androidP2P, "Android P2P must record WebRTC diagnostic marker $marker")
        }
        listOf(
            "struct NativeWebRTCDiagnostic",
            "var diagnosticSnapshot",
            "localIceCount += 1",
            "remoteIceCount += 1",
            "answerReceived = true",
            "iceConnectionState = value",
            "dataChannelState = value",
        ).forEach { marker ->
            assertTrue(marker in iosWebRTC, "iOS WebRTC must record diagnostic marker $marker")
        }
        listOf("create_session", "data_channel_open", "key_agreement", "send_manifest", "receiver_ready", "send_chunk", "ack").forEach { stage ->
            assertTrue(stage in androidP2P, "Android P2P must keep failure stage $stage")
            assertTrue(stage in iosP2P, "iOS P2P must keep failure stage $stage")
        }
        assertFalse("WebRTC DataChannel 尚未接入" in iosModel)
        assertTrue("signalingClient.connect(token, identity.deviceId)" in androidApp)
        assertTrue("signalingClient.connect(token: token, deviceId: identity.deviceId)" in iosModel)
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
        assertTrue("selectedTab == PikoTab.Send" in androidApp)
        assertTrue("settingsDestination == SettingsDestination.Friends" in androidApp)
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
    fun androidUsesFixedIosPaletteInsteadOfDynamicSystemColors() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/PikoTheme.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val sharedApp = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/App.kt").readText()
        val bottomTabs = File(rootDir, "android/src/main/kotlin/com/piko/app/glass/LiquidBottomTabs.kt").readText()
        val iosStyle = readIos("PikoStyle.swift")

        assertTrue("UIColor.systemBackground" in iosStyle)
        assertTrue("UIColor.secondarySystemBackground" in iosStyle)
        assertTrue("UIColor.systemBlue" in iosStyle)
        assertTrue("IOS_SYSTEM_BLUE_LIGHT = Color(0xFF007AFF)" in androidTheme)
        assertTrue("IOS_SYSTEM_BLUE_DARK = Color(0xFF0A84FF)" in androidTheme)
        assertTrue("IOS_SECONDARY_SYSTEM_BACKGROUND_LIGHT = Color(0xFFF2F2F7)" in androidTheme)
        assertTrue("IOS_SECONDARY_SYSTEM_BACKGROUND_DARK = Color(0xFF1C1C1E)" in androidTheme)
        assertTrue("PikoTheme {" in androidApp)
        assertTrue("PikoTheme {" in sharedApp)
        assertTrue("PikoColors.accent" in bottomTabs)
        assertFalse("dynamicLightColorScheme" in androidTheme + androidApp + sharedApp)
        assertFalse("dynamicDarkColorScheme" in androidTheme + androidApp + sharedApp)
        assertFalse("Color(0xFF0088FF)" in bottomTabs)
        assertFalse("Color(0xFF0091FF)" in bottomTabs)
    }

    @Test
    fun iosContentIconsStayLucideSourcedLikeAndroid() {
        val style = readIos("PikoStyle.swift")
        val receiveView = readIos("NativeReceiveView.swift")
        val sendItemSection = readIos("NativeSendItemSection.swift")
        val sendDeviceSection = readIos("NativeSendDeviceSection.swift")
        val transferSection = readIos("NativeSendTransferSection.swift")

        assertTrue("case inbox" in style)
        assertTrue("M22,12H16l-2,3H10l-2,-3H2" in style)
        assertTrue("case file" in style)
        assertTrue("case image" in style)
        assertTrue("case plus" in style)
        assertTrue("case x" in style)
        assertTrue("case check" in style)
        assertTrue("case smartphone" in style)
        assertTrue("case refreshCw" in style)
        assertTrue("M7,2h10a2,2 0,0 1,2 2v16a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2z" in style)
        assertTrue("M3,12a9,9 0,0 1,9 -9 9.75,9.75 0,0 1,6.74 2.74L21,8" in style)

        val iosContent = receiveView + sendItemSection + sendDeviceSection + transferSection
        listOf("inbox", "file", "image", "plus", "x", "check", "smartphone", "refreshCw").forEach { name ->
            assertTrue("LucideTabIcon.$name.image" in iosContent)
        }
        assertFalse("Image(systemName: \"tray" in iosContent)
        assertFalse("Image(systemName: \"doc" in iosContent)
        assertFalse("Image(systemName: \"photo" in iosContent)
        assertFalse("Image(systemName: \"plus" in iosContent)
        assertFalse("Image(systemName: \"xmark" in iosContent)
        assertFalse("Image(systemName: \"checkmark" in iosContent)
    }

    @Test
    fun iosSendDeviceSubtitleKeepsAndroidOptionalContract() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/SendPageState.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosDeviceSection = readIos("NativeSendDeviceSection.swift")

        assertTrue("val subtitle: String? = null" in androidState)
        assertTrue("let subtitle: String?" in iosModel)
        assertTrue("if let subtitle = device.subtitle" in iosDeviceSection)
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
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/ReceiveScreen.kt").readText()
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
        assertTrue("LucideSmartphoneIcon" in androidReceive)
        assertTrue("LucideRefreshCwIcon" in androidReceive)
        assertTrue("text = nickname" in androidReceive)
        assertTrue("text = \"本设备名称\"" in androidReceive)
        assertTrue("换个昵称" in androidReceive)
        assertTrue("LucideTabIcon.smartphone.image" in iosReceive)
        assertTrue("LucideTabIcon.refreshCw.image" in iosReceive)
        assertTrue("Text(nickname)" in iosReceive)
        assertTrue("Text(\"本设备名称\")" in iosReceive)
        assertFalse("Image(uiImage: LucideTabIcon.inbox.image)" in iosReceive.substringBefore("private struct NativeReceiveEmptyState"))
        assertFalse("Image(systemName:" in iosReceive)
        assertTrue("换个昵称" in iosReceive)
        assertTrue("subtitle = nickname.code" in androidDiscovery)
        assertFalse("subtitle = resolvedService.host?.hostAddress" in androidDiscovery)
    }

    @Test
    fun receivePagesUseCompactActiveProgressAndMediaPreview() {
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/ReceiveScreen.kt").readText()
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
        assertTrue("RoundedRectProgressIndicator" in androidReceive)
        assertTrue("RoundedRectangle(cornerRadius: 18, style: .continuous)\n                .trim" in iosReceive)
        assertTrue("style = MaterialTheme.typography.bodyLarge" in androidReceive)
        assertTrue(".font(PikoFont.compactTitle)" in iosReceive)
        assertTrue(".offset(x = (-8).dp)" in androidReceive)
        assertTrue(".offset(x: -8)" in iosReceive)
        assertTrue("mediaPreviewDescription" in androidState)
        assertTrue("isMediaPreview" in androidState)
        assertTrue("MediaThumbnailPreview" in androidReceive)
        assertTrue("mediaPreviewData" in iosReceiveFileStore)
        assertTrue("NativeMediaPreview" in iosReceive)
        assertTrue("AVAssetImageGenerator" in iosReceiveFileStore)
        assertTrue("mediaPreviewImageData(" in iosReceiveFileStore)
        assertTrue("UIGraphicsImageRenderer" in iosReceiveFileStore)
        assertTrue("jpegData(compressionQuality: 0.82)" in iosReceiveFileStore)
        assertFalse("case .image:\n            return fallbackData" in iosReceiveFileStore.replace("\r\n", "\n"))
        assertFalse("UIImage(data: $0)?.receiveListPixelDescription" in iosReceive)
        assertTrue("mediaPreview(bytes:" in iosReceive)
        assertTrue("receiveListImageDescription" in iosReceive)
        assertTrue("file.fileType.isMediaPreview" in androidDiscovery)
        assertTrue("file.fileType.isMediaPreview" in androidLocalSendServer)
    }

    @Test
    fun iosReceiveFileListUsesNativeTableAndKeepsRequiredControls() {
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/ReceiveScreen.kt").readText()
        val iosReceive = readIos("NativeReceiveView.swift")

        assertTrue("modifier = Modifier.weight(1f)" in androidReceive)
        assertInOrder(iosReceive, "NativeReceiveTable(", "model: model")
        assertTrue("private struct NativeReceiveTable: UIViewControllerRepresentable" in iosReceive)
        assertTrue("final class NativeReceiveTableViewController: UITableViewController" in iosReceive)
        assertTrue("UIContextualAction(style: .destructive, title: \"删除\")" in iosReceive)
        assertTrue("deleteAction.backgroundColor = UIColor.systemRed" in iosReceive)
        assertTrue("UISwipeActionsConfiguration(actions: [deleteAction])" in iosReceive)
        assertFalse("private final class NativeReceiveSpacerCell: UITableViewCell" in iosReceive)
        assertFalse("return NativeReceiveSpacerCell(" in iosReceive)
        assertFalse("case .spacer:\n            return AnyView(" in iosReceive.replace("\r\n", "\n"))
        assertTrue("visibleCells=" in iosReceive)
        assertTrue("swiftUILayout event=" in iosReceive)
        assertTrue("global:" in iosReceive)
        assertTrue("hostingCell layout item=" in iosReceive)
        assertFalse("spacerCell layout expected=" in iosReceive)
        assertFalse("tableView.cellForRow(at:" in iosReceive)
        assertTrue("tableView.visibleCells" in iosReceive)
        val didEndDisplayingBlock = Regex("""override func tableView\(_ tableView: UITableView, didEndDisplaying[\s\S]*?\n    }\n""")
            .find(iosReceive)
            ?.value
            ?: error("didEndDisplaying must remain inspectable")
        assertTrue("detachHost()" in didEndDisplayingBlock)
        val willDisplayBlock = Regex("""override func tableView\(_ tableView: UITableView, willDisplay[\s\S]*?\n    }\n""")
            .find(iosReceive)
            ?.value
            ?: error("willDisplay must remain inspectable")
        assertFalse("logTableGeometry(" in willDisplayBlock)
        assertTrue("scrollViewDidScroll" in iosReceive)
        assertTrue("postReloadAsync" in iosReceive)
        assertTrue("receiveListInsetsDescription" in iosReceive)
        assertTrue("NSLog(\"%@\", message)" in iosReceive)
        assertTrue("configuration.performsFirstActionWithFullSwipe = false" in iosReceive)
        assertTrue("UIAlertController(title: item.deleteConfirmationTitle" in iosReceive)
        assertTrue("swipeCompletion(false)" in iosReceive)
        assertTrue("swipeCompletion(true)" in iosReceive)
        assertTrue("tableView.deleteRows(at: [indexPath], with: .automatic)" in iosReceive)
        assertTrue("private var isApplyingAnimatedDelete = false" in iosReceive)
        assertTrue("static let fileRowTrailingInset: CGFloat = 24" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertTrue("NativeReceiveHistoryPreview(item: item)" in iosReceive)
        assertTrue("historyPreviewFrame(" in iosReceive)
        assertTrue("historyPreviewBranch(kind:media" in iosReceive)
        assertTrue("historyTextColumn(" in iosReceive)
        assertTrue("historyTitle(" in iosReceive)
        assertTrue("historySubtitle(" in iosReceive)
        assertTrue("NativeActiveReceiveRow(" in iosReceive)
        assertTrue("NativeActiveReceiveProgressIcon(transfer: transfer)" in iosReceive)
        assertFalse("ProgressView(value: transfer.progress)" in iosReceive)
        assertTrue("Button(action: onCancel)" in iosReceive)
        assertTrue("Text(transfer.title)" in iosReceive)
        assertTrue("Text(transfer.subtitle)" in iosReceive)
        assertTrue("Text(item.title)" in iosReceive)
        assertTrue("Text(item.subtitle)" in iosReceive)
        assertTrue("private struct NativeReceiveTextColumn<Content: View>: View" in iosReceive)
        assertTrue(".frame(maxWidth: .infinity, alignment: .leading)" in iosReceive)
        assertTrue(".layoutPriority(1)" in iosReceive)
        assertFalse("List {" in iosReceive)
        assertFalse(".listStyle(.plain)" in iosReceive)
        assertFalse(".swipeActions(edge: .trailing" in iosReceive)
        assertFalse("Button(\"删除\", role: .destructive)" in iosReceive)
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
            "ui/App.kt" to "package com.piko.app.ui",
            "ui/PikoTheme.kt" to "package com.piko.app.ui",
            "ui/PikoIcons.kt" to "package com.piko.app.ui",
            "ui/PikoUiChrome.kt" to "package com.piko.app.ui",
            "ui/ReceiveScreen.kt" to "package com.piko.app.ui",
            "ui/SendDeviceComponents.kt" to "package com.piko.app.ui",
            "ui/SendFileComponents.kt" to "package com.piko.app.ui",
            "ui/SendImageComponents.kt" to "package com.piko.app.ui",
            "ui/SendPlatformImageThumbnail.kt" to "package com.piko.app.ui",
            "ui/SendScreen.kt" to "package com.piko.app.ui",
            "ui/SendSelectionComponents.kt" to "package com.piko.app.ui",
            "ui/SendTransferComponents.kt" to "package com.piko.app.ui",
            "ui/SettingsScreen.kt" to "package com.piko.app.ui",
            "ui/UiLabels.kt" to "package com.piko.app.ui",
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
    }

    @Test
    fun receiveHistoryDeletionUsesSwipeConfirmationAndSavedFileReferencesOnBothPlatforms() {
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/ReceiveScreen.kt").readText()
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/domain/PikoHomeState.kt").readText()
        val androidStore = File(rootDir, "android/src/main/kotlin/com/piko/app/data/ReceiveHistoryStore.kt").readText()
        val androidLocalSendServer = File(rootDir, "android/src/main/kotlin/com/piko/app/transport/LocalSendHttpServer.kt").readText()
        val androidLegacyReceiver = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidSendPlatformActions.kt").readText()
        val iosReceive = readIos("NativeReceiveView.swift")
        val normalizedIosReceive = iosReceive.replace("\r\n", "\n")
        val iosModel = readIos("NativePikoModel.swift")
        val iosTransferModels = readIos("NativeTransferModels.swift")
        val iosReceiveFileStore = readIos("NativeReceiveFileStore.swift")

        assertTrue("detectHorizontalDragGestures" in androidReceive)
        assertTrue("val deleteWidth = 96.dp" in androidReceive)
        assertTrue("val deleteButtonOffset" in androidReceive)
        assertTrue(".width(deleteWidth)" in androidReceive)
        assertTrue(".clip(RoundedCornerShape(20.dp))" in androidReceive)
        assertTrue("copy(alpha = revealFraction)" in androidReceive)
        assertFalse("targetOffset" in androidReceive)
        assertFalse("val revealedWidth" in androidReceive)
        assertFalse(".background(MaterialTheme.colorScheme.error.copy(alpha = 0.28f" in androidReceive)
        assertTrue("DeleteReceiveHistoryDialog" in androidReceive)
        assertTrue("AlertDialog(" in androidReceive)
        assertTrue("onDeleteReceiveHistory(history, false)" in androidReceive)
        assertTrue("onDeleteReceiveHistory(history, true)" in androidReceive)
        assertTrue("仅删除记录" in androidReceive)
        assertTrue("删除记录与文件" in androidReceive)
        assertTrue("Column(horizontalAlignment = Alignment.End)" in androidReceive)
        assertInOrder(androidReceive, "Text(text = \"算了\")", "Text(text = \"仅删除记录\")", "text = \"删除记录与文件\"")
        assertFalse("Checkbox(" in androidReceive)
        assertFalse("Dialog(onDismissRequest = onDismiss)" in androidReceive)
        assertFalse("同时删除文件" in androidReceive)
        assertTrue("算了" in androidReceive)
        assertTrue("删除" in androidReceive)
        assertTrue("真的要删除" in androidState)
        assertTrue("将会删除：" in androidState)
        assertTrue("removeReceiveHistory" in androidState)
        assertTrue("savedUri" in androidStore)
        assertTrue("savedUri = uri.toString()" in androidLocalSendServer)
        assertTrue("savedUri = uri.toString()" in androidLegacyReceiver)
        assertTrue("contentResolver.delete(Uri.parse(savedUri), null, null)" in androidApp)

        assertTrue("static let pageHorizontalInset: CGFloat = 24" in iosReceive)
        assertTrue("static let contentTrailingInset: CGFloat = 24" in iosReceive)
        assertTrue("static let bottomSpacerHeight: CGFloat = 32" in iosReceive)
        assertTrue("static let bottomReadableClearance: CGFloat = 56" in iosReceive)
        assertTrue("static func readableBottomInset(for safeAreaBottom: CGFloat) -> CGFloat" in iosReceive)
        assertTrue("applyReadableContentInsets(reason: \"viewDidLoad\")" in iosReceive)
        assertTrue("applyReadableContentInsets(reason: \"viewDidLayoutSubviews\")" in iosReceive)
        assertTrue("tableView.contentInset = nextInset" in iosReceive)
        assertTrue("tableView.scrollIndicatorInsets = nextInset" in iosReceive)
        assertTrue("readableInsets reason=" in iosReceive)
        assertFalse("tableView.contentInset = .zero" in iosReceive)
        assertFalse("tableView.scrollIndicatorInsets = .zero" in iosReceive)
        assertTrue("case history(NativeReceiveHistoryItem)" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertFalse("swipeEditingIndexPath" in iosReceive)
        assertFalse("NativeSwipeToDeleteReceiveHistoryCard" in iosReceive)
        assertFalse("NativeReceiveHistoryCard" in iosReceive)
        assertFalse("NativeActiveReceiveCard" in iosReceive)
        assertFalse("static let historyRowSpacing" in iosReceive)
        assertFalse("let deleteWidth: CGFloat = 96" in iosReceive)
        assertFalse("DragGesture(minimumDistance: 12)" in iosReceive)
        assertFalse(".swipeActions(edge: .trailing" in iosReceive)
        assertFalse("Button(\"删除\", role: .destructive)" in iosReceive)
        assertFalse("pendingDeleteHistory = item" in iosReceive)
        assertTrue("delete(item, deleteFiles: false, swipeCompletion: swipeCompletion)" in iosReceive)
        assertTrue("delete(item, deleteFiles: true, swipeCompletion: swipeCompletion)" in iosReceive)
        assertTrue("animateDelete(item, at: indexPath)" in iosReceive)
        assertInOrder(iosReceive, "model.deleteReceiveHistory(item, deleteFiles: deleteFiles)", "animateDelete(item, at: indexPath)", "swipeCompletion(true)")
        assertTrue("仅删除记录" in iosReceive)
        assertTrue("删除记录与文件" in iosReceive)
        assertInOrder(iosReceive, "UIAlertAction(title: \"算了\"", "UIAlertAction(title: \"仅删除记录\"", "UIAlertAction(title: \"删除记录与文件\"")
        assertInOrder(iosReceive, "NativeReceiveTable(", "model: model")
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
        val iosStyle = readIos("PikoStyle.swift")

        assertTrue("static let pageBackgroundUIColor = surfaceUIColor" in iosStyle)
        assertTrue("PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("view.window?.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("view.superview?.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("applyImmersiveConfiguration()" in iosRoot)
        assertTrue(".ignoresSafeArea(.container, edges: .bottom)" in iosReceive)
        assertTrue("PikoPalette.pageBackground.ignoresSafeArea()" in iosRoot)
        assertInOrder(iosReceive, "NativeReceiveTable(", "model: model")
        assertTrue("tableView.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosReceive)
        assertTrue("tableView.contentInsetAdjustmentBehavior = .automatic" in iosReceive)
        assertTrue("static let deviceNicknameBottomSpacing: CGFloat = 8" in iosReceive)
        assertTrue("static let deviceNicknameVerticalPadding: CGFloat = 9" in iosReceive)
        assertTrue("static let emptyStateTopSpacing: CGFloat = 24" in iosReceive)
        assertTrue("static let emptyStateBottomSpacing: CGFloat = 112" in iosReceive)
        assertTrue("static let emptyStateMinimumContentHeight: CGFloat = 164" in iosReceive)
        assertTrue("emptyStateCardView(" in iosReceive)
        assertTrue("private struct NativeReceiveEmptyStateContent: View" in iosReceive)
        assertTrue(".background(PikoPalette.pageBackground)" in iosReceive)
        assertFalse("static func emptyStateRowHeight(for tableHeight: CGFloat)" in iosReceive)
        assertFalse("emptyStateHeightCacheKeyPrefix" in iosReceive)
        assertFalse("let emptyStateShape = RoundedRectangle(cornerRadius: 24, style: .continuous)" in iosReceive)
        assertFalse(".fill(Color.secondary.opacity(0.08))" in iosReceive)
        assertFalse(".clipShape(emptyStateShape)" in iosReceive)
        assertTrue(".frame(maxWidth: .infinity)" in iosReceive)
        assertTrue(".frame(minHeight: NativeReceiveLayout.emptyStateMinimumContentHeight)" in iosReceive)
        assertFalse("static let emptyStateRowHeight: CGFloat = 300" in iosReceive)
        assertFalse("emptyStateEstimatedHeight" in iosReceive)
        assertFalse("PikoEmptyPlane(text: \"还没有接收过文件\")" in iosReceive)
        assertFalse(".frame(maxWidth: .infinity, height: height, alignment: .top)" in iosReceive)
        assertFalse("rowView(bottom: 136)" in iosReceive)
        assertFalse("return 156" in iosReceive)
        assertFalse("return 82" in iosReceive)
        assertTrue(".strokeBorder(Color.secondary.opacity(0.16), lineWidth: 1)" in iosReceive)
        assertFalse(".stroke(Color.secondary.opacity(0.16), lineWidth: 1)" in iosReceive)
    }

    @Test
    fun appTextUsesAdaptiveTypographyForSmallAndLargeScreens() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/PikoTheme.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/platform/AndroidPikoApp.kt").readText()
        val androidSettings = File(rootDir, "android/src/main/kotlin/com/piko/app/ui/SettingsScreen.kt").readText()
        val iosStyle = readIos("PikoStyle.swift")
        val iosRoot = readIos("PikoRootView.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosSendTransfer = readIos("NativeSendTransferSection.swift")
        val iosSendDevice = readIos("NativeSendDeviceSection.swift")
        val iosSendItem = readIos("NativeSendItemSection.swift")
        val iosSettings = readIos("NativeSettingsView.swift")
        val iosAppText = iosRoot + iosReceive + iosSendTransfer + iosSendDevice + iosSendItem + iosSettings

        assertTrue("internal object PikoTypography" in androidTheme)
        assertTrue("LocalConfiguration.current.screenWidthDp" in androidTheme)
        assertTrue("widthDp <= 375 -> PikoScreenTextScale.Compact" in androidTheme)
        assertTrue("widthDp >= 430 -> PikoScreenTextScale.Expanded" in androidTheme)
        assertTrue("Compact(0.92f)" in androidTheme)
        assertTrue("Expanded(1.06f)" in androidTheme)
        assertTrue("typography = PikoTypography.current()" in androidTheme)
        assertFalse("typography = MaterialTheme.typography" in androidTheme)
        assertTrue("maxLines = 1" in androidApp)
        assertTrue("overflow = TextOverflow.Ellipsis" in androidApp)
        assertTrue("maxLines = 1" in androidSettings)
        assertTrue("overflow = TextOverflow.Ellipsis" in androidSettings)

        assertTrue("enum PikoFont" in iosStyle)
        assertTrue("UIScreen.main.bounds" in iosStyle)
        assertTrue("case compact" in iosStyle)
        assertTrue("case regular" in iosStyle)
        assertTrue("case expanded" in iosStyle)
        assertTrue("compact: return 0.92" in iosStyle)
        assertTrue("expanded: return 1.06" in iosStyle)
        assertTrue("static var pageTitle" in iosStyle)
        assertTrue("static var rowTitle" in iosStyle)
        assertTrue("static var pill" in iosStyle)
        assertTrue("static var tabLabel" in iosStyle)
        assertFalse("textStyle: .caption," in iosStyle)
        assertTrue("textStyle: .caption1" in iosStyle)
        assertFalse(".font(.largeTitle" in iosStyle)
        assertFalse(".font(.title3" in iosAppText)
        assertTrue(".font(PikoFont.tabLabel)" in iosRoot)
        assertTrue(".font(PikoFont.rowTitle)" in iosReceive)
        assertTrue(".minimumScaleFactor(0.88)" in iosAppText)
        assertTrue(".truncationMode(.tail)" in iosAppText)
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
