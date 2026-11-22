package ch.lkmc.pageworn.ui.connect

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.PaperButton
import ch.lkmc.pageworn.ui.components.PaperField
import ch.lkmc.pageworn.ui.components.PaperScreen
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperSwitchColors
import ch.lkmc.pageworn.ui.model.ConnectStep
import ch.lkmc.pageworn.ui.model.ConnectUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun ConnectScreen(state: ConnectUiState, onUrlChange: (String) -> Unit, onLabelChange: (String) -> Unit, onAllowCleartextChange: (Boolean) -> Unit, onContinue: () -> Unit, onUsernameChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onSignIn: () -> Unit, onTrust: () -> Unit, onRetry: () -> Unit, onAllowLocalNetwork: () -> Unit, onConfirmPairing: () -> Unit, onCancelPairing: () -> Unit, onOpenDownloaded: (() -> Unit)?) {
    PaperScreen(state.heading, onBack = null) {
        when (state.step) {
            ConnectStep.ADDRESS -> {
                PaperField(state.url, onUrlChange, "Server address", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = state.addressError != null)
                state.addressError?.let { Text(it, color = PagewornTheme.colors.accent, style = PagewornTheme.type.status) }
                state.fingerprint?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata.copy(fontFamily = FontFamily.Monospace)) }
                if (state.fingerprint != null) TextAction(Copy.TRUST_CERTIFICATE, onClick = onTrust)
                if (state.canRetry) TextAction(Copy.RETRY, onClick = onRetry)
                state.localNetworkAction?.let { TextAction(it, onClick = onAllowLocalNetwork) }
                PaperField(state.label, onLabelChange, "Label (optional)")
                if (state.url.trim().startsWith("http://", ignoreCase = true) || state.allowCleartext || state.cleartextExplanation != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = state.allowCleartext, role = Role.Switch, onValueChange = onAllowCleartextChange), verticalAlignment = Alignment.CenterVertically) {
                        Switch(state.allowCleartext, onCheckedChange = null, colors = paperSwitchColors())
                        Text(Copy.ALLOW_CLEARTEXT, Modifier.padding(start = 8.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.metadata)
                    }
                }
                (state.cleartextExplanation ?: if (state.url.trim().startsWith("http://", ignoreCase = true)) Copy.CLEARTEXT_TRADEOFF else null)?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                PaperButton(Copy.CONTINUE, onContinue, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy)
            }
            ConnectStep.CREDENTIALS -> {
                PaperField(state.username, onUsernameChange, "Username", Modifier.semantics { contentType = ContentType.Username })
                state.usernameError?.let { Text(it, color = PagewornTheme.colors.accent, style = PagewornTheme.type.status) }
                PaperField(state.password, onPasswordChange, "Password", Modifier.semantics { contentType = ContentType.Password }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = state.credentialsError != null)
                state.credentialsError?.let { Text(it, color = PagewornTheme.colors.accent, style = PagewornTheme.type.status) }
                PaperButton(Copy.CONTINUE, onSignIn, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy)
            }
            ConnectStep.CONFIRM_PAIRING -> {
                state.pairingSummary?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.empty) }
                PaperButton(Copy.ADD_ADDRESS, onConfirmPairing, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy)
                TextAction(Copy.CANCEL, onClick = onCancelPairing)
            }
        }
        if (state.busy) CircularProgressIndicator(Modifier.padding(8.dp))
        onOpenDownloaded?.let { TextAction("Open Downloaded", onClick = it) }
    }
}
