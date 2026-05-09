package com.piko.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SendImageSection(
    sendPage: SendPageState,
    onToggleExpanded: () -> Unit,
    onImageClick: (String) -> Unit,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PikoSectionPanel(
        title = "图片",
        modifier = modifier.clickable(onClick = onToggleExpanded),
        trailing = {
            PikoInfoPill(
                text = if (sendPage.imageSectionExpanded) "收起" else "展开",
                emphasized = true,
            )
        },
    ) {

        if (sendPage.visibleImages.isEmpty()) {
            SendImagesEmptyState(
                permissionState = sendPage.photoPermissionState,
                onPickImages = onPickImages,
            )
        } else if (sendPage.imageSectionExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sendPage.visibleImages.chunked(3).forEach { rowImages ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowImages.forEach { image ->
                            SendImageTile(
                                image = image,
                                selected = image.id in sendPage.selectedImageIds,
                                onClick = { onImageClick(image.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowImages.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sendPage.visibleImages, key = { image -> image.id }) { image ->
                    SendImageTile(
                        image = image,
                        selected = image.id in sendPage.selectedImageIds,
                        onClick = { onImageClick(image.id) },
                        modifier = Modifier.width(92.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendImagesEmptyState(
    permissionState: SendPermissionState,
    onPickImages: () -> Unit,
) {
    PikoEmptyPlane(
        text = permissionState.label,
    ) {
        TextButton(onClick = onPickImages) {
            Text(text = "选择图片")
        }
    }
}

@Composable
private fun SendImageTile(
    image: SendImageItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (selected) 28.dp else 18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                },
                shape = RoundedCornerShape(if (selected) 28.dp else 18.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SendPlatformImageThumbnail(
            image = image,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            SelectionBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}
