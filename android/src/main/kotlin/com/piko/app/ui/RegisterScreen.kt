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

@Composable
internal fun RegisterForm(
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (email: String, password: String, username: String, nickname: String?) -> Unit,
    onSwitchToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    val canSubmit = !isSubmitting &&
        email.isNotBlank() &&
        password.length >= 8 &&
        username.trim().length in 3..32

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = AuthLabels.signUp,
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
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(AuthLabels.username) },
            singleLine = true,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text(AuthLabels.nickname) },
            singleLine = true,
            enabled = !isSubmitting,
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
            onClick = {
                onSubmit(
                    email.trim(),
                    password,
                    username.trim(),
                    nickname.trim().ifBlank { null },
                )
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(text = AuthLabels.signUp)
        }
        TextButton(
            onClick = onSwitchToLogin,
            enabled = !isSubmitting,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = AuthLabels.signIn)
        }
    }
}

internal fun AccountError.toRegisterMessage(): String = when (this) {
    AccountError.EmailTaken -> AuthLabels.emailTaken
    AccountError.UsernameTaken -> AuthLabels.usernameTaken
    AccountError.InvalidEmail -> AuthLabels.invalidEmail
    AccountError.InvalidPassword -> AuthLabels.weakPassword
    AccountError.InvalidUsername -> AuthLabels.invalidUsername
    AccountError.InvalidNickname -> AuthLabels.invalidNickname
    AccountError.Network -> AuthLabels.networkUnavailable
    is AccountError.Server -> message.ifEmpty { AuthLabels.networkUnavailable }
    else -> AuthLabels.networkUnavailable
}
