package com.distractionkiller.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** Shortest password the app will accept. Long enough to be deliberate. */
const val MIN_PASSWORD_LENGTH = 4

/**
 * First-run screen, and the "change password" flow reuses the same field pair.
 * Both entries have to match before the button does anything.
 */
@Composable
fun SetPasswordScreen(
    title: String,
    subtitle: String,
    submitLabel: String,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GateScaffold(title = title, subtitle = subtitle) {
        PasswordField(
            value = password,
            onValueChange = { password = it; error = null },
            label = "New password",
            reveal = reveal,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            value = confirmation,
            onValueChange = { confirmation = it; error = null },
            label = "Repeat password",
            reveal = reveal,
            imeAction = ImeAction.Done,
        )
        RevealToggle(reveal = reveal, onToggle = { reveal = !reveal })

        error?.let { ErrorText(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    error = validateNewPassword(password, confirmation)
                    if (error == null) onSubmit(password)
                },
            ) {
                Text(submitLabel)
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * Gate in front of Settings. [onSubmit] returns true when the password was
 * right; anything else just shows the error and leaves the field alone.
 * No lockout or delay, as agreed for v1.
 */
@Composable
fun PasswordPromptScreen(
    onSubmit: (String) -> Boolean,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    GateScaffold(title = "Settings", subtitle = "Enter your password to continue.") {
        PasswordField(
            value = password,
            onValueChange = { password = it; showError = false },
            label = "Password",
            reveal = reveal,
            imeAction = ImeAction.Done,
            modifier = Modifier.focusRequester(focusRequester),
        )
        RevealToggle(reveal = reveal, onToggle = { reveal = !reveal })

        if (showError) ErrorText("Wrong password.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (!onSubmit(password)) showError = true }) {
                Text("Unlock")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** null when the pair is acceptable, otherwise the message to show. */
fun validateNewPassword(password: String, confirmation: String): String? = when {
    password.length < MIN_PASSWORD_LENGTH ->
        "Use at least $MIN_PASSWORD_LENGTH characters."
    password != confirmation -> "The two entries do not match."
    else -> null
}

@Composable
private fun GateScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    reveal: Boolean,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RevealToggle(reveal: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) {
        Text(if (reveal) "Hide password" else "Show password")
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
