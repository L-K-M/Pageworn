package ch.lkmc.pageworn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lkmc.pageworn.ui.AppNavHost
import ch.lkmc.pageworn.ui.theme.PagewornTheme

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
        setContent {
            PagewornTheme {
                AppNavHost(container, openPlayer)
            }
        }
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
