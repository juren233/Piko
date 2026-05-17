package com.piko.app.platform

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.juren233.piko.BuildConfig
import com.piko.app.app.PikoAndroidAppShell
import com.piko.app.app.PikoDestination
import com.piko.app.app.AuthSection
import com.piko.app.app.FriendsEntry
import com.piko.app.app.startSendTransfer
import com.piko.app.data.AuthRepository
import com.piko.app.data.AuthTokenStore
import com.piko.app.data.DeviceIdentityStore
import com.piko.app.data.FriendsRepository
import com.piko.app.data.ReceiveHistoryStore
import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.AuthState
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveTransferEvent
import com.piko.app.transport.AccountApiClient
import com.piko.app.transport.DeviceApiClient
import com.piko.app.transport.FriendApiClient
import com.piko.app.transport.SignalingWebSocketClient
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
    var destination by remember { mutableStateOf(PikoDestination.Receive) }
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
            mutateState { current -> current.applyReceiveTransferEvent(event) }
        },
    )
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
            if (destination == PikoDestination.Friends || destination == PikoDestination.FriendRequests) {
                destination = PikoDestination.Settings
            }
        }
    }

    AppLifecycleForegroundObserver(onForeground = ::refreshFriendsPresence)

    LaunchedEffect(destination, authState) {
        if (destination == PikoDestination.Send) {
            refreshFriendsPresence()
        }
    }

    LaunchedEffect(destination, authState) {
        if (destination == PikoDestination.Friends) {
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

    PikoAndroidAppShell(
        destination = destination,
        onDestinationChange = { destination = it },
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
            onClick = { destination = PikoDestination.Friends },
        ),
        friendSearchQuery = friendSearchQuery,
        onFriendSearchQueryChange = { friendSearchQuery = it },
        onFriendRequestsClick = { destination = PikoDestination.FriendRequests },
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
        onAcceptReceiveTransfer = { transferId ->
            sendPlatformActions.acceptReceiveTransfer(transferId)
        },
        onCancelReceiveTransfer = { transferId ->
            sendPlatformActions.cancelReceiveTransfer(transferId)
            mutateState { current -> current.applyReceiveTransferEvent(ReceiveTransferEvent.Canceled(transferId)) }
        },
        onStartSendTransfer = {
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
        appVersion = BuildConfig.VERSION_NAME,
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
