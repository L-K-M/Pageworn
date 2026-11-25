package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.components.DisclosureRow
import ch.lkmc.pageworn.ui.components.PaperCard
import ch.lkmc.pageworn.ui.components.PaperDialog
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PaperScreen
import ch.lkmc.pageworn.ui.components.SectionLabel
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperSwitchColors
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.SettingsUiState
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun SettingsScreen(state: SettingsUiState, onBack: () -> Unit, onOpenAddresses: () -> Unit, onOpenSleep: () -> Unit, onSetUiSoundsEnabled: (Boolean) -> Unit, onSignOut: () -> Unit, onConfirmSignOut: () -> Unit, onDismissSignOut: () -> Unit, onSignIn: () -> Unit, onExportDiagnostics: () -> Unit, onOpenLicences: () -> Unit, exportFailed: Boolean = false) {
    PaperScreen("Settings", onBack) {
        SectionLabel("Interface")
        PaperCard {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = state.uiSoundsEnabled, role = Role.Switch, onValueChange = onSetUiSoundsEnabled)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sound effects", Modifier.weight(1f), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                Switch(checked = state.uiSoundsEnabled, onCheckedChange = null, colors = paperSwitchColors())
            }
        }
        SectionLabel("Server")
        PaperCard { DisclosureRow("Server addresses", "Where Pageworn finds your server", onClick = onOpenAddresses) }
        SectionLabel("Sleep")
        PaperCard { DisclosureRow("Sleep timer", state.sleepSummary, onClick = onOpenSleep) }
        SectionLabel("Account")
        PaperCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    state.accountName?.let { Text(it, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle) }
                    Text(if (state.signedIn) "Signed in" else "Signed out", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
                }
                TextAction(if (state.signedIn) Copy.SIGN_OUT else Copy.SIGN_IN, onClick = if (state.signedIn) onSignOut else onSignIn)
            }
        }
        SectionLabel("About")
        PaperCard {
            DisclosureRow("Export diagnostics", "A log without passwords, addresses or file names", onClick = onExportDiagnostics)
            if (exportFailed) Text(
                "Couldn’t export diagnostics. Try another location.",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = PagewornTheme.colors.accent,
                style = PagewornTheme.type.status,
            )
            PaperRule(Modifier.padding(horizontal = 16.dp))
            DisclosureRow("Open-source licences", onClick = onOpenLicences)
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.pw_place_ribbon), null, Modifier.size(12.dp, 18.dp))
            Text(state.versionText, Modifier.padding(start = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author)
        }
    }
    if (state.confirmSignOut) PaperDialog(
        title = Copy.SIGN_OUT,
        text = Copy.SIGN_OUT_CONFIRM,
        confirm = Copy.SIGN_OUT,
        onConfirm = onConfirmSignOut,
        dismiss = Copy.CANCEL,
        onDismiss = onDismissSignOut,
    )
}
