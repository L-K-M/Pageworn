package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** One choice of a sheet, with an optional live detail under its label. */
@Composable
fun RadioRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    PaperRule()
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.author.copy(fontSize = PagewornTheme.type.rowTitle.fontSize))
            detail?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
        }
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = PagewornTheme.colors.accent, unselectedColor = PagewornTheme.colors.primaryInk))
    }
}
