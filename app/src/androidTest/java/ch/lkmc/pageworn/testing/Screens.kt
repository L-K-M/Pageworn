package ch.lkmc.pageworn.testing

import android.graphics.Bitmap
import android.graphics.Color

/** Mean luminance, 0 to 255, of the middle of [bitmap]: high on the day paper, low on a dark one. */
fun centreLuminance(bitmap: Bitmap): Double {
    var total = 0.0
    var count = 0
    for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 8) {
        for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 8) {
            val pixel = bitmap.getPixel(x, y)
            total += .2126 * Color.red(pixel) + .7152 * Color.green(pixel) + .0722 * Color.blue(pixel)
            count++
        }
    }
    return total / count
}

/** How far the luminance of the same screen may move between captures, for changing times and progress. */
const val SAME_PAPER_TOLERANCE = 10.0
