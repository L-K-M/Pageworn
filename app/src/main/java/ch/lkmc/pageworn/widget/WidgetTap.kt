package ch.lkmc.pageworn.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.KeyAction

/** What a key on a widget does. */
enum class WidgetCommand {
    PLAY, PAUSE, SKIP_BACK, SKIP_FORWARD;

    companion object {
        fun of(action: KeyAction) = when (action) {
            KeyAction.PLAY -> PLAY
            KeyAction.PAUSE -> PAUSE
        }
    }
}

/** One key of one book, as a broadcast to [WidgetActionReceiver]. */
data class WidgetTap(val command: WidgetCommand, val key: BookKey) {
    fun intent(context: Context): Intent =
        Intent(context, WidgetActionReceiver::class.java)
            .setAction(ACTION_PREFIX + command.name)
            // The book is in the data, because extras do not tell two PendingIntents apart.
            .setData(Uri.fromParts(SCHEME, key.mediaId, null))
            // The listener waits for this one: it is not queued behind the system's broadcasts.
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        fun from(intent: Intent): WidgetTap? {
            val action = intent.action ?: return null
            val command = WidgetCommand.entries.firstOrNull { ACTION_PREFIX + it.name == action } ?: return null
            val data = intent.data?.takeIf { it.scheme == SCHEME } ?: return null
            val key = BookKey.fromMediaId(data.schemeSpecificPart) ?: return null
            return WidgetTap(command, key)
        }

        private const val ACTION_PREFIX = "ch.lkmc.pageworn.widget."
        private const val SCHEME = "pageworn"
    }
}
