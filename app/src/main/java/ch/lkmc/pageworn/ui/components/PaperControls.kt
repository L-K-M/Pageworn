package ch.lkmc.pageworn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.theme.LocalHighContrast
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** A page behind the bookmarks: a pencilled way back, its heading, and content that scrolls. */
@Composable
fun PaperScreen(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        if (onBack != null) BackAction(onBack)
        Text(
            title,
            Modifier.padding(start = 20.dp, end = 20.dp, top = if (onBack == null) 20.dp else 4.dp, bottom = 12.dp).semantics { heading() },
            color = PagewornTheme.colors.primaryInk,
            style = PagewornTheme.type.heading,
        )
        PaperRule()
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun BackAction(onBack: () -> Unit) {
    val tapFeel = rememberTapFeel(TapFeel.LIGHT)
    Row(
        Modifier.padding(start = 8.dp, top = 4.dp).heightIn(min = 48.dp).clip(CONTROL_SHAPE).clickable(role = Role.Button) { tapFeel(); onBack() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PencilIcon(R.drawable.pw_pencil_collapse, null, 20.dp, Modifier.rotate(QUARTER_TURN))
        Text("Back", color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.ribbonLabel)
    }
}

/** A hairline in pencil, as between rows. */
@Composable
fun PaperRule(modifier: Modifier = Modifier) {
    val alpha = if (LocalHighContrast.current) 1f else RULE_ALPHA
    HorizontalDivider(modifier, color = PagewornTheme.colors.secondaryInk.copy(alpha = alpha))
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 10.dp).semantics { heading() },
        color = PagewornTheme.colors.secondaryInk,
        style = PagewornTheme.type.ribbonLabel.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** A slip of paper that holds the rows that belong together. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = PagewornTheme.colors
    Column(
        modifier.fillMaxWidth()
            .shadow(2.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(colors.raisedSheet)
            .border(1.dp, colors.secondaryInk.copy(alpha = if (LocalHighContrast.current) 1f else CARD_EDGE_ALPHA), CARD_SHAPE),
        content = content,
    )
}

/**
 * A row that leads somewhere, with a pencilled chevron at its end. A row that opens in place passes
 * [expanded]: the chevron turns and TalkBack hears the state.
 */
@Composable
fun DisclosureRow(label: String, detail: String? = null, expanded: Boolean? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { expanded?.let { stateDescription = if (it) "Expanded" else "Collapsed" } }
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick)
            .then(if (focused) Modifier.border(2.dp, PagewornTheme.colors.primaryInk, CONTROL_SHAPE) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
            detail?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
        }
        PencilIcon(R.drawable.pw_pencil_collapse, null, 20.dp, Modifier.rotate(if (expanded == true) 0f else -QUARTER_TURN))
    }
}

/**
 * The main action of a screen, made like the Play key: oxblood leather in a brass rim. A press
 * sinks it, loses the light on its upper half and tightens its shadow, held for a moment after a
 * quick tap as the keys' presses are, and a tap it takes clicks as a key does.
 */
@Composable
fun PaperButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberVisiblePress(interaction)
    val focused by interaction.collectIsFocusedAsState()
    val reduced = LocalReducedMotion.current
    val tapFeel = rememberTapFeel(TapFeel.KEY)
    val depth by animateFloatAsState(if (pressed) 1f else 0f, if (reduced) snap() else tween(if (pressed) PRESS_DOWN_MS else PRESS_UP_MS), label = "button depth")
    val colors = PagewornTheme.colors
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 128.dp)
            .graphicsLayer {
                translationY = if (reduced) 0f else BUTTON_SINK.toPx() * depth
                shadowElevation = if (enabled) lerp(BUTTON_LIFT, BUTTON_PRESSED_LIFT, depth).toPx() else 0f
                shape = KEY_SHAPE
                ambientShadowColor = colors.primaryInk
                spotShadowColor = colors.primaryInk
                alpha = if (enabled) 1f else DISABLED_ALPHA
            }
            .background(Brush.verticalGradient(listOf(lerp(LEATHER_HIGH, LEATHER_LOW, depth), LEATHER_LOW)), KEY_SHAPE)
            .border(if (focused) 3.dp else 2.dp, if (focused) colors.primaryInk else colors.brass, KEY_SHAPE)
            .clip(KEY_SHAPE)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { tapFeel(); onClick() }
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.inkOnKey, style = PagewornTheme.type.rowTitle)
    }
}

@Composable
fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        singleLine = true,
        shape = CARD_SHAPE,
        colors = paperFieldColors(),
    )
}

/** Fields are raised paper with a pencilled edge. */
@Composable
fun paperFieldColors(): TextFieldColors {
    val colors = PagewornTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.raisedSheet,
        unfocusedContainerColor = colors.raisedSheet,
        errorContainerColor = colors.raisedSheet,
        focusedBorderColor = colors.primaryInk,
        unfocusedBorderColor = colors.controlEdge(LocalHighContrast.current),
        focusedLabelColor = colors.primaryInk,
    )
}

@Composable
fun paperSwitchColors(): SwitchColors {
    val colors = PagewornTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.inkOnAccent,
        checkedTrackColor = colors.accent,
        checkedBorderColor = colors.brass,
        uncheckedThumbColor = colors.secondaryInk,
        uncheckedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        uncheckedBorderColor = colors.secondaryInk,
    )
}

/** A question on a slip of paper. */
@Composable
fun PaperDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, dismiss: String, onDismiss: () -> Unit) {
    val colors = PagewornTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = PagewornTheme.type.sheetTitle) },
        text = { Text(text, style = PagewornTheme.type.empty) },
        confirmButton = { TextAction(confirm, onClick = onConfirm) },
        dismissButton = { TextAction(dismiss, onClick = onDismiss) },
        shape = CARD_SHAPE,
        containerColor = colors.raisedSheet,
        titleContentColor = colors.primaryInk,
        textContentColor = colors.secondaryInk,
        tonalElevation = 0.dp,
    )
}

/** A sheet pulled up by its brass handle, under its title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSheet(title: String, state: SheetState, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = PagewornTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.raisedSheet,
        contentColor = colors.primaryInk,
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(40.dp, 5.dp).background(colors.brass, KEY_SHAPE)) },
    ) {
        Text(title, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp).semantics { heading() }, style = PagewornTheme.type.sheetTitle)
        content()
    }
}

/**
 * A pencil mark that acts, such as Settings or the player's collapse mark, in a round 48 dp target:
 * the paper's wash while pressed and a light tick when tapped.
 */
@Composable
fun PencilButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tapFeel = rememberTapFeel(TapFeel.LIGHT)
    Box(modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { tapFeel(); onClick() }, contentAlignment = Alignment.Center) { content() }
}

private val CONTROL_SHAPE = RoundedCornerShape(10.dp)
private val CARD_SHAPE = RoundedCornerShape(14.dp)
private val KEY_SHAPE = RoundedCornerShape(percent = 50)

/** The oxblood of the key art, lit from above. */
private val LEATHER_HIGH = Color(0xFF8A2E25)
private val LEATHER_LOW = Color(0xFF571712)
private const val QUARTER_TURN = 90f
private const val RULE_ALPHA = 0.28f
private const val CARD_EDGE_ALPHA = 0.18f
private const val DISABLED_ALPHA = 0.45f
private val BUTTON_SINK = 1.dp
private val BUTTON_LIFT = 3.dp
private val BUTTON_PRESSED_LIFT = 0.5.dp
private const val PRESS_DOWN_MS = 40
private const val PRESS_UP_MS = 140
