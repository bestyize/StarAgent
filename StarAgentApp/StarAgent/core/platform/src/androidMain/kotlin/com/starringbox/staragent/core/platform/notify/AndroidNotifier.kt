package com.starringbox.staragent.core.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.starringbox.staragent.core.platform.notify.NotificationTarget

class AndroidNotifier(private val context: Context) : Notifier {

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "任务执行通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "定时与托管任务执行完成时的提醒"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun notify(id: String, title: String, content: String) =
        notifyInternal(id, title, content, destination = null)

    override fun notify(id: String, title: String, content: String, destination: NotificationTarget) =
        notifyInternal(id, title, content, destination)

    private fun notifyInternal(id: String, title: String, content: String, destination: NotificationTarget?) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (launchIntent != null && destination != null) {
            when (destination) {
                is NotificationTarget.Task -> launchIntent
                    .putExtra(EXTRA_DESTINATION_TYPE, "task")
                    .putExtra(EXTRA_DESTINATION_ID, destination.taskId)
                is NotificationTarget.Artifact -> launchIntent
                    .putExtra(EXTRA_DESTINATION_TYPE, "artifact")
                    .putExtra(EXTRA_DESTINATION_ID, destination.artifactId)
            }
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentIntent(contentIntent)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "staragent_automations"
        const val EXTRA_DESTINATION_TYPE = "com.starringbox.staragent.notification.destination_type"
        const val EXTRA_DESTINATION_ID = "com.starringbox.staragent.notification.destination_id"
    }
}
