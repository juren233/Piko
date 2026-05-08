package com.piko.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.juren233.piko.R
import com.piko.app.glass.LiquidBottomTab
import com.piko.app.glass.LiquidBottomTabs

@Composable
fun AndroidPikoApp() {
    var selectedTab by remember { mutableStateOf(PikoTab.Receive) }
    var state by remember { mutableStateOf(PikoHomeState.initial()) }
    val backdrop = rememberLayerBackdrop()

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
                color = MaterialTheme.colorScheme.background,
            ) {
                PikoTabScreen(
                    tab = selectedTab,
                    state = state,
                    onCreateSampleTransfer = {
                        state = state.withSampleTransfer()
                    },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(bottom = 104.dp),
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
                            )
                        }
                    }
                }
            }
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
