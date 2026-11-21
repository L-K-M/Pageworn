package ch.lkmc.pageworn.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.data.db.TransferEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The user-initiated data transfer job that runs the download queue (docs/03-android-and-data.md,
 * Android integration). It posts its progress notification at once, as the platform requires
 * within ten seconds, and keeps it current while books transfer. When the platform stops the
 * job, the coordinator's state in Room is all that survives, and the job asks to run again.
 */
class TransferJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as PagewornApp).container
        TransferNotifications.ensureChannel(this)
        setNotification(params, TransferNotifications.ID, TransferNotifications.build(this, null), JOB_END_NOTIFICATION_POLICY_REMOVE)
        work = container.appScope.launch {
            val updates = launch {
                combine(container.transfers.active, container.database.transfers().observeActive()) { id, jobs -> jobs.firstOrNull { it.jobId == id } }
                    .collect { job -> setNotification(params, TransferNotifications.ID, TransferNotifications.build(this@TransferJobService, job), JOB_END_NOTIFICATION_POLICY_REMOVE) }
            }
            try {
                container.transfers.runQueue()
                updates.cancel()
                jobFinished(params, false)
            } catch (stopped: CancellationException) {
                updates.cancel()
                throw stopped
            }
        }
        return true
    }

    /** The platform stopped the job; the queue resumes from Room when it runs again. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        const val JOB_ID = 4_201
    }
}

/**
 * Schedules [TransferJobService]; the platform accepts it only while the app is visible.
 *
 * Scheduling the same ID replaces a pending job and stops a running one. The coordinator asks
 * only while no queue runs in this process, so nothing of ours is stopped, and the job listed
 * may be one that just called jobFinished: keeping it instead would strand the new work.
 */
class JobSchedulerTransfers(private val context: Context) : TransferScheduler {
    override fun schedule(): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val info = JobInfo.Builder(TransferJobService.JOB_ID, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        return try {
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (refused: RuntimeException) {
            // Not visible, or another platform refusal: the queue resumes on the next foreground.
            false
        }
    }
}

/** The transfer notification. A denied permission hides it; the transfer runs all the same. */
object TransferNotifications {
    const val ID = 4_202
    private const val CHANNEL = "transfers"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notification_channel_transfers_description)
            },
        )
    }

    fun build(context: Context, job: TransferEntity?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pageworn)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (job == null) return builder.setContentTitle(context.getString(R.string.transfer_preparing)).build()
        val total = job.bytesTotal
        builder.setContentTitle(job.title)
        if (total != null && total > 0) {
            val percent = (job.bytesDone * 100 / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false).setContentText(context.getString(R.string.transfer_percent, percent))
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
