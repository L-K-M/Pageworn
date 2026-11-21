package ch.lkmc.pageworn.transfer

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import ch.lkmc.pageworn.PagewornApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the durable download queue on Android 12 and 13. The system can restart it after a kill. */
class TransferForegroundService : Service() {
    private var work: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (work?.isActive != true) {
            val container = (application as PagewornApp).container
            val notifications = getSystemService(NotificationManager::class.java)
            TransferNotifications.ensureChannel(this)
            startForeground(TransferNotifications.ID, TransferNotifications.build(this, null))
            work = container.appScope.launch(Dispatchers.Main) {
                do {
                    val drainingStartId = latestStartId
                    withContext(Dispatchers.Default) {
                        container.runTransferQueueWithProgress { job ->
                            notifications?.notify(
                                TransferNotifications.ID,
                                TransferNotifications.build(this@TransferForegroundService, job),
                            )
                        }
                    }
                    // A new start can arrive after the queue checks Room for the last time.
                } while (latestStartId != drainingStartId)
                stopSelf(latestStartId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }
}

internal class ForegroundServiceTransfers(private val context: Context) : TransferScheduler {
    override fun schedule(): Boolean = try {
        context.startForegroundService(Intent(context, TransferForegroundService::class.java)) != null
    } catch (_: ForegroundServiceStartNotAllowedException) {
        // The app left the foreground. The next foreground entry checks Room again.
        false
    }
}
