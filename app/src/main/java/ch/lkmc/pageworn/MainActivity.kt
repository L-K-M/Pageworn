package ch.lkmc.pageworn

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import ch.lkmc.pageworn.ui.AppNavHost
import ch.lkmc.pageworn.ui.components.CoverLoader
import ch.lkmc.pageworn.ui.components.LocalCoverLoader
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the playback notification and the session activity: open the full player. */
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    private var openPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        val container = (application as PagewornApp).container
        val covers = CoverLoader { cover, sizePx ->
            val file = container.remoteCovers.thumbnail(cover.itemId, cover.updatedAt, sizePx) ?: return@CoverLoader null
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
        }
        setContent {
            PagewornTheme {
                CompositionLocalProvider(LocalCoverLoader provides covers) {
                    AppNavHost(container, openPlayer)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The platform runs a transfer job only if it was scheduled while the app was visible.
        val container = (application as PagewornApp).container
        container.appScope.launch { container.transfers.onForeground() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            // Toggle so a second notification tap navigates again.
            openPlayer = false
            openPlayer = true
        }
    }
}
