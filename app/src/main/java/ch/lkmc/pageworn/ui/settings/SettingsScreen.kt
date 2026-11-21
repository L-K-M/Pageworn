package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.SettingsUiState
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun SettingsScreen(state: SettingsUiState, onBack: () -> Unit, onOpenAddresses: () -> Unit, onSignOut: () -> Unit, onConfirmSignOut: () -> Unit, onDismissSignOut: () -> Unit, onSignIn: () -> Unit, onExportDiagnostics: () -> Unit, onOpenLicences: () -> Unit) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { TextAction("Back", onClick = onBack) }
        Text("Settings", Modifier.padding(bottom = 24.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.heading)
        SettingsRow("Server addresses", onOpenAddresses)
        Text("Account", Modifier.padding(top = 16.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
        state.accountName?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
        TextAction(if (state.signedIn) Copy.SIGN_OUT else Copy.SIGN_IN, onClick = if (state.signedIn) onSignOut else onSignIn)
        SettingsRow("Export diagnostics", onExportDiagnostics)
        SettingsRow("Open-source licences", onOpenLicences)
        Text(state.versionText, Modifier.padding(vertical = 24.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
    }
    if (state.confirmSignOut) AlertDialog(
        onDismissRequest = onDismissSignOut,
        text = { Text(Copy.SIGN_OUT_CONFIRM) },
        confirmButton = { TextButton(onClick = onConfirmSignOut) { Text(Copy.SIGN_OUT) } },
        dismissButton = { TextButton(onClick = onDismissSignOut) { Text(Copy.CANCEL) } },
    )
}

@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
}
