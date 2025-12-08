package com.ext.download_manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    const val CHANNEL_ID = "download_channel"
    const val NOTIF_ID = 1001

    // Action constants
    const val ACTION_PAUSE = "download_manager.action.PAUSE"
    const val ACTION_RESUME = "download_manager.action.RESUME"
    const val ACTION_CANCEL = "download_manager.action.CANCEL"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress and controls"
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildDownloadNotification(
        context: Context,
        filename: String,
        progress: Int,
        isIndeterminate: Boolean,
        isPaused: Boolean,
        filePath: String? = null  // only when completed
    ): Notification {

        // Correctly define pause/resume action inside the function
        val pauseResumeAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Resume",
                getPendingIntent(context, ACTION_RESUME)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                getPendingIntent(context, ACTION_PAUSE)
            )
        }

        val cancelAction = NotificationCompat.Action(
            android.R.drawable.ic_delete,
            "Cancel",
            getPendingIntent(context, ACTION_CANCEL)
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(filename)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, isIndeterminate)
            .apply {
                if (progress in 1..99) setSubText("$progress%")
            }
            .addAction(pauseResumeAction)
            .addAction(cancelAction)

        // When download is complete → show "View" button
        if (filePath != null && File(filePath).exists()) {
            val file = File(filePath)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val viewPendingIntent = PendingIntent.getActivity(
                context,
                0,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder
                .setContentTitle("Download Complete")
                .setContentText(filename)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .clearActions()
                .addAction(
                    NotificationCompat.Action(
                        android.R.drawable.ic_menu_view,
                        "View",
                        viewPendingIntent
                    )
                )
                .setContentIntent(viewPendingIntent)
        }

        return builder.build()
    }

    fun updateNotification(
        context: Context,
        filename: String,
        progress: Int = 0,
        isIndeterminate: Boolean = true,
        isPaused: Boolean = false,
        filePath: String? = null
    ) {
        val notification = buildDownloadNotification(
            context = context,
            filename = filename,
            progress = progress,
            isIndeterminate = isIndeterminate,
            isPaused = isPaused,
            filePath = filePath
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun getPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName) // Required on Android 12+
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getMimeType(filename: String): String = when {
        filename.endsWith(".pdf", true) -> "application/pdf"
        filename.endsWith(".zip", true) -> "application/zip"
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".png", true) -> "image/png"
        filename.endsWith(".mp4", true) -> "video/mp4"
        filename.endsWith(".mp3", true) -> "audio/mpeg"
        filename.endsWith(".txt", true) -> "text/plain"
        else -> "*/*"
    }
}