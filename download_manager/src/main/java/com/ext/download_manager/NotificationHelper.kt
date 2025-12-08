package com.ext.download_manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    const val CHANNEL_ID = "download_channel"
    const val CHANNEL_NAME = "Downloads"
    const val NOTIF_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
                setShowBadge(false)
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        progressPercent: Int,
        indeterminate: Boolean
    ): Notification {

        val openIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)

        val piOpen = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(piOpen)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (indeterminate) {
            builder.setProgress(100, 0, true)
        } else {
            builder.setProgress(100, progressPercent, false)
                .setSubText("$progressPercent%")
        }

        return builder.build()
    }

    fun updateProgress(context: Context, title: String, percent: Int, indeterminate: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                buildProgressNotification(context, title, percent, indeterminate)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    fun showCompleted(context: Context, title: String, filePath: String) {
        try {
            val file = File(filePath)

            if (!file.exists()) {
                Log.e(TAG, "Downloaded file does not exist: $filePath")
                return
            }

            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Download Complete")
                .setContentText(title)
                .setSubText("Tap to open")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notif)

            Log.d(TAG, "Completion notification shown for: $title at $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show completion notification", e)
        }
    }

    private fun getMimeType(filename: String): String {
        return when {
            filename.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            filename.endsWith(".zip", ignoreCase = true) -> "application/zip"
            filename.endsWith(".jpg", ignoreCase = true) ||
                    filename.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"

            filename.endsWith(".png", ignoreCase = true) -> "image/png"
            filename.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            filename.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            filename.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "*/*"
        }
    }
}

