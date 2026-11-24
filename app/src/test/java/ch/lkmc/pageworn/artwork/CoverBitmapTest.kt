package ch.lkmc.pageworn.artwork

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverBitmapTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun bothOrientationsFitTheSameDecodeBudget() {
        assertThat(decodedSize(256, 8192)).isEqualTo(8 to 256)
        assertThat(decodedSize(8192, 256)).isEqualTo(256 to 8)
    }

    @Test fun intermediateDimensionsUseTheNextSampleSize() {
        assertThat(decodedSize(300, 450)).isEqualTo(150 to 225)
    }

    @Test fun smallCoversKeepTheirPixels() {
        assertThat(decodedSize(64, 128)).isEqualTo(64 to 128)
    }

    @Test fun unreadableArtUsesTheClothFallback() {
        val file = folder.newFile().apply { writeText("no image") }
        assertThat(decodeCover(file, 128)).isNull()
    }

    private fun decodedSize(width: Int, height: Int): Pair<Int, Int> {
        val file = folder.newFile()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = checkNotNull(decodeCover(file, 128))
        return image.width to image.height
    }
}
