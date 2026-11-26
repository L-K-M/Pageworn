package ch.lkmc.pageworn.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.ui.components.PaperSheet
import ch.lkmc.pageworn.ui.components.PencilIcon
import ch.lkmc.pageworn.ui.components.RadioRow
import ch.lkmc.pageworn.ui.components.TapFeel
import ch.lkmc.pageworn.ui.components.feedbackClickable
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** Names the order under the search field, and opens the sheet that changes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySortControl(sort: CatalogSort, onSelect: (CatalogSort) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(SORT_SHAPE).feedbackClickable(Role.Button, TapFeel.LIGHT) { open = true }
                .semantics {
                    contentDescription = Copy.SORT_LIBRARY
                    stateDescription = Copy.sortName(sort)
                }
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
            Text(Copy.sortShortName(sort), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.ribbonLabel)
            PencilIcon(R.drawable.pw_pencil_collapse, null, 16.dp)
        }
    }
    if (!open) return
    PaperSheet(Copy.SORT_LIBRARY, rememberModalBottomSheetState(skipPartiallyExpanded = true), { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).selectableGroup()) {
            CatalogSort.entries.forEach { choice ->
                RadioRow(Copy.sortName(choice), choice == sort) {
                    open = false
                    onSelect(choice)
                }
            }
        }
    }
}

private val SORT_SHAPE = RoundedCornerShape(10.dp)
