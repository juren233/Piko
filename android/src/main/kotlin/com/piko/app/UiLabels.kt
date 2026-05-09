package com.piko.app

internal val String.avatarLabel: String
    get() = trim().take(2).ifBlank { "设备" }

internal val SendPermissionState.label: String
    get() = when (this) {
        SendPermissionState.Unknown -> "正在读取最近图片"
        SendPermissionState.Requesting -> "等待相册授权"
        SendPermissionState.Granted -> "还没有读取到最近图片"
        SendPermissionState.Denied -> "相册权限未开启"
        SendPermissionState.Unavailable -> "当前平台暂不可读取相册"
    }

internal val SendLanDiscoveryState.label: String
    get() = when (this) {
        SendLanDiscoveryState.Idle -> "等待搜索局域网设备"
        SendLanDiscoveryState.Searching -> "正在搜索局域网设备"
        SendLanDiscoveryState.Found -> "已发现局域网设备"
        SendLanDiscoveryState.Empty -> "暂无局域网设备"
        SendLanDiscoveryState.Unavailable -> "当前平台暂不可发现设备"
        SendLanDiscoveryState.Failed -> "局域网发现失败"
    }

internal val SendFileItem.sizeLabel: String
    get() {
        val units = listOf("B", "KB", "MB", "GB")
        var value = sizeBytes.toDouble().coerceAtLeast(0.0)
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${(value * 10).toLong() / 10.0} ${units[unitIndex]}"
        }
    }

internal val ReceiveFileType.previewLabel: String
    get() = when (this) {
        ReceiveFileType.Document -> "DOC"
        ReceiveFileType.Spreadsheet -> "XLS"
        ReceiveFileType.Image -> "IMG"
        ReceiveFileType.Video -> "VID"
        ReceiveFileType.Archive -> "ZIP"
        ReceiveFileType.Other -> "FILE"
    }
