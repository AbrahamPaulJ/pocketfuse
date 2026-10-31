package io.github.abrahampaulj.pocketfuse.jobs

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.abrahampaulj.pocketfuse.MainActivity
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.core.SwapProgress
import io.github.abrahampaulj.pocketfuse.core.SwapRequest
import io.github.abrahampaulj.pocketfuse.core.SwapRunner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one swap in the foreground, so the upload, the wait and the download survive the user leaving
 * the app, and the session keeps being refreshed while the PC works. Progress shows in a notification;
 * the result arrives as a second notification that opens the app on it.
 */
class JobService : Service() {
    // SwapRunner turns every known failure into a message; anything else ends the swap, never the app.
    private val unexpected = CoroutineExceptionHandler { _, _ ->
        val failed = SwapProgress.Failed(getString(R.string.error_job_unexpected), retryable = false)
        app.jobs.report(failed)
        finished(failed)
        ServiceCompat.stopForeground(this@JobService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + unexpected)
    private val app get() = application as PocketfuseApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_PROGRESS, progressNotification(getString(R.string.job_starting), null), type)

        val request = app.jobs.takePending()
        val connection = app.connection.value
        if (request == null || connection == null) {
            app.jobs.report(SwapProgress.Failed(getString(R.string.error_not_paired), retryable = false))
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            val runner = SwapRunner(connection, app.jobs.outputDirectory)
            var next: SwapRequest? = request
            while (next != null) {
                val current = next
                val result = runner.run(current.sources, current.target, current.options, current.voice) { progress ->
                    app.jobs.report(progress)
                    describe(progress)?.let { (text, fraction) ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PROGRESS, progressNotification(text, fraction))
                    }
                }
                app.jobs.finishedOne()
                next = app.jobs.takePending()
                // In a batch the last result is what the user sees; one failed target does not stop the rest.
                if (next == null) finished(result) else app.jobs.report(SwapProgress.Preparing)
            }
            ServiceCompat.stopForeground(this@JobService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // A killed swap is not restarted blindly: the user sees it failed and decides.
        return START_NOT_STICKY
    }

    private fun describe(progress: SwapProgress): Pair<String, Float?>? = when (progress) {
        is SwapProgress.Uploading -> getString(R.string.job_uploading, progress.what) to progress.fraction
        SwapProgress.Preparing -> getString(R.string.job_preparing) to null
        is SwapProgress.Processing -> getString(R.string.job_processing, progress.elapsedSeconds) to null
        is SwapProgress.Downloading -> getString(R.string.job_downloading) to progress.fraction
        else -> null
    }

    private fun finished(result: SwapProgress) {
        val text = when (result) {
            is SwapProgress.Done -> getString(R.string.job_done, result.seconds)
            is SwapProgress.Failed -> result.message
            else -> return
        }
        val notification = NotificationCompat.Builder(this, PocketfuseApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (result is SwapProgress.Done) R.string.job_done_title else R.string.job_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_DONE, notification)
    }

    private fun progressNotification(text: String, fraction: Float?): Notification =
        NotificationCompat.Builder(this, PocketfuseApp.CHANNEL_JOBS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.job_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_PROGRESS = 1
        const val NOTIFICATION_DONE = 2
    }
}
