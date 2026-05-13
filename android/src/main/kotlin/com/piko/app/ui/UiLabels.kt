package com.piko.app.ui

import com.piko.app.domain.ReceiveFileType
import com.piko.app.domain.SendFileItem
import com.piko.app.domain.SendLanDiscoveryState
import com.piko.app.domain.SendPermissionState

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

/**
 * 账号体系中文字符串集中地。iOS 端 NativeAuthLabels.swift key 集与此 1:1 对齐，
 * 由 IosAndroidUiParityTest.authLabelsParityAcrossPlatforms 守护。
 */
internal object AuthLabels {
    const val accountSectionTitle = "账号"
    const val email = "邮箱"
    const val password = "密码"
    const val username = "用户名"
    const val nickname = "昵称（可选）"
    const val signIn = "登录"
    const val signUp = "注册"
    const val signInOrSignUp = "登录 / 注册"
    const val signOut = "退出登录"
    const val unsetNicknamePlaceholder = "未设置"
    const val emailTaken = "邮箱已被注册"
    const val usernameTaken = "用户名已被占用"
    const val invalidCredentials = "邮箱或密码错误"
    const val networkUnavailable = "网络不可用，请稍后重试"
    const val weakPassword = "密码至少 8 位"
    const val invalidEmail = "邮箱格式有误"
    const val invalidUsername = "用户名格式不合法"
    const val invalidNickname = "昵称格式不合法"
    const val friendsEntry = "好友"
    const val friendRequestsTitle = "好友申请"
    const val addFriendButton = "申请"
    const val acceptButton = "同意"
    const val rejectButton = "拒绝"
    const val cancelRequestButton = "撤回"
    const val removeFriendButton = "删除"
    const val searchPlaceholder = "邮箱或用户名"
    const val noFriendsHint = "还没有好友，从上方搜索一个吧"
    const val friendsLoginHint = "登录后管理好友"
}
