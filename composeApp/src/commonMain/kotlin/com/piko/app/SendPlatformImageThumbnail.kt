package com.piko.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun SendPlatformImageThumbnail(
    image: SendImageItem,
    modifier: Modifier = Modifier,
)
