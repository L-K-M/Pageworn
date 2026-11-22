package ch.lkmc.pageworn.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import java.io.File

/**
 * A book as a picture. The launcher can neither bind art nor load Pageworn's typeface, so the
 * cover's crease, lettering, shadow and ribbon are drawn here, as `BookCover` draws them.
 */
class WidgetCovers(private val context: Context) {
    private data class Drawn(val cover: CoverImage, val title: String, val author: String?, val ribbon: Boolean)

    private val drawn = LruCache<Drawn, Bitmap>(CACHED_COVERS)

    fun bitmap(face: BookFace, ribbon: Boolean): Bitmap {
        val wanted = Drawn(face.cover, face.title, face.author, ribbon)
        return drawn.get(wanted) ?: draw(face, ribbon).also { drawn.put(wanted, it) }
    }

    private fun draw(face: BookFace, ribbon: Boolean): Bitmap {
        val art = (face.cover as? CoverImage.Local)?.let { decode(it.file) }
        val aspect = art?.let { it.width.toFloat() / it.height } ?: 1f
        val width = if (aspect < 1f) SIZE * aspect else SIZE
        val height = if (aspect > 1f) SIZE / aspect else SIZE
        // The picture hugs the book, so art of any shape fills the room the widget gives it.
        val book = RectF(MARGIN, MARGIN, MARGIN + width, MARGIN + height)
        val picture = createBitmap((book.right + MARGIN).toInt(), (book.bottom + RIBBON_PEEK).toInt())
        val canvas = Canvas(picture)

        if (ribbon) drawRibbon(canvas, book)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.widget_cloth)
            setShadowLayer(SHADOW, 0f, SHADOW / 2, SHADOW_COLOR)
        }
        canvas.drawRoundRect(book, CORNER, CORNER, edge)

        val shape = Path().apply { addRoundRect(book, CORNER, CORNER, Path.Direction.CW) }
        canvas.withClip(shape) {
            if (art == null) {
                drawCloth(this, book, face)
            } else {
                drawBitmap(art, null, book, Paint(Paint.FILTER_BITMAP_FLAG))
                bindEdges(this, book)
            }
        }
        return picture
    }

    /** Null for a file that is missing or is not a picture, which then gets a cloth cover. */
    private fun decode(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        options.inSampleSize = generateSequence(1) { it * 2 }
            .first { maxOf(options.outWidth, options.outHeight) / it <= SIZE * 2 }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun drawRibbon(canvas: Canvas, book: RectF) {
        val ribbon = BitmapFactory.decodeResource(context.resources, R.drawable.pw_place_ribbon)
        val bottom = book.bottom + RIBBON_PEEK
        val left = book.left + book.width() * RIBBON_INSET
        val place = RectF(left, bottom - RIBBON_WIDTH * RIBBON_ASPECT, left + RIBBON_WIDTH, bottom)
        canvas.drawBitmap(ribbon, null, place, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawCloth(canvas: Canvas, book: RectF, face: BookFace) {
        val cloth = BitmapFactory.decodeResource(context.resources, R.drawable.pw_cover_cloth)
        canvas.drawBitmap(cloth, null, book, Paint(Paint.FILTER_BITMAP_FLAG))

        val pad = book.width() * LETTERING_INSET
        val room = (book.width() - 2 * pad).toInt()
        val author = face.author?.let { lettering(it, R.font.spectral_regular, AUTHOR_SIZE, room, 1) }
        val bottom = book.bottom - pad - (author?.height ?: 0)
        // As many lines as fit above the author, so the two never overlap.
        val oneLine = lettering(face.title, R.font.spectral_medium, TITLE_SIZE, room, 1).height.coerceAtLeast(1)
        val lines = ((bottom - book.top - pad) / oneLine).toInt().coerceIn(1, TITLE_LINES)
        val title = lettering(face.title, R.font.spectral_medium, TITLE_SIZE, room, lines)
        val top = book.top + pad + ((bottom - book.top - pad - title.height) / 2).coerceAtLeast(0f)
        canvas.withTranslation(book.left + pad, top) { title.draw(this) }
        author?.let { canvas.withTranslation(book.left + pad, bottom) { it.draw(this) } }
    }

    private fun lettering(text: String, font: Int, size: Float, width: Int, lines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LETTERING_COLOR
            textSize = size
            typeface = context.resources.getFont(font)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, LETTERING_LEADING)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    /** A crease beside the spine, and light along the top and fore edges. */
    private fun bindEdges(canvas: Canvas, book: RectF) {
        val spine = book.width() * SPINE
        val crease = Paint().apply {
            shader = LinearGradient(book.left, 0f, book.left + spine, 0f, CREASE_COLORS, CREASE_STOPS, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(book.left, book.top, book.left + spine, book.bottom, crease)
        val light = Paint().apply { color = EDGE_LIGHT }
        val shade = Paint().apply { color = EDGE_SHADE }
        canvas.drawRect(book.left, book.top, book.right, book.top + EDGE, light)
        canvas.drawRect(book.left, book.bottom - EDGE, book.right, book.bottom, shade)
        canvas.drawRect(book.right - EDGE, book.top, book.right, book.bottom, shade)
    }

    private companion object {
        /** Sharp at the largest size a widget draws, and four stay far below the launcher's limit. */
        const val SIZE = 288f
        const val CACHED_COVERS = 10
        const val MARGIN = 10f
        const val CORNER = SIZE * 0.04f
        const val SHADOW = 8f
        const val RIBBON_WIDTH = SIZE * 0.13f
        const val RIBBON_ASPECT = 1.5f
        const val RIBBON_INSET = 0.12f
        const val RIBBON_PEEK = SIZE * 0.13f
        const val LETTERING_INSET = 0.1f
        const val TITLE_SIZE = SIZE * 0.14f
        const val TITLE_LINES = 4
        const val LETTERING_LEADING = 0.86f
        const val AUTHOR_SIZE = SIZE * 0.1f
        const val SPINE = 0.075f
        const val EDGE = 2f
        val LETTERING_COLOR = Color.rgb(0xF4, 0xE4, 0xC8)
        val SHADOW_COLOR = Color.argb(0x55, 0, 0, 0)
        val EDGE_LIGHT = Color.argb(0x29, 0xFF, 0xFF, 0xFF)
        val EDGE_SHADE = Color.argb(0x33, 0, 0, 0)
        val CREASE_COLORS = intArrayOf(
            Color.argb(0x38, 0, 0, 0), Color.argb(0x12, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT,
            Color.argb(0x42, 0, 0, 0), Color.argb(0x24, 0xFF, 0xFF, 0xFF),
        )
        val CREASE_STOPS = floatArrayOf(0f, 0.35f, 0.72f, 0.9f, 1f)
    }
}
