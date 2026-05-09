package com.piko.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val LucideInboxIcon: ImageVector = ImageVector.Builder(
    name = "LucideInbox",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(22f, 12f)
        lineTo(16f, 12f)
        lineTo(14f, 15f)
        lineTo(10f, 15f)
        lineTo(8f, 12f)
        lineTo(2f, 12f)
    }
    lucidePath {
        moveTo(5.45f, 5.11f)
        lineTo(2f, 12f)
        verticalLineToRelative(6f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        horizontalLineToRelative(16f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
        verticalLineToRelative(-6f)
        lineToRelative(-3.45f, -6.89f)
        arcToRelative(2f, 2f, 0f, false, false, -1.79f, -1.11f)
        horizontalLineTo(7.24f)
        arcToRelative(2f, 2f, 0f, false, false, -1.79f, 1.11f)
        close()
    }
}.build()

internal val LucideSmartphoneIcon: ImageVector = ImageVector.Builder(
    name = "LucideSmartphone",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(7f, 2f)
        horizontalLineToRelative(10f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        verticalLineToRelative(16f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(7f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(4f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        close()
    }
    lucidePath {
        moveTo(12f, 18f)
        horizontalLineToRelative(0.01f)
    }
}.build()

internal val LucideRefreshCwIcon: ImageVector = ImageVector.Builder(
    name = "LucideRefreshCw",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(3f, 12f)
        arcToRelative(9f, 9f, 0f, false, true, 9f, -9f)
        arcToRelative(9.75f, 9.75f, 0f, false, true, 6.74f, 2.74f)
        lineTo(21f, 8f)
    }
    lucidePath {
        moveTo(21f, 3f)
        verticalLineToRelative(5f)
        horizontalLineToRelative(-5f)
    }
    lucidePath {
        moveTo(21f, 12f)
        arcToRelative(9f, 9f, 0f, false, true, -9f, 9f)
        arcToRelative(9.75f, 9.75f, 0f, false, true, -6.74f, -2.74f)
        lineTo(3f, 16f)
    }
    lucidePath {
        moveTo(8f, 16f)
        horizontalLineTo(3f)
        verticalLineToRelative(5f)
    }
}.build()

internal val LucideCheckIcon: ImageVector = ImageVector.Builder(
    name = "LucideCheck",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(20f, 6f)
        lineTo(9f, 17f)
        lineToRelative(-5f, -5f)
    }
}.build()

internal val LucideXIcon: ImageVector = ImageVector.Builder(
    name = "LucideX",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }
    lucidePath {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
    }
}.build()

internal val LucideFileIcon: ImageVector = ImageVector.Builder(
    name = "LucideFile",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(6f, 22f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(4f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        horizontalLineToRelative(8f)
        arcToRelative(2.4f, 2.4f, 0f, false, true, 1.704f, 0.706f)
        lineToRelative(3.588f, 3.588f)
        arcToRelative(2.4f, 2.4f, 0f, false, true, 0.708f, 1.706f)
        verticalLineToRelative(12f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        close()
    }
    lucidePath {
        moveTo(14f, 2f)
        verticalLineToRelative(5f)
        arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
        horizontalLineToRelative(5f)
    }
}.build()

internal val LucideImageIcon: ImageVector = ImageVector.Builder(
    name = "LucideImage",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(5f, 3f)
        horizontalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        verticalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        close()
    }
    lucidePath {
        moveTo(9f, 9f)
        moveToRelative(-2f, 0f)
        arcToRelative(2f, 2f, 0f, true, false, 4f, 0f)
        arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
    }
    lucidePath {
        moveTo(21f, 15f)
        lineToRelative(-3.086f, -3.086f)
        arcToRelative(2f, 2f, 0f, false, false, -2.828f, 0f)
        lineTo(6f, 21f)
    }
}.build()

internal val LucidePlusIcon: ImageVector = ImageVector.Builder(
    name = "LucidePlus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(5f, 12f)
        horizontalLineToRelative(14f)
    }
    lucidePath {
        moveTo(12f, 5f)
        verticalLineToRelative(14f)
    }
}.build()

private fun ImageVector.Builder.lucidePath(pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder,
    )
}
