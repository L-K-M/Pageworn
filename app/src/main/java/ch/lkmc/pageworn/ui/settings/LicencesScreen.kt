package ch.lkmc.pageworn.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun LicencesScreen(entries: List<Pair<String, String>>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        TextAction("Back", onClick = onBack)
        Text("Open-source licences", Modifier.padding(bottom = 20.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.heading)
        entries.forEach { (name, licence) ->
            var expanded by remember(name) { mutableStateOf(false) }
            Text(name, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 14.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
            if (expanded) Text(licence, Modifier.padding(bottom = 16.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
        }
    }
}
