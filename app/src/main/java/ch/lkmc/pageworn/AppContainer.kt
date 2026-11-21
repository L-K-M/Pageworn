package ch.lkmc.pageworn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.SystemClockSource
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.playback.EditionSource
import ch.lkmc.pageworn.playback.PlaybackHub
import ch.lkmc.pageworn.playback.PlayerConnection
import ch.lkmc.pageworn.ui.state.AppNotices
import ch.lkmc.pageworn.playback.ProgressWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency wiring. Tests replace the clock, the progress writer and the playback data
 * source (for deterministic faults) before the playback service starts.
 */
class AppContainer(private val app: PagewornApp) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wall and elapsed time for rest, sorting and sleep deadlines. Tests inject a fake clock. */
    var clock: Clock = SystemClockSource

    /** Reads [clock] at call time, so a clock injected later reaches every component. */
    val liveClock: Clock = object : Clock {
        override fun wallMillis() = clock.wallMillis()
        override fun elapsedRealtimeMillis() = clock.elapsedRealtimeMillis()
    }

    val database: PagewornDatabase by lazy { PagewornDatabase.build(app) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val progressStore: ProgressStore by lazy { ProgressStore(database.progress(), liveClock) }
    val editionFiles: EditionFiles by lazy { EditionFiles(File(app.filesDir, "audio")) }
    val editionStore: EditionStore by lazy { EditionStore(database, editionFiles, liveClock) }
    val playbackHub = PlaybackHub()
    val playerConnection: PlayerConnection by lazy { PlayerConnection(app, playbackHub) }
    val notices: AppNotices by lazy { AppNotices(settings) }

    /** Where the player's progress writes go. Tests may hold a write back to deliver it late. */
    var progressWriter: ProgressWriter = ProgressWriter.of(progressStore, appScope)

    /** Local file reads only: the player never sees an HTTP data source. */
    var playbackDataSourceFactory: DataSource.Factory = FileDataSource.Factory()

    val editionSource: EditionSource get() = editionStore

    suspend fun coverFileFor(key: BookKey): File? = editionStore.anyPlan(key)?.coverFile

    fun sessionActivity(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
