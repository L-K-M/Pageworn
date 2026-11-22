package ch.lkmc.pageworn.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import android.graphics.BitmapFactory
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.MiniPlayerUi
import ch.lkmc.pageworn.ui.player.MiniPlayer
import ch.lkmc.pageworn.ui.theme.LocalHighContrast
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** pw_tab_*.png are 418 × 300 px; the notch apex of the fishtail is at 239 px. */
private const val TAB_ASPECT = 300f / 418f
private const val TAB_NOTCH_APEX = 239f / 300f

/** Smallest size a bookmark label shrinks to so that it stays on one line. */
private val TAB_LABEL_MIN_SIZE = 10.sp

private fun DrawScope.tile(image: ImageBitmap, alpha: Float) {
    drawRect(brush = ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)), alpha = alpha)
}

@Composable
fun Modifier.paperBackground(): Modifier {
    val colors = PagewornTheme.colors
    val highContrast = LocalHighContrast.current
    val context = LocalContext.current
    val image = remember(context, colors.dark) {
        BitmapFactory.decodeResource(context.resources, if (colors.dark) R.drawable.pw_paper_night else R.drawable.pw_paper_day).asImageBitmap()
    }
    return this.background(colors.paper).drawBehind { if (!highContrast) tile(image, 0.10f) }
}

@Composable
fun BindingFooter(destination: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val colors = PagewornTheme.colors
    val type = PagewornTheme.type
    val reducedMotion = LocalReducedMotion.current
    val highContrast = LocalHighContrast.current
    val largeType = LocalDensity.current.fontScale >= 1.5f
    // Fabric is measured at the centre, from the paper seam to the apex of the fishtail notch
    // (docs/08-assets-and-implementation.md, Navigation bookmarks); the tails hang below it.
    val tabWidth = if (largeType) 144.dp else 112.dp
    val tabHeight = tabWidth * TAB_ASPECT
    val exposed = if (largeType) 64.dp else 44.dp
    val tabTop = exposed - tabHeight * TAB_NOTCH_APEX
    val bandHeight = if (largeType) 96.dp else 72.dp
    val context = LocalContext.current
    val cloth = remember(context) { BitmapFactory.decodeResource(context.resources, R.drawable.pw_binding_cloth).asImageBitmap() }
    Column(modifier.background(colors.cloth).drawBehind { if (!highContrast) tile(cloth, 0.25f) }) {
        Box(Modifier.fillMaxWidth().height(4.dp).shadow(2.dp).background(colors.raisedSheet)) {
            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter).background(colors.secondaryInk.copy(alpha = .25f)))
            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.Center).background(colors.secondaryInk.copy(alpha = .16f)))
            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(colors.secondaryInk.copy(alpha = .12f)))
        }
        Row(Modifier.fillMaxWidth().height(bandHeight).clipToBounds().selectableGroup()) {
            for (item in Destination.entries) {
                val selected = item == destination
                val shift by animateDpAsState(if (selected) 8.dp else 0.dp, animationSpec = tween(if (reducedMotion) 0 else 140), label = "bookmark")
                Box(Modifier.weight(1f).height(bandHeight).selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item) }), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.size(width = tabWidth, height = bandHeight), contentAlignment = Alignment.TopCenter) {
                        Image(
                            painterResource(if (item == Destination.LIBRARY) R.drawable.pw_tab_flax else R.drawable.pw_tab_oxblood),
                            null,
                            // Unbounded so the artwork keeps its size; the band clips its top at the seam.
                            Modifier.offset(y = tabTop + shift).wrapContentHeight(Alignment.Top, unbounded = true).requiredSize(width = tabWidth, height = tabHeight),
                        )
                        Box(Modifier.offset(y = shift).width(tabWidth).height(exposed), contentAlignment = Alignment.Center) {
                            // One line on whole fabric: a label too wide for the tab at large text
                            // sizes shrinks rather than breaking inside the word (asset guide).
                            BasicText(
                                if (item == Destination.LIBRARY) "Library" else "Downloaded",
                                Modifier.background(if (highContrast) if (item == Destination.LIBRARY) Color(0xFFFAF5E9) else Color(0xFF833828) else Color.Transparent, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp),
                                style = type.ribbonLabel.copy(
                                    color = if (item == Destination.LIBRARY) Color(0xFF30251E) else Color(0xFFF3EBD9),
                                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                ),
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = TAB_LABEL_MIN_SIZE, maxFontSize = type.ribbonLabel.fontSize),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
fun MainScaffold(destination: Destination, onSelectDestination: (Destination) -> Unit, miniPlayer: MiniPlayerUi?, onOpenPlayer: () -> Unit, onMiniPlayPause: (KeyAction) -> Unit, playNotice: String? = null, onDismissPlayNotice: () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val density = LocalDensity.current
    var miniHeightPx by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().paperBackground()) {
        Box(Modifier.fillMaxWidth().weight(1f).statusBarsPadding()) {
            val covered = miniPlayer != null || playNotice != null
            val bottom = if (covered) with(density) { miniHeightPx.toDp() } else 0.dp
            content(PaddingValues(bottom = bottom))
            if (covered) {
                // It lies on the list: without a pointer input of its own, a touch on its margins
                // falls through to the row beneath.
                Column(Modifier.align(Alignment.BottomCenter).pointerInput(Unit) {}.onSizeChanged { miniHeightPx = it.height }) {
                    playNotice?.let { PlayNotice(it, onDismissPlayNotice) }
                    miniPlayer?.let { MiniPlayer(it, onOpenPlayer, onMiniPlayPause) }
                }
            }
        }
        BindingFooter(destination, onSelectDestination)
    }
}

@Composable
fun SectionHeader(title: String, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.heading)
        androidx.compose.material3.IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            PencilIcon(R.drawable.pw_pencil_settings, "Settings")
        }
    }
}
