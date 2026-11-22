package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.DisclosureRow
import ch.lkmc.pageworn.ui.components.PaperCard
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PaperScreen
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun LicencesScreen(entries: List<Pair<String, String>>, onBack: () -> Unit) {
    PaperScreen("Open-source licences", onBack) {
        PaperCard {
            entries.forEachIndexed { index, (name, licence) ->
                var expanded by remember(name) { mutableStateOf(false) }
                if (index > 0) PaperRule(Modifier.padding(horizontal = 16.dp))
                val coordinates = name.substringAfter(" (", "").removeSuffix(")").ifEmpty { null }
                DisclosureRow(name.substringBefore(" ("), coordinates, turned = expanded) { expanded = !expanded }
                if (expanded) Text(licence, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
            }
        }
    }
}
