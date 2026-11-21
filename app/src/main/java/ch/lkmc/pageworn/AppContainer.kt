package ch.lkmc.pageworn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.SystemClockSource
import ch.lkmc.pageworn.playback.EditionSource
import ch.lkmc.pageworn.playback.InMemoryEditionSource
import java.io.File

/**
 * Manual dependency wiring. Tests replace the clock, the edition source and the playback data source
 * (for deterministic faults) before the service starts.
 */
class AppContainer(private val app: PagewornApp) {
    var clock: Clock = SystemClockSource

    /** Local file reads only: the player never sees an HTTP data source. */
    var playbackDataSourceFactory: DataSource.Factory = FileDataSource.Factory()

    var editionSource: EditionSource = InMemoryEditionSource()

    suspend fun coverFileFor(key: BookKey): File? = editionSource.plan(key)?.coverFile

    fun sessionActivity(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
