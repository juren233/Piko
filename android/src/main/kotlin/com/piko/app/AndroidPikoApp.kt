package com.piko.app

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.juren233.piko.R
import com.piko.app.glass.LiquidBottomTab
import com.piko.app.glass.LiquidBottomTabs
import com.piko.app.glass.LiquidSendFloatingButton

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
    val sendPlatformActions = rememberAndroidSendPlatformActions(
        currentNickname = currentNickname,
        mediaSaveLocation = mediaSaveLocation,
        onReceiveTransferEvent = { event ->
            val nextState = state.applyReceiveTransferEvent(event)
            if (nextState.receiveHistory != state.receiveHistory) {
                receiveHistoryStore.save(nextState.receiveHistory)
            }
            state = nextState
        },
    )
    val backdrop = rememberLayerBackdrop()

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
                    onStateMutate = { transform -> state = transform(state) },
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
                    bottomContentPadding = 104.dp,
                    modifier = Modifier,
                )
            }

            SystemNavigationBackdrop(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )

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
                            onStateMutate = { transform -> state = transform(state) },
                            sendPlatformActions = sendPlatformActions,
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
                        )
                    }
                }
            }
        }
    }
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
