package com.piko.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.app.AuthSection
import com.piko.app.app.FriendsEntry
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.design.PikoSectionHeader
import com.piko.app.design.PikoSpacing
import com.piko.app.domain.AccountError
import com.piko.app.domain.AuthState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperRadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsRoute(
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit,
    authSection: AuthSection,
    friendsEntry: FriendsEntry,
    appVersion: String,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PikoSpacing.screenHorizontal,
            top = PikoSpacing.screenTop,
            end = PikoSpacing.screenHorizontal,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PikoSpacing.section),
    ) {
        item {
            PikoSectionHeader(
                title = "设置",
                supportingText = "长期偏好、账号和诊断。",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "传输策略",
                    summary = "局域网 direct 优先，跨网使用 P2P direct。",
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                )
                BasicComponent(
                    title = "自动接收",
                    summary = "首版保持可信设备策略展示，不新增复杂配置。",
                )
                ReceiveMediaSaveLocation.entries.forEach { location ->
                    SuperRadioButton(
                        title = location.label,
                        summary = if (location == ReceiveMediaSaveLocation.Album) {
                            "图片和视频写入系统相册。"
                        } else {
                            "所有内容保存在 Piko 文件夹。"
                        },
                        selected = location == mediaSaveLocation,
                        onClick = { onMediaSaveLocationChange(location) },
                    )
                }
            }
        }
        item {
            AccountCard(
                authSection = authSection,
                friendsEntry = friendsEntry,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "诊断",
                    summary = "Piko $appVersion · direct-only transfer",
                    endActions = {
                        TextButton(
                            text = "查看",
                            onClick = {},
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountCard(
    authSection: AuthSection,
    friendsEntry: FriendsEntry,
) {
    var authMode by remember { mutableStateOf<AuthMode?>(null) }

    LaunchedEffect(authSection.state) {
        if (authSection.state is AuthState.Authenticated) {
            authMode = null
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "账号",
            summary = "登录后可管理好友设备。",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Settings,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
        )
        FriendsEntryRow(entry = friendsEntry)
        AuthSectionContent(
            section = authSection,
            onAuthModeChange = { authMode = it },
        )
    }

    val currentMode = authMode
    if (currentMode != null) {
        AuthDialog(
            mode = currentMode,
            section = authSection,
            onModeChange = { authMode = it },
            onDismiss = {
                authMode = null
                authSection.onErrorConsumed()
            },
        )
    }
}

@Composable
private fun FriendsEntryRow(
    entry: FriendsEntry,
) {
    val summary = if (entry.enabled) {
        if (entry.pendingCount > 0) {
            "${entry.friendCount} 人 · ${entry.pendingCount} 个申请"
        } else {
            "${entry.friendCount} 人"
        }
    } else {
        "登录后可用"
    }
    SuperArrow(
        title = "好友",
        summary = summary,
        enabled = entry.enabled,
        onClick = entry.onClick,
    )
}

@Composable
private fun AuthSectionContent(
    section: AuthSection,
    onAuthModeChange: (AuthMode) -> Unit,
) {
    BasicComponent(
        title = "登录方式",
        summary = "邮箱账号",
    )
    when (val state = section.state) {
        is AuthState.Authenticated -> {
            BasicComponent(title = "邮箱", summary = state.user.email)
            BasicComponent(title = "用户名", summary = "@${state.user.username}")
            BasicComponent(title = "昵称", summary = state.user.nickname ?: "未设置")
            TextButton(
                text = "退出登录",
                onClick = section.onSignOut,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AuthState.Loading,
        AuthState.Unauthenticated -> {
            SuperArrow(
                title = "登录 / 注册",
                summary = if (state is AuthState.Loading) "正在提交账号请求" else "使用邮箱账号同步好友设备。",
                onClick = { onAuthModeChange(AuthMode.Login) },
                enabled = state !is AuthState.Loading,
            )
        }
    }
}

@Composable
private fun AuthDialog(
    mode: AuthMode,
    section: AuthSection,
    onModeChange: (AuthMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember(mode) { mutableStateOf("") }
    var password by remember(mode) { mutableStateOf("") }
    var username by remember(mode) { mutableStateOf("") }
    var nickname by remember(mode) { mutableStateOf("") }
    val isSubmitting = section.state is AuthState.Loading

    SuperDialog(
        show = true,
        title = if (mode == AuthMode.Login) "登录" else "注册",
        summary = section.lastError?.toAuthMessage(),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = email,
                onValueChange = { email = it },
                label = "邮箱",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == AuthMode.Register) {
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "用户名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = "昵称（可选）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    if (mode == AuthMode.Login) {
                        section.onLogin(email.trim(), password)
                    } else {
                        section.onRegister(
                            email.trim(),
                            password,
                            username.trim(),
                            nickname.trim().ifBlank { null },
                        )
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (mode == AuthMode.Login) "登录" else "注册")
            }
            TextButton(
                text = if (mode == AuthMode.Login) "创建账号" else "已有账号，去登录",
                onClick = {
                    section.onErrorConsumed()
                    onModeChange(if (mode == AuthMode.Login) AuthMode.Register else AuthMode.Login)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class AuthMode {
    Login,
    Register,
}

private fun AccountError.toAuthMessage(): String = when (this) {
    AccountError.Network -> "网络不可用，请稍后重试"
    AccountError.InvalidCredentials -> "邮箱或密码错误"
    AccountError.EmailTaken -> "邮箱已被注册"
    AccountError.UsernameTaken -> "用户名已被占用"
    AccountError.InvalidEmail -> "邮箱格式有误"
    AccountError.InvalidPassword -> "密码至少 8 位"
    AccountError.InvalidUsername -> "用户名格式不合法"
    AccountError.InvalidNickname -> "昵称格式不合法"
    AccountError.SessionExpired -> "登录已过期"
    is AccountError.Server -> message.ifBlank { "服务器返回错误：$code" }
}
