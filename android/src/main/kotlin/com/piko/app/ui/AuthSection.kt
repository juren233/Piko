package com.piko.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piko.app.domain.AccountError
import com.piko.app.domain.AuthState

/**
 * Settings 账号 section 的所有依赖打包成一个 bundle，避免 PikoSettingsScreen 参数爆炸。
 */
data class AuthSection(
    val state: AuthState,
    val lastError: AccountError?,
    val onLogin: (email: String, password: String) -> Unit,
    val onRegister: (email: String, password: String, username: String, nickname: String?) -> Unit,
    val onSignOut: () -> Unit,
    val onErrorConsumed: () -> Unit,
) {
    companion object {
        val Empty = AuthSection(
            state = AuthState.Unauthenticated,
            lastError = null,
            onLogin = { _, _ -> },
            onRegister = { _, _, _, _ -> },
            onSignOut = {},
            onErrorConsumed = {},
        )
    }
}

private enum class AuthMode { Login, Register }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthSectionContent(
    section: AuthSection,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf<AuthMode?>(null) }

    // 一旦认证成功就关闭 sheet
    LaunchedEffect(section.state) {
        if (section.state is AuthState.Authenticated && mode != null) {
            mode = null
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 永远保留 "登录方式 / 邮箱账号" 行（parity test 锁定）
        AccountMetricRow(title = "登录方式", value = "邮箱账号")

        when (val s = section.state) {
            is AuthState.Authenticated -> {
                AccountMetricRow(title = AuthLabels.email, value = s.user.email)
                AccountMetricRow(title = AuthLabels.username, value = "@${s.user.username}")
                AccountMetricRow(
                    title = AuthLabels.nickname,
                    value = s.user.nickname ?: AuthLabels.unsetNicknamePlaceholder,
                )
                OutlinedButton(
                    onClick = section.onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = AuthLabels.signOut)
                }
            }

            AuthState.Loading, AuthState.Unauthenticated -> {
                SignInLauncherRow(
                    isBusy = s is AuthState.Loading,
                    onClick = { mode = AuthMode.Login },
                )
            }
        }
    }

    val currentMode = mode
    if (currentMode != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                mode = null
                section.onErrorConsumed()
            },
            sheetState = sheetState,
        ) {
            val isSubmitting = section.state is AuthState.Loading
            when (currentMode) {
                AuthMode.Login -> LoginForm(
                    isSubmitting = isSubmitting,
                    errorMessage = section.lastError?.toLoginMessage(),
                    onSubmit = { email, password -> section.onLogin(email, password) },
                    onSwitchToRegister = {
                        section.onErrorConsumed()
                        mode = AuthMode.Register
                    },
                )

                AuthMode.Register -> RegisterForm(
                    isSubmitting = isSubmitting,
                    errorMessage = section.lastError?.toRegisterMessage(),
                    onSubmit = { email, password, username, nickname ->
                        section.onRegister(email, password, username, nickname)
                    },
                    onSwitchToLogin = {
                        section.onErrorConsumed()
                        mode = AuthMode.Login
                    },
                )
            }
        }
    }
}

@Composable
private fun SignInLauncherRow(
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = AuthLabels.signInOrSignUp,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AccountMetricRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
