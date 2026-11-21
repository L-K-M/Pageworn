package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.AddressRow
import ch.lkmc.pageworn.ui.model.AddressesUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun AddressesScreen(state: AddressesUiState, onBack: () -> Unit, onAdd: () -> Unit, onEditLabel: (String, String) -> Unit, onMoveUp: (String) -> Unit, onMoveDown: (String) -> Unit, onToggleEnabled: (String) -> Unit, onTest: (String) -> Unit, onRemove: (String) -> Unit, onTrust: (String) -> Unit, onAllowLocalNetwork: (String) -> Unit, onSignIn: (String) -> Unit, onSetAutomatic: (Boolean) -> Unit, onPin: (String) -> Unit) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        TextAction("Back", onClick = onBack)
        Text("Server addresses", Modifier.padding(bottom = 12.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.heading)
        Column(Modifier.selectableGroup()) {
            Row(Modifier.fillMaxWidth().selectable(state.automatic, role = Role.RadioButton) { onSetAutomatic(true) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.automatic, onClick = null)
                Text("Automatic", Modifier.padding(start = 8.dp), style = PagewornTheme.type.rowTitle)
            }
        }
        state.rows.forEachIndexed { index, row ->
            HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
            AddressEntry(row, !state.automatic && state.pinnedAddressId == row.addressId,
                canMoveUp = index > 0, canMoveDown = index < state.rows.lastIndex,
                onEditLabel = { onEditLabel(row.addressId, it) }, onMoveUp = { onMoveUp(row.addressId) }, onMoveDown = { onMoveDown(row.addressId) },
                onToggleEnabled = { onToggleEnabled(row.addressId) }, onTest = { onTest(row.addressId) }, onRemove = { onRemove(row.addressId) },
                onTrust = { onTrust(row.addressId) }, onAllowLocalNetwork = { onAllowLocalNetwork(row.addressId) }, onSignIn = { onSignIn(row.addressId) }, onPin = { onPin(row.addressId) })
        }
        TextAction("Add address", onClick = onAdd)
    }
}

@Composable
private fun AddressEntry(row: AddressRow, pinned: Boolean, canMoveUp: Boolean, canMoveDown: Boolean, onEditLabel: (String) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onToggleEnabled: () -> Unit, onTest: () -> Unit, onRemove: () -> Unit, onTrust: () -> Unit, onAllowLocalNetwork: () -> Unit, onSignIn: () -> Unit, onPin: () -> Unit) {
    var editing by remember(row.addressId) { mutableStateOf(false) }
    var draft by remember(row.addressId, row.label) { mutableStateOf(row.label) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics {
        customActions = buildList {
            if (canMoveUp) add(CustomAccessibilityAction("Move ${row.label} up") { onMoveUp(); true })
            if (canMoveDown) add(CustomAccessibilityAction("Move ${row.label} down") { onMoveDown(); true })
        }
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editing) OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Address label") }, singleLine = true)
                else Text(row.label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                Text(row.url, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
            }
            Switch(checked = row.enabled, onCheckedChange = { onToggleEnabled() }, modifier = Modifier.semantics { contentDescription = "Enable ${row.label}" })
        }
        if (row.cleartext) Text(Copy.ALLOW_CLEARTEXT, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
        if (pinned) Text("Pinned", color = PagewornTheme.colors.accent, style = PagewornTheme.type.metadata)
        if (row.isActive) Text("In use", color = PagewornTheme.colors.accent, style = PagewornTheme.type.metadata)
        row.lastSuccessText?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
        row.message?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
        row.fingerprint?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata.copy(fontFamily = FontFamily.Monospace)) }
        row.testResult?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
        if (row.canTrustCertificate) TextAction(if (row.message?.contains("changed", ignoreCase = true) == true) Copy.TRUST_NEW_CERTIFICATE else Copy.TRUST_CERTIFICATE, onClick = onTrust)
        if (row.canAllowLocalNetwork) TextAction(Copy.ALLOW_ACCESS, onClick = onAllowLocalNetwork)
        if (row.needsSignIn) TextAction(Copy.SIGN_IN, onClick = onSignIn)
        Column(Modifier.fillMaxWidth()) {
            TextAction(if (editing) "Save" else "Edit label") { if (editing) { onEditLabel(draft); editing = false } else editing = true }
            TextAction("Test", onClick = onTest)
            TextAction("Remove", onClick = onRemove)
            if (canMoveUp) TextAction("Move up", onClick = onMoveUp)
            if (canMoveDown) TextAction("Move down", onClick = onMoveDown)
            if (!pinned) TextAction("Use this address", onClick = onPin)
        }
    }
}
