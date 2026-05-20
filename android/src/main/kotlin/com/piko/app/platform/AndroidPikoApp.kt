package com.piko.app.platform

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.juren233.piko.BuildConfig
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.juren233.piko.R
import com.piko.app.data.AuthRepository
import com.piko.app.data.AuthTokenStore
import com.piko.app.data.DeviceIdentityStore
import com.piko.app.data.FriendsRepository
import com.piko.app.data.ReceiveHistoryStore
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.AuthState
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.ReceiveTransferEvent
import com.piko.app.domain.ReceiveTransferState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.glass.LiquidBottomTab
import com.piko.app.glass.LiquidBottomTabs
import com.piko.app.glass.LiquidSendFloatingButton
import com.piko.app.transport.AccountApiClient
import com.piko.app.transport.DeviceApiClient
import com.piko.app.transport.FriendApiClient
import com.piko.app.transport.SignalingWebSocketClient
import com.piko.app.ui.App
import com.piko.app.ui.AuthSection
import com.piko.app.ui.FriendsEntry
import com.piko.app.ui.IOS_SYSTEM_BACKGROUND_DARK
import com.piko.app.ui.IOS_SYSTEM_BACKGROUND_LIGHT
import com.piko.app.ui.PikoColors
import com.piko.app.ui.PikoTab
import com.piko.app.ui.PikoTabScreen
import com.piko.app.ui.PikoTheme
import com.piko.app.ui.SettingsDestination
import com.piko.app.ui.startSendTransfer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AndroidPikoApp() {
    val appContext = LocalContext.current.applicationContext
    val nicknameRepository = remember(appContext) {
        DeviceNicknameRepository(AndroidDeviceNicknameStorage(appContext))
    }
    val receivePreferences = remember(appContext) {
        AndroidReceivePreferences(appContext)
    }
    val receiveHistoryStore = remember(appContext) {
        ReceiveHistoryStore.fromContext(appContext)
    }
    var currentNickname by remember(nicknameRepository) { mutableStateOf(nicknameRepository.loadOrCreate()) }
    var selectedTab by remember { mutableStateOf(PikoTab.Receive) }
    var state by remember {
        mutableStateOf(
            PikoHomeState.initial(
                currentDeviceName = currentNickname.fullName,
                receiveHistory = receiveHistoryStore.load(),
            ),
        )
    }
    var mediaSaveLocation by remember {
        mutableStateOf(receivePreferences.loadMediaSaveLocation())
    }
    var settingsDestination by remember { mutableStateOf(SettingsDestination.Settings) }
    var friendSearchQuery by remember { mutableStateOf("") }
    val mutateState: (((PikoHomeState) -> PikoHomeState) -> Unit) = { transform ->
        val nextState = transform(state)
        if (nextState.receiveHistory != state.receiveHistory) {
            receiveHistoryStore.save(nextState.receiveHistory)
        }
        state = nextState
    }
    val tokenStore = remember(appContext) {
        AuthTokenStore.fromContext(appContext)
    }
    val deviceIdentityStore = remember(appContext) {
        DeviceIdentityStore.fromContext(appContext)
    }
    val deviceApiClient = remember(appContext) {
        DeviceApiClient(baseUrl = BuildConfig.PIKO_API_BASE_URL)
    }
    val signalingClient = remember(appContext) {
        SignalingWebSocketClient(
            baseUrl = BuildConfig.PIKO_API_BASE_URL,
            onMessage = { },
        )
    }
    val sendPlatformActions = rememberAndroidSendPlatformActions(
        currentNickname = currentNickname,
        mediaSaveLocation = mediaSaveLocation,
        tokenStore = tokenStore,
        deviceIdentityStore = deviceIdentityStore,
        signalingClient = signalingClient,
        apiBaseUrl = BuildConfig.PIKO_API_BASE_URL,
        onReceiveTransferEvent = { event ->
            if (event is ReceiveTransferEvent.Notice) {
                Toast.makeText(appContext, event.message, Toast.LENGTH_SHORT).show()
            }
            mutateState { current -> current.applyReceiveTransferEvent(event) }
        },
    )
    val backdrop = rememberLayerBackdrop()

    val authRepository = remember(appContext) {
        AuthRepository(
            api = AccountApiClient(baseUrl = BuildConfig.PIKO_API_BASE_URL),
            tokenStore = tokenStore,
        )
    }
    val friendsRepository = remember(appContext) {
        FriendsRepository(
            api = FriendApiClient(baseUrl = BuildConfig.PIKO_API_BASE_URL),
            tokenStore = tokenStore,
        )
    }
    val authState by authRepository.state.collectAsState()
    val friendsState by friendsRepository.state.collectAsState()
    var lastAuthError by remember(authRepository) { mutableStateOf<AccountError?>(null) }
    val authScope = rememberCoroutineScope()
    val friendsScope = rememberCoroutineScope()
    fun refreshFriendsPresence() {
        if (authState !is AuthState.Authenticated) return
        friendsScope.launch {
            friendsRepository.refreshAll()
        }
    }

    LaunchedEffect(authRepository) {
        authRepository.bootstrap()
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            val token = tokenStore.load()
            if (token != null) {
                val identity = deviceIdentityStore.loadOrCreate()
                when (
                    val result = deviceApiClient.registerDevice(
                        token = token,
                        identity = identity,
                        deviceName = currentNickname.fullName,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                ) {
                    is AccountResult.Ok -> Unit
                    is AccountResult.Err -> lastAuthError = result.error
                }
                signalingClient.connect(token, identity.deviceId)
            }
            refreshFriendsPresence()
        } else {
            signalingClient.close()
            friendsRepository.clear()
            settingsDestination = SettingsDestination.Settings
        }
    }

    AppLifecycleForegroundObserver(onForeground = ::refreshFriendsPresence)

    LaunchedEffect(selectedTab, authState) {
        if (selectedTab == PikoTab.Send) {
            refreshFriendsPresence()
        }
    }

    LaunchedEffect(selectedTab, settingsDestination, authState) {
        if (selectedTab == PikoTab.Settings && settingsDestination == SettingsDestination.Friends) {
            refreshFriendsPresence()
        }
    }

    LaunchedEffect(friendsState.friends, friendsState.friendDevices) {
        val friendDevices = friendsState.friendDevices.values.flatten()
        mutateState { current ->
            current.copy(
                friendsState = friendsState,
                sendPage = current.sendPage.replaceFriendDevices(friendDevices),
            )
        }
    }

    LaunchedEffect(friendsState.incoming, friendsState.outgoing, friendsState.searchResults, friendsState.error) {
        mutateState { current -> current.copy(friendsState = friendsState) }
    }

    LaunchedEffect(friendSearchQuery) {
        delay(300)
        if (friendSearchQuery.length >= 2) {
            friendsRepository.search(friendSearchQuery)
        }
    }

    val authSection = AuthSection(
        state = authState,
        lastError = lastAuthError,
        onLogin = { email, password ->
            authScope.launch {
                val res = authRepository.login(email, password)
                lastAuthError = if (res is AccountResult.Err) res.error else null
            }
        },
        onRegister = { email, password, username, nickname ->
            authScope.launch {
                val res = authRepository.register(email, password, username, nickname)
                lastAuthError = if (res is AccountResult.Err) res.error else null
            }
        },
        onSignOut = {
            authScope.launch {
                authRepository.logout()
                lastAuthError = null
            }
        },
        onErrorConsumed = { lastAuthError = null },
    )

    PikoTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
                color = MaterialTheme.colorScheme.background,
            ) {
                PikoTabScreen(
                    tab = selectedTab,
                    state = state,
                    onStateMutate = mutateState,
                    onResetCurrentDeviceName = {
                        currentNickname = nicknameRepository.regenerate()
                        state = state.copy(currentDeviceName = currentNickname.fullName)
                    },
                    mediaSaveLocation = mediaSaveLocation,
                    onMediaSaveLocationChange = { location ->
                        mediaSaveLocation = location
                        receivePreferences.saveMediaSaveLocation(location)
                    },
                    sendPlatformActions = sendPlatformActions,
                    authSection = authSection,
                    friendsEntry = FriendsEntry(
                        enabled = authState is AuthState.Authenticated,
                        friendCount = friendsState.friends.size,
                        pendingCount = friendsState.pendingIncomingCount,
                        onClick = {
                            selectedTab = PikoTab.Settings
                            settingsDestination = SettingsDestination.Friends
                        },
                    ),
                    settingsDestination = settingsDestination,
                    friendSearchQuery = friendSearchQuery,
                    onFriendSearchQueryChange = { friendSearchQuery = it },
                    onFriendRequestsClick = { settingsDestination = SettingsDestination.FriendRequests },
                    onSendFriendRequest = { userId -> friendsScope.launch { friendsRepository.sendRequest(userId) } },
                    onAcceptFriendRequest = { requestId -> friendsScope.launch { friendsRepository.accept(requestId) } },
                    onRejectFriendRequest = { requestId -> friendsScope.launch { friendsRepository.reject(requestId) } },
                    onCancelFriendRequest = { requestId -> friendsScope.launch { friendsRepository.cancel(requestId) } },
                    onRemoveFriend = { userId -> friendsScope.launch { friendsRepository.removeFriend(userId) } },
                    onDeleteReceiveHistory = { item, deleteFiles ->
                        if (deleteFiles) {
                            val failedCount = item.files
                                .mapNotNull { it.savedUri }
                                .count { savedUri ->
                                    runCatching {
                                        appContext.contentResolver.delete(Uri.parse(savedUri), null, null) <= 0
                                    }.getOrDefault(true)
                                }
                            if (failedCount > 0) {
                                Toast.makeText(appContext, "有${failedCount}个文件未删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                        mutateState { current -> current.removeReceiveHistory(item.id) }
                    },
                    bottomContentPadding = 104.dp,
                    modifier = Modifier,
                )
            }

            SystemNavigationBackdrop(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )

            state.activeReceive
                .takeIf { it.requiresConfirmation && it.transferId != null }
                ?.let { pendingReceive ->
                    ReceiveConfirmDialog(
                        transfer = pendingReceive,
                        onAccept = {
                            val transferId = pendingReceive.transferId ?: return@ReceiveConfirmDialog
                            sendPlatformActions.acceptReceiveTransfer(transferId)
                        },
                        onReject = {
                            val transferId = pendingReceive.transferId ?: return@ReceiveConfirmDialog
                            sendPlatformActions.cancelReceiveTransfer(transferId)
                            mutateState { current -> current.applyReceiveTransferEvent(ReceiveTransferEvent.Canceled(transferId)) }
                        },
                    )
                }

            LiquidBottomTabs(
                selectedTabIndex = { selectedTab.ordinal },
                onTabSelected = { index -> selectedTab = PikoTab.entries[index] },
                backdrop = backdrop,
                tabsCount = PikoTab.entries.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
            ) {
                PikoTab.entries.forEach { tab ->
                    LiquidBottomTab(
                        onClick = { selectedTab = tab },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            PikoTabIcon(
                                tab = tab,
                                selected = tab == selectedTab,
                            )
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (tab == selectedTab) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (selectedTab == PikoTab.Send && state.sendPage.canSend) {
                LiquidSendFloatingButton(
                    backdrop = backdrop,
                    onClick = {
                        startSendTransfer(
                            sendPage = state.sendPage,
                            senderName = state.currentDeviceName,
                            onStateMutate = mutateState,
                            sendPlatformActions = sendPlatformActions,
                            onTransferNotice = { message ->
                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 104.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = PikoTab.Send.iconPainter(),
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "发送",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiveConfirmDialog(
    transfer: ReceiveTransferState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = "确认接收") },
        text = { Text(text = transfer.receiveConfirmationMessage) },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(text = "拒绝")
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(text = "接收")
            }
        },
    )
}

@Composable
private fun SystemNavigationBackdrop(modifier: Modifier = Modifier) {
    val baseColor = if (isSystemInDarkTheme()) {
        IOS_SYSTEM_BACKGROUND_DARK
    } else {
        IOS_SYSTEM_BACKGROUND_LIGHT
    }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0f),
                        baseColor.copy(alpha = 0.58f),
                        baseColor.copy(alpha = 0.82f),
                    ),
                ),
            ),
    )
}

@Composable
private fun AppLifecycleForegroundObserver(onForeground: () -> Unit) {
    val context = LocalContext.current
    val currentOnForeground = rememberUpdatedState(onForeground)
    DisposableEffect(context) {
        val application = context.applicationContext as Application
        var startedActivities = 0
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) {
                    currentOnForeground.value()
                }
            }

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        onDispose {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

@Composable
private fun PikoTabIcon(
    tab: PikoTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = tab.iconPainter(),
        contentDescription = tab.title,
        modifier = modifier.size(23.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PikoTab.iconPainter(): Painter =
    painterResource(
        when (this) {
            PikoTab.Receive -> R.drawable.ic_lucide_download
            PikoTab.Send -> R.drawable.ic_lucide_send
            PikoTab.Settings -> R.drawable.ic_lucide_settings
        },
    )
