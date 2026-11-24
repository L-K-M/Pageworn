package ch.lkmc.pageworn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.artwork.decodeCover
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Below this size a generated cover shows only the initial of its title. */
private val MIN_LETTERED_COVER = 64.dp

/** Gold leaf on the cloth: the lettering of a generated cover. */
private val COVER_LETTERING = Color(0xFFF4E4C8)

/** Leading words a small cover skips, so "The Night Orchard" reads N rather than T. */
private val LEADING_ARTICLES = setOf("the", "a", "an")

/** Separates the words of a title when picking a small cover's initial. */
private val WORD_SEPARATOR = Regex("\\s+")

/** What a cover's art is doing: loading, on screen, or known to be absent. */
private sealed interface CoverArt {
    data object Loading : CoverArt
    data class Loaded(val image: ImageBitmap) : CoverArt
    data object Missing : CoverArt
}

/** How long fetched art fades in over the cloth. */
private const val COVER_FADE_MS = 180

/** The cover frame of a book row in Library and Downloaded, and the smaller one from 150% text. */
val ROW_COVER = 72.dp
val ROW_COVER_LARGE_TYPE = 60.dp

fun interface CoverLoader { suspend fun load(cover: CoverImage.Remote, sizePx: Int): ImageBitmap? }
val LocalCoverLoader = androidx.compose.runtime.staticCompositionLocalOf<CoverLoader> { CoverLoader { _, _ -> null } }
/** Allows debug previews to provide bundled art without referencing it in production. */
val LocalCoverPainter = androidx.compose.runtime.staticCompositionLocalOf<(@Composable (BookFace) -> Painter?)?> { null }

/**
 * Something laid over a cover's lower end corner, such as a play key: [content] fills the book as
 * drawn, and [reach] is how far in from the book's end edge it covers the art.
 */
class CoverCorner(val reach: Dp, val content: @Composable BoxScope.() -> Unit)

/**
 * A book's cover in a square frame of [size]. Art keeps its shape, so a portrait or landscape book
 * is narrower or shorter than the frame. A [corner] is laid out over the book as drawn, not over the
 * frame, so it stays on the art whatever its shape, and generated lettering keeps clear of it.
 */
@Composable
fun BookCover(face: BookFace, size: Dp, showRibbon: Boolean = false, modifier: Modifier = Modifier, corner: CoverCorner? = null) {
    key(face.cover) {
        BookCoverContent(face, size, showRibbon, modifier, corner)
    }
}

@Composable
private fun BookCoverContent(face: BookFace, size: Dp, showRibbon: Boolean, modifier: Modifier, corner: CoverCorner?) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val loader = LocalCoverLoader.current
    val previewPainter = LocalCoverPainter.current?.invoke(face)
    // While art is loading the cover shows plain cloth (or the previous cover's art, see
    // baseArt below); only a load that came back empty earns the generated lettering.
    val artState by produceState<CoverArt>(CoverArt.Loading, face.cover, sizePx, loader) {
        value = CoverArt.Loading
        value = when (val source = face.cover) {
            is CoverImage.Local -> withContext(Dispatchers.IO) {
                try {
                    decodeCover(source.file, sizePx)?.asImageBitmap()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }?.let(CoverArt::Loaded) ?: CoverArt.Missing
            }
            is CoverImage.Remote -> try {
                loader.load(source, sizePx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.let(CoverArt::Loaded) ?: CoverArt.Missing
            CoverImage.Cloth -> CoverArt.Missing
        }
    }
    // A resize keeps art from the same source on screen while the new bitmap loads; the art
    // then fades in over it instead of dropping the old one in a single frame.
    var baseArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var overlayArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastState by remember { mutableStateOf<CoverArt?>(null) }
    LaunchedEffect(artState) {
        when (val s = artState) {
            is CoverArt.Loading -> {
                // Art that already faded out after an empty load must not flash back in.
                baseArt = if (lastState is CoverArt.Missing) null else overlayArt ?: baseArt
                overlayArt = null
            }
            is CoverArt.Loaded -> overlayArt = s.image
            is CoverArt.Missing -> overlayArt = null
        }
        lastState = artState
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayArt != null) 1f else 0f,
        animationSpec = tween(if (LocalReducedMotion.current) 0 else COVER_FADE_MS),
        label = "coverOverlayArt",
    )
    // The place ribbon scales with the cover (48 x 72 px art, 2:3), sits near the spine and hangs
    // below the lower edge from behind the book.
    val ribbonWidth = maxOf(size * 0.1f, 10.dp)
    val ribbonPeek = ribbonSpace(size)
    // Art keeps its shape: the book is as tall or as wide as the square allows. A Missing
    // result means no art is drawn at all, so the lettering fallback engages.
    val art = (if (artState is CoverArt.Missing) null else overlayArt ?: baseArt)?.takeIf { previewPainter == null }
    // The settled art fades out when a load came back empty, so cloth and lettering take over.
    val baseAlpha by animateFloatAsState(
        targetValue = if (artState is CoverArt.Missing) 0f else 1f,
        animationSpec = tween(if (LocalReducedMotion.current) 0 else COVER_FADE_MS),
        label = "coverBaseArt",
    )
    // The lettering fades with the same spec, so the fallback does not pop in either.
    val letteringAlpha by animateFloatAsState(
        targetValue = if (artState is CoverArt.Missing) 1f else 0f,
        animationSpec = tween(if (LocalReducedMotion.current) 0 else COVER_FADE_MS),
        label = "coverLettering",
    )
    val aspect = art?.let { it.width.toFloat() / it.height } ?: 1f
    val bookWidth = if (aspect < 1f) size * aspect else size
    val bookHeight = if (aspect > 1f) size / aspect else size
    val shape = RoundedCornerShape(maxOf(size * CORNER, 2.dp))
    Box(modifier.size(size, size + if (showRibbon) ribbonPeek else 0.dp)) {
        if (showRibbon) Image(
            painterResource(R.drawable.pw_place_ribbon), null,
            Modifier.padding(start = (size - bookWidth) / 2 + bookWidth * 0.12f, top = size + ribbonPeek - ribbonWidth * 1.5f).size(ribbonWidth, ribbonWidth * 1.5f),
        )
        Box(
            Modifier.padding(start = (size - bookWidth) / 2, top = size - bookHeight).size(bookWidth, bookHeight)
                .shadow(maxOf(size * ELEVATION, 2.dp), shape).clip(shape).background(PagewornTheme.colors.cloth)
                .drawWithContent {
                    drawContent()
                    // The cloth cover has its own crease; flat art is bound here.
                    if (art != null) bindEdges()
                },
        ) {
            if (previewPainter != null) {
                Image(painter = previewPainter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                // Cloth stays beneath the art even when loaded: opaque art hides it, and
                // transparent regions of alpha-bearing art intentionally show cloth through.
                Image(painterResource(R.drawable.pw_cover_cloth), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                // Lettering is part of the cover art: it scales with the cover, not with the font
                // scale, and the row or player beside it carries the readable title. Small covers
                // show an initial when no art will come. While loading they stay plain cloth.
                // Keep the lettering composed while it fades out, or it would vanish a frame
                // before the art fades in.
                if (size >= MIN_LETTERED_COVER && (artState is CoverArt.Missing || letteringAlpha > 0f)) {
                    val titleSize = with(density) { (size * 0.15f).toSp() }
                    val authorSize = with(density) { (size * 0.10f).toSp() }
                    val margin = size * LETTERING_MARGIN
                    Column(Modifier.fillMaxSize().padding(margin).clearAndSetSemantics {}.graphicsLayer { alpha = letteringAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(face.title, color = COVER_LETTERING, style = PagewornTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium, fontSize = titleSize, lineHeight = titleSize * 1.15f), textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        // With something on the lower corner the author line moves to the start,
                        // like an imprint, and ends before it rather than running under it.
                        face.author?.let {
                            val authorStyle = PagewornTheme.type.metadata.copy(fontSize = authorSize, lineHeight = authorSize * 1.2f)
                            if (corner == null) Text(it, color = COVER_LETTERING, style = authorStyle, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Text(it, Modifier.fillMaxWidth().padding(end = (corner.reach + CORNER_CLEARANCE - margin).coerceAtLeast(0.dp)), color = COVER_LETTERING, style = authorStyle, textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else if (size < MIN_LETTERED_COVER && (artState is CoverArt.Missing || letteringAlpha > 0f)) {
                    coverInitial(face.title)?.let { initial ->
                        val initialSize = with(density) { (size * 0.46f).toSp() }
                        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.graphicsLayer { alpha = letteringAlpha }, contentAlignment = Alignment.Center) {
                            Text(initial, color = COVER_LETTERING, style = PagewornTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium, fontSize = initialSize, lineHeight = initialSize))
                        }
                    }
                }
                // The settled art sits under the fading-in overlay; both crossfade with cloth
                // and lettering rather than popping. Previews replace fetched art entirely.
                if (previewPainter == null) {
                    baseArt?.let { bitmap ->
                        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = baseAlpha }, contentScale = ContentScale.Fit)
                    }
                    overlayArt?.let { bitmap ->
                        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = overlayAlpha }, contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        // Outside the book's clip and shadow, so a key over its corner keeps its own rim and target.
        corner?.let {
            Box(Modifier.padding(start = (size - bookWidth) / 2, top = size - bookHeight).size(bookWidth, bookHeight), content = it.content)
        }
    }
}

/**
 * The letter a small generated cover carries: the first letter or digit of the title, after a
 * leading article when more words follow. Null when the title has none.
 */
internal fun coverInitial(title: String): String? {
    val words = title.trim().split(WORD_SEPARATOR).filter { it.isNotEmpty() }
    val first = words.firstOrNull() ?: return null
    val word = if (words.size > 1 && first.lowercase() in LEADING_ARTICLES) words[1] else first

    // Walk code points, not chars: a letter outside the Basic Multilingual Plane is two chars,
    // and neither half counts as a letter on its own.
    var index = 0
    while (index < word.length) {
        val codePoint = word.codePointAt(index)
        val width = Character.charCount(codePoint)
        if (Character.isLetterOrDigit(codePoint)) return word.substring(index, index + width).uppercase()
        index += width
    }
    return null
}

private const val CORNER = 0.04f
private const val ELEVATION = 0.03f

/** The inset of a generated cover's lettering from its edges, as a fraction of the cover's size. */
private const val LETTERING_MARGIN = 0.09f

/** Cloth left between a generated cover's author line and what sits on its corner. */
private val CORNER_CLEARANCE = 3.dp

/** How far a place ribbon hangs below a cover, as a fraction of the cover's size. */
private const val RIBBON_PEEK = 0.11f

/** The least a place ribbon ever hangs below a cover, however small the cover is. */
private val MIN_RIBBON_PEEK = 8.dp

/**
 * The space a cover's ribbon takes below its art. A layout that draws over a cover, as the
 * Downloaded shelf's play key does, holds this space whether or not the book has a saved place,
 * so the cover and what sits on it stay still when a place is saved.
 */
fun ribbonSpace(size: Dp): Dp = maxOf(size * RIBBON_PEEK, MIN_RIBBON_PEEK)

/** Binds flat art as a book: a crease beside the spine, and light along the top and fore edges. */
private fun DrawScope.bindEdges() {
    val spine = size.width * 0.075f
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.22f),
            0.35f to Color.White.copy(alpha = 0.07f),
            0.72f to Color.Transparent,
            0.9f to Color.Black.copy(alpha = 0.26f),
            1f to Color.White.copy(alpha = 0.14f),
            endX = spine,
        ),
        size = Size(spine, size.height),
    )
    val edge = 1.dp.toPx()
    val light = Color.White.copy(alpha = 0.16f)
    val shade = Color.Black.copy(alpha = 0.2f)
    drawRect(light, size = Size(size.width, edge))
    drawRect(shade, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge))
    drawRect(shade, topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
}
