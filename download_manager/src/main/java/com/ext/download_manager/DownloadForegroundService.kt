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
        const val ACTION_RESUME = "download_manager.action.RESUME"
        const val ACTION_CANCEL = "download_manager.action.CANCEL"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"

        const val BROADCAST_PROGRESS = "download_manager.broadcast.PROGRESS"
        const val EXTRA_DOWNLOADED = "extra_downloaded"
        const val EXTRA_TOTAL = "extra_total"
        const val EXTRA_STATUS = "extra_status"
    }

    private var serviceJob: Job? = null
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val downloader = Downloader()

    // These must survive pause/resume
    private var currentUrl: String? = null
    private var currentFilename: String? = null
    private var currentFile: File? = null
    private var downloadedSoFar = 0L
    private var totalSize = 0L
    private var isPaused = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME)
                    ?: url.substringAfterLast("/").ifEmpty { "file_${System.currentTimeMillis()}" }

                currentUrl = url
                currentFilename = filename
                downloadedSoFar = 0L
                totalSize = 0L
                isPaused = false

                startDownload(url, filename)
            }

            ACTION_PAUSE -> {
                if (!isPaused) {
                    pauseRequested.set(true)
                    isPaused = true
                    currentFilename?.let {
                        NotificationHelper.updateNotification(this, it, 0, true, true, null)
                    }
                    broadcast("paused")
                }
            }

            ACTION_RESUME -> {
                if (isPaused && currentUrl != null && currentFile != null) {
                    pauseRequested.set(false)
                    isPaused = false
                    resumeDownload(currentUrl!!, currentFilename!!, currentFile!!)
                }
            }

            ACTION_CANCEL -> {
                cancelRequested.set(true)
                currentFile?.takeIf { it.exists() }?.delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                broadcast("canceled")
            }
        }
        return START_STICKY
    }

    private fun startDownload(url: String, filename: String) {
        serviceJob?.cancel()
        pauseRequested.set(false)
        cancelRequested.set(false)

        val dir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val file = File(dir, filename)
        currentFile = file
        downloadedSoFar = 0L

        // Always start foreground first
        startForeground(
            NotificationHelper.NOTIF_ID,
            NotificationHelper.buildDownloadNotification(this, filename, 0, true, false)
        )

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            downloader.download(
                url = url,
                output = file,
                startFrom = 0L,
                progressCallback = { downloaded, total ->
                    downloadedSoFar = downloaded
                    totalSize = total
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0

                    NotificationHelper.updateNotification(
                        this@DownloadForegroundService,
                        filename,
                        percent,
                        false,
                        isPaused,
                        null
                    )
                    broadcast("progress", downloaded, total)

                    // Return false = continue downloading?
                    !pauseRequested.get() && !cancelRequested.get()
                },
                cancelChecker = { cancelRequested.get() }
            )

            // Only reach here if download finished naturally
            if (!cancelRequested.get()) {
                NotificationHelper.updateNotification(
                    this@DownloadForegroundService,
                    filename,
                    100,
                    false,
                    false,
                    file.absolutePath
                )
                broadcast("completed", file.length(), file.length())
                delay(60000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun resumeDownload(url: String, filename: String, file: File) {
        serviceJob?.cancel()
        pauseRequested.set(false)
        cancelRequested.set(false)

        // Re-start foreground with correct state
        startForeground(
            NotificationHelper.NOTIF_ID,
            NotificationHelper.buildDownloadNotification(this, filename, 0, true, false)
        )

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            downloader.download(
                url = url,
                output = file,
                startFrom = downloadedSoFar,
                progressCallback = { added, total ->
                    val currentTotal = downloadedSoFar + added
                    val percent = if (total > 0) (currentTotal * 100 / total).toInt() else 0

                    NotificationHelper.updateNotification(
                        this@DownloadForegroundService,
                        filename,
                        percent,
                        false,
                        false,
                        null
                    )
                    broadcast("progress", currentTotal, total)

                    !pauseRequested.get() && !cancelRequested.get()
                },
                cancelChecker = { cancelRequested.get() }
            )

            if (!cancelRequested.get()) {
                NotificationHelper.updateNotification(
                    this@DownloadForegroundService,
                    filename,
                    100,
                    false,
                    false,
                    file.absolutePath
                )
                broadcast("completed", file.length(), file.length())
                delay(60000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun broadcast(status: String, downloaded: Long = 0L, total: Long = -1L) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DOWNLOADED, downloaded)
            putExtra(EXTRA_TOTAL, total)
        })
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}