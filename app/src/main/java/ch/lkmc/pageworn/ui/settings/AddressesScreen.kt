package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.components.PaperButton
import ch.lkmc.pageworn.ui.components.PaperCard
import ch.lkmc.pageworn.ui.components.PaperField
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PaperScreen
import ch.lkmc.pageworn.ui.components.PencilIcon
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.feedbackSelectable
import ch.lkmc.pageworn.ui.components.paperSwitchColors
import ch.lkmc.pageworn.ui.components.rememberTapSound
import ch.lkmc.pageworn.ui.model.AddressRow
import ch.lkmc.pageworn.ui.model.AddressesUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun AddressesScreen(state: AddressesUiState, onBack: () -> Unit, onAdd: () -> Unit, onEditLabel: (String, String) -> Unit, onMoveUp: (String) -> Unit, onMoveDown: (String) -> Unit, onToggleEnabled: (String) -> Unit, onTest: (String) -> Unit, onRemove: (String) -> Unit, onTrust: (String) -> Unit, onAllowLocalNetwork: (String) -> Unit, onSignIn: (String) -> Unit, onSetAutomatic: (Boolean) -> Unit, onPin: (String) -> Unit) {
    PaperScreen("Server addresses", onBack) {
        PaperCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).feedbackSelectable(state.automatic, Role.RadioButton, feedbackEnabled = !state.automatic) { onSetAutomatic(true) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.automatic, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Automatic", color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                    Text("Tries the enabled addresses in the order below", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
                }
            }
        }
        state.rows.forEachIndexed { index, row ->
            AddressEntry(row, !state.automatic && state.pinnedAddressId == row.addressId,
                canMoveUp = index > 0, canMoveDown = index < state.rows.lastIndex,
                onEditLabel = { onEditLabel(row.addressId, it) }, onMoveUp = { onMoveUp(row.addressId) }, onMoveDown = { onMoveDown(row.addressId) },
                onToggleEnabled = { onToggleEnabled(row.addressId) }, onTest = { onTest(row.addressId) }, onRemove = { onRemove(row.addressId) },
                onTrust = { onTrust(row.addressId) }, onAllowLocalNetwork = { onAllowLocalNetwork(row.addressId) }, onSignIn = { onSignIn(row.addressId) }, onPin = { onPin(row.addressId) })
        }
        PaperButton("Add address", onAdd, Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
    }
}

@Composable
private fun AddressEntry(row: AddressRow, pinned: Boolean, canMoveUp: Boolean, canMoveDown: Boolean, onEditLabel: (String) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onToggleEnabled: () -> Unit, onTest: () -> Unit, onRemove: () -> Unit, onTrust: () -> Unit, onAllowLocalNetwork: () -> Unit, onSignIn: () -> Unit, onPin: () -> Unit) {
    var editing by remember(row.addressId) { mutableStateOf(false) }
    var draft by remember(row.addressId, row.label) { mutableStateOf(row.label) }
    val sound = rememberTapSound()
    val colors = PagewornTheme.colors
    val type = PagewornTheme.type
    PaperCard(Modifier.semantics {
        customActions = buildList {
            if (canMoveUp) add(CustomAccessibilityAction("Move ${row.label} up") { onMoveUp(); true })
            if (canMoveDown) add(CustomAccessibilityAction("Move ${row.label} down") { onMoveDown(); true })
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editing) PaperField(draft, { draft = it }, "Address label", Modifier.padding(end = 8.dp))
                else Text(row.label, color = colors.primaryInk, style = type.rowTitle)
                Text(row.url, color = colors.secondaryInk, style = type.metadata)
            }
            Switch(checked = row.enabled, onCheckedChange = { sound(); onToggleEnabled() }, colors = paperSwitchColors(), modifier = Modifier.semantics { contentDescription = "Enable ${row.label}" })
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.isActive) StateMark("In use")
            if (pinned) StateMark("Pinned")
            if (row.cleartext) Text(Copy.CLEARTEXT_ALLOWED, color = colors.secondaryInk, style = type.metadata)
            row.lastSuccessText?.let { Text(Copy.lastReached(it), color = colors.secondaryInk, style = type.metadata) }
            row.message?.let { Text(it, color = colors.accent, style = type.status) }
            row.fingerprint?.let { Text(it, color = colors.secondaryInk, style = type.metadata.copy(fontFamily = FontFamily.Monospace)) }
            row.testResult?.let { Text(it, color = colors.primaryInk, style = type.status) }
        }
        FlowRow(Modifier.padding(horizontal = 8.dp)) {
            if (row.canTrustCertificate) TextAction(if (row.message?.contains("changed", ignoreCase = true) == true) Copy.TRUST_NEW_CERTIFICATE else Copy.TRUST_CERTIFICATE, onClick = onTrust)
            if (row.canAllowLocalNetwork) TextAction(Copy.ALLOW_ACCESS, onClick = onAllowLocalNetwork)
            if (row.needsSignIn) TextAction(Copy.SIGN_IN, onClick = onSignIn)
            if (!pinned) TextAction("Use this address", onClick = onPin)
        }
        PaperRule(Modifier.padding(horizontal = 16.dp))
        FlowRow(Modifier.padding(horizontal = 8.dp)) {
            TextAction("Test", onClick = onTest)
            TextAction(if (editing) "Save" else "Edit label") { if (editing) { onEditLabel(draft); editing = false } else editing = true }
            if (canMoveUp) TextAction("Move up", onClick = onMoveUp)
            if (canMoveDown) TextAction("Move down", onClick = onMoveDown)
            TextAction("Remove", onClick = onRemove)
        }
    }
}

/** A pencilled check and the state it marks. */
@Composable
private fun StateMark(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PencilIcon(R.drawable.pw_pencil_check, null, 18.dp)
        Text(text, color = PagewornTheme.colors.accent, style = PagewornTheme.type.metadata)
    }
}
