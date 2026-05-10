package com.piko.app

import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure as AndroidPathMeasure
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PikoReceiveScreen(
    state: PikoHomeState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    onResetCurrentDeviceName: () -> Unit = {},
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val activeReceive = state.activeReceive.takeIf { it.transferId != null }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pikoPageBrush()),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 32.dp, bottom = bottomContentPadding + 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                PikoHeroPanel(
                    title = "Piko",
                    subtitle = "接收记录和本机收件箱",
                    metric = "${state.receiveHistoryDescending.size} 次",
                )
            }
            item {
                CurrentDeviceNicknameBanner(
                    nickname = state.currentDeviceName,
                    onReset = onResetCurrentDeviceName,
                )
            }
            if (state.receiveHistoryDescending.isEmpty() && activeReceive == null) {
                item {
                    ReceiveHistoryEmptyState()
                }
            } else {
                if (activeReceive != null) {
                    item(key = activeReceive.transferId) {
                        ActiveReceiveCard(
                            transfer = activeReceive,
                            onCancel = {
                                activeReceive.transferId?.let { transferId ->
                                    sendPlatformActions.cancelReceiveTransfer(transferId)
                                    onStateMutate { current ->
                                        current.applyReceiveTransferEvent(ReceiveTransferEvent.Canceled(transferId))
                                    }
                                }
                            },
                        )
                    }
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
}

@Composable
private fun ActiveReceiveCard(
    transfer: ReceiveTransferState,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActiveReceiveProgressIcon(
            progress = transfer.progress,
            fileType = transfer.primaryFileType,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = transfer.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = transfer.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.offset(x = (-8).dp),
        ) {
            Icon(
                imageVector = LucideXIcon,
                contentDescription = "取消",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActiveReceiveProgressIcon(
    progress: Float,
    fileType: ReceiveFileType,
) {
    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fileType == ReceiveFileType.Image) LucideImageIcon else LucideDownloadIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        RoundedRectProgressIndicator(
            progress = progress,
            modifier = Modifier.size(60.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f).toArgb(),
        )
    }
}

@Composable
private fun RoundedRectProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Int,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 3.dp.toPx()
        val inset = strokeWidth / 2f
        val corner = 18.dp.toPx()
        val path = AndroidPath().apply {
            addRoundRect(
                inset,
                inset,
                size.width - inset,
                size.height - inset,
                corner,
                corner,
                AndroidPath.Direction.CW,
            )
        }
        val measure = AndroidPathMeasure(path, false)
        val segment = AndroidPath()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), segment, true)
        drawIntoCanvas { canvas ->
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                this.strokeWidth = strokeWidth
                this.color = color
            }
            canvas.nativeCanvas.drawPath(segment, paint)
        }
    }
}

@Composable
private fun CurrentDeviceNicknameBanner(
    nickname: String,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LucideSmartphoneIcon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = nickname,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "本设备名称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onReset)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LucideRefreshCwIcon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "换个昵称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReceiveHistoryCard(history: ReceiveHistoryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Icon(
            imageVector = LucideChevronRightIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ReceiveHistoryPreview(history: ReceiveHistoryItem) {
    val mediaPreviewDescription = history.mediaPreviewDescription

    when {
        history.fileCount > 1 -> MultiFilePreviewBadge(
            fileType = history.primaryFile.fileType,
            fileCount = history.fileCount,
            size = 60.dp,
        )

        mediaPreviewDescription != null -> MediaThumbnailPreview(
            description = mediaPreviewDescription,
            thumbnailBytes = history.primaryFile.thumbnailBytes,
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
                .offset(x = 8.dp, y = (-6).dp),
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.74f)
                .offset(x = (-8).dp, y = 8.dp),
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    MaterialTheme.colorScheme.surface,
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.78f),
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
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
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
            FileTypeGlyph(
                label = fileType.previewLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun MediaThumbnailPreview(
    description: String,
    thumbnailBytes: ByteArray?,
    size: Dp,
) {
    val shape = RoundedCornerShape(18.dp)
    val bitmap = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
        return
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.surface,
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
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(size * 0.34f)
                .offset(y = 8.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 26.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f),
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
            .size(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LucideInboxIcon,
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
    }
}
