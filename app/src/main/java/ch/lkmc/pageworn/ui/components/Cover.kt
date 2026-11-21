package ch.lkmc.pageworn.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Below this size a generated cover shows plain cloth without lettering. */
private val MIN_LETTERED_COVER = 64.dp

fun interface CoverLoader { suspend fun load(cover: CoverImage.Remote, sizePx: Int): ImageBitmap? }
val LocalCoverLoader = androidx.compose.runtime.staticCompositionLocalOf<CoverLoader> { CoverLoader { _, _ -> null } }
/** Allows debug previews to provide bundled art without referencing it in production. */
val LocalCoverPainter = androidx.compose.runtime.staticCompositionLocalOf<(@Composable (BookFace) -> Painter?)?> { null }

@Composable
fun BookCover(face: BookFace, size: Dp, showRibbon: Boolean = false, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val loader = LocalCoverLoader.current
    val previewPainter = LocalCoverPainter.current?.invoke(face)
    val image by produceState<ImageBitmap?>(null, face.cover, sizePx, loader) {
        value = when (val source = face.cover) {
            is CoverImage.Local -> withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.file.absolutePath, bounds)
                    val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= sizePx * 2 || it >= 128 }
                    BitmapFactory.decodeFile(source.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
            is CoverImage.Remote -> runCatching { loader.load(source, sizePx) }.getOrNull()
            CoverImage.Cloth -> null
        }
    }
    // The place ribbon scales with the cover (48 x 72 px art, 2:3), sits near the spine and hangs
    // below the lower edge from behind the book.
    val ribbonWidth = maxOf(size * 0.1f, 10.dp)
    val ribbonPeek = maxOf(size * 0.11f, 8.dp)
    Box(modifier.size(size, size + if (showRibbon) ribbonPeek else 0.dp)) {
        if (showRibbon) Image(painterResource(R.drawable.pw_place_ribbon), null, Modifier.align(Alignment.BottomStart).padding(start = size * 0.12f).size(ribbonWidth, ribbonWidth * 1.5f))
        Box(Modifier.size(size).shadow(3.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)).background(PagewornTheme.colors.raisedSheet)) {
            if (previewPainter != null) {
                Image(painter = previewPainter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (image != null) {
                Image(bitmap = image!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Image(painterResource(R.drawable.pw_cover_cloth), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                // Lettering is part of the cover art: it scales with the cover, not with the font
                // scale, and the row or player beside it carries the readable title. Small covers
                // stay plain cloth.
                if (size >= MIN_LETTERED_COVER) {
                    val titleSize = with(density) { (size * 0.15f).toSp() }
                    val authorSize = with(density) { (size * 0.10f).toSp() }
                    Column(Modifier.fillMaxSize().padding(size * 0.09f).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(face.title, color = Color(0xFFF4E4C8), style = PagewornTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium, fontSize = titleSize, lineHeight = titleSize * 1.15f), textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        face.author?.let { Text(it, color = Color(0xFFF4E4C8), style = PagewornTheme.type.metadata.copy(fontSize = authorSize, lineHeight = authorSize * 1.2f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
