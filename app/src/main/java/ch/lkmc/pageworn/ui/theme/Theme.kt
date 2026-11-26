package ch.lkmc.pageworn.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.SoundEffectOnInteraction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.lkmc.pageworn.R

data class PagewornColors(
    val paper: Color,
    val raisedSheet: Color,
    val primaryInk: Color,
    val secondaryInk: Color,
    val accent: Color,
    val inkOnAccent: Color,
    /** The inlay ivory of the keys. */
    val inkOnKey: Color,
    val cloth: Color,
    /** Rims and handles. */
    val brass: Color,
) {
    /** The outline that bounds a field, which has to stand out from the paper at 3:1. */
    fun controlEdge(highContrast: Boolean): Color = secondaryInk.copy(alpha = if (highContrast) 1f else CONTROL_EDGE_ALPHA)
}

data class PagewornTypography(
    val heading: TextStyle,
    val playerTitle: TextStyle,
    /** A sheet's title and a heading inside a screen. */
    val sheetTitle: TextStyle,
    val rowTitle: TextStyle,
    /** Authors, and the line that names the chapter. */
    val author: TextStyle,
    val ribbonLabel: TextStyle,
    val metadata: TextStyle,
    val status: TextStyle,
    val empty: TextStyle,
    val time: TextStyle,
)

private val spectral = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
    Font(R.font.spectral_bold, FontWeight.Bold),
    Font(R.font.spectral_italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic),
    Font(R.font.spectral_medium_italic, FontWeight.Medium, androidx.compose.ui.text.font.FontStyle.Italic),
)

// Ordinary Android proportions rather than the mockups' larger ones, which were too large on the
// owner's 360 dp phone (docs/02-interface.md, Type, color and layout).
private val typography = PagewornTypography(
    heading = TextStyle(fontFamily = spectral, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    playerTitle = TextStyle(fontFamily = spectral, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    sheetTitle = TextStyle(fontFamily = spectral, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    rowTitle = TextStyle(fontFamily = spectral, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    author = TextStyle(fontFamily = spectral, fontSize = 15.sp, lineHeight = 20.sp),
    ribbonLabel = TextStyle(fontFamily = spectral, fontSize = 15.sp, lineHeight = 20.sp),
    metadata = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    status = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    empty = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 22.sp),
    time = TextStyle(fontFamily = spectral, fontSize = 14.sp, lineHeight = 19.sp, fontFeatureSettings = "tnum"),
)

// The one designed palette. The app keeps it when the phone is in dark mode: the owner found the
// earlier night palette unfinished, and another theme would need every colour and material redone
// (docs/02-interface.md, Type, color and layout).
private val day = PagewornColors(Color(0xFFF3EBD9), Color(0xFFFAF5E9), Color(0xFF30251E), Color(0xFF625243), Color(0xFF833828), Color(0xFFF3EBD9), Color(0xFFF3EBD9), Color(0xFF233831), Color(0xFFA67C45))

private val LocalColors = staticCompositionLocalOf { day }
private val LocalType = staticCompositionLocalOf { typography }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalHighContrast = staticCompositionLocalOf { false }

object PagewornTheme {
    val colors: PagewornColors @Composable get() = LocalColors.current
    val type: PagewornTypography @Composable get() = LocalType.current
}

@Composable
fun PagewornTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    val highContrast = runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        manager?.javaClass?.methods?.firstOrNull { it.name == "isHighTextContrastEnabled" }?.invoke(manager) as? Boolean ?: false
    }.getOrDefault(false)
    val colors = day
    // Unfilled tracks, chips and outlines are secondary ink washed into the paper, so no Material
    // default (lavender containers) shows through on sliders, progress bars or fields.
    val track = lerp(colors.paper, colors.secondaryInk, 0.22f)
    val scheme = lightColorScheme(
        primary = colors.accent, onPrimary = colors.inkOnAccent,
        primaryContainer = track, onPrimaryContainer = colors.primaryInk,
        secondary = colors.accent, onSecondary = colors.inkOnAccent,
        secondaryContainer = track, onSecondaryContainer = colors.primaryInk,
        tertiary = colors.accent, onTertiary = colors.inkOnAccent,
        tertiaryContainer = track, onTertiaryContainer = colors.primaryInk,
        background = colors.paper, onBackground = colors.primaryInk,
        surface = colors.raisedSheet, onSurface = colors.primaryInk,
        surfaceVariant = colors.paper, onSurfaceVariant = colors.secondaryInk,
        surfaceTint = colors.accent, inverseSurface = colors.primaryInk, inverseOnSurface = colors.paper,
        surfaceBright = colors.raisedSheet, surfaceDim = colors.paper,
        surfaceContainerLowest = colors.raisedSheet, surfaceContainerLow = colors.raisedSheet,
        surfaceContainer = colors.raisedSheet, surfaceContainerHigh = colors.raisedSheet,
        surfaceContainerHighest = track,
        error = colors.accent, onError = colors.inkOnAccent, errorContainer = track, onErrorContainer = colors.primaryInk,
        outline = colors.secondaryInk, outlineVariant = track,
    )
    CompositionLocalProvider(LocalColors provides colors, LocalType provides typography, LocalReducedMotion provides reducedMotion, LocalHighContrast provides highContrast) {
        MaterialTheme(colorScheme = scheme) {
            // Inside MaterialTheme, which provides its ripple as the default indication.
            CompositionLocalProvider(LocalIndication provides InkWash(colors.primaryInk)) {
                // Pageworn provides its own cues and Sound effects setting.
                SoundEffectOnInteraction(enabled = false, content = content)
            }
        }
    }
}

private const val CONTROL_EDGE_ALPHA = 0.75f
