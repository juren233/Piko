package com.piko.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PikoReceiveScreen(
    state: PikoHomeState,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(pikoPageBrush())
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = bottomContentPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            PikoHeroPanel(
                title = "Piko",
                subtitle = "接收记录和本机收件箱",
                metric = "${state.receiveHistoryDescending.size} 次",
            )
        }
        if (state.receiveHistoryDescending.isEmpty()) {
            item {
                ReceiveHistoryEmptyState()
            }
        } else {
            item {
                PikoInfoPill(text = "最近接收", emphasized = true)
            }
            items(
                items = state.receiveHistoryDescending,
                key = { history -> history.id },
            ) { history ->
                ReceiveHistoryCard(history = history)
            }
        }
    }
}

@Composable
private fun ReceiveHistoryCard(history: ReceiveHistoryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 72.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
            ReceiveHistoryPreview(history = history)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = history.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = history.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
private fun ReceiveHistoryPreview(history: ReceiveHistoryItem) {
    val imagePreviewDescription = history.imagePreviewDescription

    when {
        history.fileCount > 1 -> MultiFilePreviewBadge(
            fileType = history.primaryFile.fileType,
            fileCount = history.fileCount,
            size = 60.dp,
        )

        imagePreviewDescription != null -> ImageThumbnailPreview(
            description = imagePreviewDescription,
            size = 60.dp,
        )

        else -> FileTypePreviewBadge(
            fileType = history.primaryFile.fileType,
            size = 60.dp,
        )
    }
}

@Composable
private fun MultiFilePreviewBadge(
    fileType: ReceiveFileType,
    fileCount: Int,
    size: Dp,
) {
    val corner = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.7f)
                .offset(x = 8.dp, y = (-6).dp)
                .graphicsLayer { rotationZ = 8f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surface,
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.74f)
                .offset(x = (-8).dp, y = 8.dp)
                .graphicsLayer { rotationZ = -10f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.78f)
                .graphicsLayer { rotationZ = -2f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ),
        ) {
            FileTypeGlyph(
                label = fileType.previewLabel,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = "+${fileCount - 1}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FileTypePreviewBadge(
    fileType: ReceiveFileType,
    size: Dp,
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            FileTypeGlyph(
                label = fileType.previewLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ImageThumbnailPreview(
    description: String,
    size: Dp,
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(11.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(size * 0.34f)
                .offset(y = 8.dp)
                .graphicsLayer { rotationZ = -7f }
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 26.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = description.take(6),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LayeredPreviewCard(
    modifier: Modifier,
    shape: RoundedCornerShape,
    backgroundBrush: Brush,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            ),
        content = content,
    )
}

@Composable
private fun FileTypeGlyph(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ReceiveHistoryEmptyState(
    modifier: Modifier = Modifier,
) {
    PikoEmptyPlane(
        text = "还没有接收过文件",
        modifier = modifier,
    ) {
        EmptyReceiveHistoryIcon()
    }
}

@Composable
private fun EmptyReceiveHistoryIcon() {
    Box(
        modifier = Modifier
            .size(108.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LucideInboxIcon,
            contentDescription = null,
            modifier = Modifier.size(58.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
    }
}
