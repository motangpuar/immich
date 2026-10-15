package app.alextran.immich.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.alextran.immich.R

class RufhUploadNotification(private val ctx: Context) {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Uploads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background media upload progress"
                setShowBadge(false)
            }
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    fun buildProgress(fileName: String, committed: Long, total: Long) =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Uploading $fileName")
            .setContentText("${committed / 1_048_576} / ${total / 1_048_576} MiB")
            .setProgress(100, percent(committed, total), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    fun updateProgress(fileName: String, committed: Long, total: Long) {
        NotificationManagerCompat.from(ctx).notify(
            NOTIFICATION_ID,
            buildProgress(fileName, committed, total),
        )
    }

    fun dismiss() {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
    }

    private fun percent(committed: Long, total: Long): Int =
        if (total <= 0) 0 else ((committed * 100) / total).toInt().coerceIn(0, 100)

    companion object {
        private const val CHANNEL_ID = "immich_rufh_upload"
        private const val NOTIFICATION_ID = 0x1F0F
    }
}
