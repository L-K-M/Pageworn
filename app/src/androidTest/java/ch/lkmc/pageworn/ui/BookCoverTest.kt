package ch.lkmc.pageworn.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.CoverLoader
import ch.lkmc.pageworn.ui.components.LocalCoverLoader
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCoverTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aNewCoverSourceDoesNotShowTheOldArtworkWhileLoading() {
        val first = CoverImage.Remote("profile", "book", 1L)
        val second = first.copy(updatedAt = 2L)
        val face = mutableStateOf(BookFace(BookKey("profile", "book"), "Book", null, first))
        val requested = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<ImageBitmap>()
        val loader = CoverLoader { source, _ ->
            if (source == first) {
                solidBitmap(android.graphics.Color.RED)
            } else {
                requested.complete(Unit)
                replacement.await()
            }
        }

        composeRule.setContent {
            PagewornTheme {
                CompositionLocalProvider(LocalCoverLoader provides loader) {
                    BookCover(face.value, 60.dp, modifier = Modifier.testTag("cover"))
                }
            }
        }
        assertThat(coverCenter()).isEqualTo(Color.Red)

        composeRule.runOnIdle { face.value = face.value.copy(cover = second) }
        composeRule.waitUntil(5_000) { requested.isCompleted }
        assertThat(coverCenter()).isNotEqualTo(Color.Red)

        composeRule.runOnIdle { replacement.complete(solidBitmap(android.graphics.Color.BLUE)) }
        assertThat(coverCenter()).isEqualTo(Color.Blue)
    }

    private fun coverCenter(): Color {
        val pixels = composeRule.onNodeWithTag("cover").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun solidBitmap(color: Int): ImageBitmap =
        android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()
}
