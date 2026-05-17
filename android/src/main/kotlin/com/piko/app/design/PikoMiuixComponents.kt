package com.piko.app.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piko.app.domain.SendTransportPath
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PikoSectionHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PikoEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        BasicComponent(
            title = title,
            summary = body,
        )
    }
}

@Composable
internal fun DirectPathChip(
    path: SendTransportPath,
    online: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val label = when (path) {
        SendTransportPath.Lan -> "LAN direct"
        SendTransportPath.P2P -> "P2P direct"
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (online) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.secondaryContainer
        },
        contentColor = if (online) {
            MiuixTheme.colorScheme.onPrimaryContainer
        } else {
            MiuixTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (online) label else "$label · 离线",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
