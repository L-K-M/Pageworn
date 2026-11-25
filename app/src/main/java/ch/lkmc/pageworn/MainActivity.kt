package ch.lkmc.pageworn

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import ch.lkmc.pageworn.artwork.decodeCover
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.AppNavHost
import ch.lkmc.pageworn.ui.BookLink
import ch.lkmc.pageworn.ui.components.CoverLoader
import ch.lkmc.pageworn.ui.components.LocalCoverLoader
import ch.lkmc.pageworn.ui.components.LocalPlayTapSound
import ch.lkmc.pageworn.ui.components.UiSounds
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the playback notification and the session activity: open the full player. */
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_DOWNLOADED = "open_downloaded"

        private const val ACTION_OPEN_BOOK = "ch.lkmc.pageworn.OPEN_BOOK"
        private const val BOOK_SCHEME = "pageworn"

        /** Opens [book]'s details. The book is in the data, so each book gets its own PendingIntent. */
        fun openBookIntent(context: Context, book: BookKey): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BOOK)
                .setData(Uri.fromParts(BOOK_SCHEME, book.mediaId, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    // Each tap counts, so the same link navigates again after the listener left where it led.
    private var openPlayer by mutableIntStateOf(0)
    private var openDownloaded by mutableIntStateOf(0)
    private var openBook by mutableStateOf<BookLink?>(null)
    private var uiSounds: UiSounds? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The paper stays light when the phone is dark, so the bars always draw dark icons. The
        // styles stay automatic, which keeps Android's scrim behind 3-button navigation.
        val overPaper = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { false }
        enableEdgeToEdge(statusBarStyle = overPaper, navigationBarStyle = overPaper)
        super.onCreate(savedInstanceState)
        takeLinks(intent)
        val container = (application as PagewornApp).container
        val sounds = UiSounds(this) { container.settings.uiSoundsEnabled.value }
        uiSounds = sounds
        val covers = CoverLoader { cover, sizePx ->
            val file = container.remoteCovers.thumbnail(cover.itemId, cover.updatedAt, sizePx) ?: return@CoverLoader null
            withContext(Dispatchers.IO) { decodeCover(file, sizePx)?.asImageBitmap() }
        }
        setContent {
            PagewornTheme {
                CompositionLocalProvider(
                    LocalCoverLoader provides covers,
                    LocalPlayTapSound provides sounds::play,
                ) {
                    AppNavHost(container, openPlayer, openDownloaded, openBook)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Android allows the app to start its download service while the screen is visible.
        val container = (application as PagewornApp).container
        container.appScope.launch { container.transfers.onForeground() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLinks(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiSounds?.close()
        uiSounds = null
    }

    private fun takeLinks(intent: Intent) {
        takePlayerLink(intent)
        takeDownloadLink(intent)
        takeBookLink(intent)
    }

    private fun takeBookLink(intent: Intent) {
        if (intent.action != ACTION_OPEN_BOOK) return
        val book = intent.data?.takeIf { it.scheme == BOOK_SCHEME }?.let { BookKey.fromMediaId(it.schemeSpecificPart) }
        intent.action = null
        if (book == null) return
        openPlayer = 0
        openDownloaded = 0
        openBook = BookLink(book)
    }

    private fun takePlayerLink(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) return
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        openBook = null
        openPlayer++
    }

    private fun takeDownloadLink(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADED, false)) return
        intent.removeExtra(EXTRA_OPEN_DOWNLOADED)
        openPlayer = 0
        openBook = null
        openDownloaded++
    }
}
