package ch.lkmc.pageworn.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Reads file-backed cover art on the caller's I/O dispatcher. */
internal fun decodeCover(file: File, sizePx: Int): Bitmap? {
    if (sizePx <= 0) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null

    val longestEdge = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while (longestEdge > sizePx * 2L * sample) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
