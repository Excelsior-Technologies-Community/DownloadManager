package com.ext.download_manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DownloadForegroundService : Service() {

    companion object {
        const val TAG = "DownloadService"
        const val ACTION_START = "download_manager.action.START"
        const val ACTION_PAUSE = "download_manager.action.PAUSE"
        const val ACTION_CANCEL = "download_manager.action.CANCEL"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"

        const val BROADCAST_PROGRESS = "download_manager.broadcast.PROGRESS"
        const val EXTRA_DOWNLOADED = "extra_downloaded"
        const val EXTRA_TOTAL = "extra_total"
        const val EXTRA_STATUS = "extra_status"
    }

    private var serviceJob: Job? = null
    private val pauseFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private val downloader = Downloader()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrEmpty()) {
                    Log.e(TAG, "URL is null or empty")
                    return START_NOT_STICKY
                }
                val fileName = intent.getStringExtra(EXTRA_FILENAME)
                    ?: url.substringAfterLast("/")
                        .ifEmpty { "download_${System.currentTimeMillis()}" }

                Log.d(TAG, "Starting download: $url -> $fileName")
                startDownload(url, fileName)
            }

            ACTION_PAUSE -> {
                Log.d(TAG, "Pause requested")
                pauseFlag.set(true)
            }

            ACTION_CANCEL -> {
                Log.d(TAG, "Cancel requested")
                cancelFlag.set(true)
            }
        }

        return START_STICKY
    }

    private fun broadcast(status: String, downloaded: Long = 0, total: Long = -1) {
        Log.d(TAG, "Broadcasting: status=$status, downloaded=$downloaded, total=$total")
        val i = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DOWNLOADED, downloaded)
            putExtra(EXTRA_TOTAL, total)
        }
        sendBroadcast(i)
    }

    private fun startDownload(url: String, filename: String) {
        // Cancel any existing download
        serviceJob?.cancel()

        pauseFlag.set(false)
        cancelFlag.set(false)

        val downloadsDir = File(getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val dest = File(downloadsDir, filename)
        Log.d(TAG, "Download destination: ${dest.absolutePath}")

        // Start foreground service with notification
        try {
            startForeground(
                NotificationHelper.NOTIF_ID,
                NotificationHelper.buildProgressNotification(
                    this,
                    filename,
                    progressPercent = 0,
                    indeterminate = true
                )
            )
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            broadcast("error")
            stopSelf()
            return
        }

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting download coroutine")
                broadcast("progress", 0, -1)

                downloader.download(
                    url = url,
                    output = dest,
                    progressCallback = { downloaded, total ->
                        if (pauseFlag.get()) {
                            Log.d(TAG, "Pause flag detected")
                            throw Exception("Paused")
                        }
                        if (cancelFlag.get()) {
                            Log.d(TAG, "Cancel flag detected")
                            throw Exception("Canceled")
                        }

                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                        // Update notification - can be called from any thread
                        NotificationHelper.updateProgress(
                            this@DownloadForegroundService,
                            filename,
                            percent,
                            total <= 0
                        )

                        broadcast("progress", downloaded, total)
                    },
                    cancelChecker = { cancelFlag.get() }
                )

                Log.d(TAG, "Download completed successfully")

                // Show completion notification - can be called from any thread
                NotificationHelper.showCompleted(
                    this@DownloadForegroundService,
                    filename,
                    dest.absolutePath
                )

                broadcast("completed", dest.length(), dest.length())

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)

                when (e.message) {
                    "Paused" -> broadcast("paused")
                    "Canceled" -> {
                        broadcast("canceled")
                        if (dest.exists()) dest.delete()
                    }

                    else -> broadcast("error")
                }

            } finally {
                Log.d(TAG, "Download finished – keeping notification")
                // Do NOT call stopForeground(true) → keep the completion notification alive
                // Do NOT call stopSelf() immediately → let user dismiss it
                // Optional: change icon to "done" and make it cancellable
                NotificationHelper.showCompleted(
                    this@DownloadForegroundService,
                    filename,
                    dest.absolutePath
                )

                // Only stop foreground after 30 seconds so user can see it
                CoroutineScope(Dispatchers.Main).launch {
                    delay(30000)  // keep for 30 seconds
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceJob?.cancel()
    }
}

