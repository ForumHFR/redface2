package fr.forumhfr.redface2.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(
    onAuthenticated: (pseudo: String) -> Unit,
    onCancel: () -> Unit,
) {
    val viewModel = hiltViewModel<LoginViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.mode) {
        val mode = state.mode
        if (mode is LoginUiState.Mode.Authenticated) {
            onAuthenticated(mode.pseudo)
        }
    }

    LoginContent(state = state, onIntent = viewModel::send, onCancel = onCancel)
}

@Composable
internal fun LoginContent(
    state: LoginUiState,
    onIntent: (LoginIntent) -> Unit,
    onCancel: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isSubmitting = state.mode is LoginUiState.Mode.Submitting
    val canSubmit = state.pseudo.isNotBlank() && state.password.isNotBlank() && !isSubmitting

    val submitAction: () -> Unit = {
        keyboardController?.hide()
        onIntent(LoginIntent.Submit)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.login_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = state.pseudo,
                onValueChange = { onIntent(LoginIntent.UpdatePseudo(it)) },
                label = { Text(stringResource(R.string.login_pseudo)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(LoginIntent.UpdatePassword(it)) },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                enabled = !isSubmitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (canSubmit) submitAction() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            val errorMode = state.mode as? LoginUiState.Mode.Error
            if (errorMode != null) {
                ErrorBanner(
                    type = errorMode.type,
                    detail = errorMode.detail,
                    onDismiss = { onIntent(LoginIntent.DismissError) },
                )
            }

            Button(
                onClick = submitAction,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.login_submit))
                }
            }

            TextButton(
                onClick = onCancel,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.login_cancel))
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    type: LoginUiState.ErrorType,
    detail: String?,
    onDismiss: () -> Unit,
) {
    val message = stringResource(
        when (type) {
            LoginUiState.ErrorType.InvalidCredentials -> R.string.login_error_invalid
            LoginUiState.ErrorType.RateLimited -> R.string.login_error_rate_limited
            LoginUiState.ErrorType.Network -> R.string.login_error_network
            LoginUiState.ErrorType.Unknown -> R.string.login_error_unknown
        },
    )
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            // Alpha affordance: surface the technical detail under the localized message so
            // testers can self-diagnose without `adb logcat`. Hidden when null (e.g.
            // InvalidCredentials / RateLimited where there's nothing useful to show).
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.login_error_dismiss))
            }
        }
    }
}
