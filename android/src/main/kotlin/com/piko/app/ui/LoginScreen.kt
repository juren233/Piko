package com.piko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.piko.app.domain.AccountError

/**
 * 登录表单。提交后通过 onSubmit 异步触发；UI 期间设置 isSubmitting=true 禁用按钮。
 */
@Composable
internal fun LoginForm(
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (email: String, password: String) -> Unit,
    onSwitchToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = !isSubmitting && email.isNotBlank() && password.length >= 8

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = AuthLabels.signIn,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(AuthLabels.email) },
            singleLine = true,
            enabled = !isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(AuthLabels.password) },
            singleLine = true,
            enabled = !isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = { onSubmit(email.trim(), password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(text = AuthLabels.signIn)
        }
        TextButton(
            onClick = onSwitchToRegister,
            enabled = !isSubmitting,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = AuthLabels.signUp)
        }
    }
}

internal fun AccountError.toLoginMessage(): String = when (this) {
    AccountError.InvalidCredentials -> AuthLabels.invalidCredentials
    AccountError.Network -> AuthLabels.networkUnavailable
    AccountError.InvalidEmail -> AuthLabels.invalidEmail
    AccountError.InvalidPassword -> AuthLabels.weakPassword
    is AccountError.Server -> message.ifEmpty { AuthLabels.networkUnavailable }
    else -> AuthLabels.invalidCredentials
}
