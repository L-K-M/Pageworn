package ch.lkmc.pageworn.testing

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.lkmc.pageworn.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs [block] on the main thread, where controllers live. */
fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

fun serviceToken(context: Context) = SessionToken(context, ComponentName(context, PlaybackService::class.java))

suspend fun connectController(context: Context): MediaController =
    MediaController.Builder(context, serviceToken(context)).buildAsync().await()

suspend fun connectBrowser(context: Context): MediaBrowser =
    MediaBrowser.Builder(context, serviceToken(context)).buildAsync().await()

/** Polls [condition] on the controller's thread until it holds or [timeoutMs] passes. */
suspend fun Player.awaitUntil(timeoutMs: Long = 10_000, description: String = "condition", condition: Player.() -> Boolean) {
    try {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    } catch (e: Exception) {
        throw AssertionError("Timed out waiting for $description (state=$playbackState, position=$currentPosition, error=$playerError)", e)
    }
}

/** Position within the current period (one period per track of a book item). */
fun Player.positionInCurrentPeriodMs(): Long {
    val period = currentTimeline.getPeriod(currentPeriodIndex, Timeline.Period())
    return currentPosition - period.positionInWindowMs
}
