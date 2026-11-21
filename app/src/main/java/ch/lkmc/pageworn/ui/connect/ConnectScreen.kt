package ch.lkmc.pageworn.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.ConnectStep
import ch.lkmc.pageworn.ui.model.ConnectUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun ConnectScreen(state: ConnectUiState, onUrlChange: (String) -> Unit, onLabelChange: (String) -> Unit, onAllowCleartextChange: (Boolean) -> Unit, onContinue: () -> Unit, onUsernameChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onSignIn: () -> Unit, onTrust: () -> Unit, onRetry: () -> Unit, onAllowLocalNetwork: () -> Unit, onConfirmPairing: () -> Unit, onCancelPairing: () -> Unit, onOpenDownloaded: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.heading, Modifier.padding(top = 16.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.heading)
        when (state.step) {
            ConnectStep.ADDRESS -> {
                OutlinedTextField(state.url, onUrlChange, Modifier.fillMaxWidth(), label = { Text("Server address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, isError = state.addressError != null)
                state.addressError?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                state.fingerprint?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata.copy(fontFamily = FontFamily.Monospace)) }
                if (state.fingerprint != null) TextAction(Copy.TRUST_CERTIFICATE, onClick = onTrust)
                if (state.canRetry) TextAction(Copy.RETRY, onClick = onRetry)
                if (state.canAllowLocalNetwork) TextAction(Copy.ALLOW_ACCESS, onClick = onAllowLocalNetwork)
                OutlinedTextField(state.label, onLabelChange, Modifier.fillMaxWidth(), label = { Text("Label (optional)") }, singleLine = true)
                if (state.url.trim().startsWith("http://", ignoreCase = true) || state.allowCleartext || state.cleartextExplanation != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = state.allowCleartext, role = Role.Checkbox, onValueChange = onAllowCleartextChange), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.allowCleartext, onCheckedChange = null)
                        Text("Allow unencrypted HTTP for this address", Modifier.padding(start = 8.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.metadata)
                    }
                }
                state.cleartextExplanation?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                Button(onClick = onContinue, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Continue") }
            }
            ConnectStep.CREDENTIALS -> {
                OutlinedTextField(state.username, onUsernameChange, Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(state.password, onPasswordChange, Modifier.fillMaxWidth().semantics { contentType = ContentType.Password }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, isError = state.credentialsError != null)
                state.credentialsError?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                Button(onClick = onSignIn, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(Copy.SIGN_IN) }
            }
            ConnectStep.CONFIRM_PAIRING -> {
                state.pairingSummary?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.empty) }
                Button(onClick = onConfirmPairing, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add this address") }
                TextAction(Copy.CANCEL, onClick = onCancelPairing)
            }
        }
        if (state.busy) CircularProgressIndicator(Modifier.padding(8.dp))
        onOpenDownloaded?.let { TextAction("Open Downloaded", onClick = it) }
    }
}
