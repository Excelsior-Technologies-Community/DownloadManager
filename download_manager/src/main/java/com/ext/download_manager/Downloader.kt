package com.ext.download_manager

import android.util.Log
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class Downloader {

    companion object {
        private const val TAG = "Downloader"
        private const val BUFFER_SIZE = 8 * 1024
        private const val TIMEOUT = 30000
    }

    // Returns true if download was paused/canceled
    fun download(
        url: String,
        output: File,
        startFrom: Long = 0L,
        progressCallback: (downloaded: Long, total: Long) -> Boolean,  // return true = continue, false = stop
        cancelChecker: () -> Boolean
    ): Boolean {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: RandomAccessFile? = null
        var lastProgressUpdate = System.currentTimeMillis()

        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT

            if (startFrom > 0) {
                connection.setRequestProperty("Range", "bytes=$startFrom-")
            }

            connection.connect()

            if (connection.responseCode !in 200..299 && connection.responseCode != 206) {
                Log.e(TAG, "HTTP ${connection.responseCode}")
                return false
            }

            val total = connection.contentLengthLong.coerceAtLeast(0L) + startFrom
            inputStream = connection.inputStream
            outputStream = RandomAccessFile(output, "rw")
            outputStream.seek(startFrom)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var downloaded = startFrom

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (cancelChecker()) {
                    Log.d(TAG, "Download canceled")
                    return false
                }

                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 500) {
                    if (!progressCallback(downloaded, total)) {
                        Log.d(TAG, "Download paused by user")
                        return true  // paused, not canceled
                    }
                    lastProgressUpdate = now
                }
            }

            progressCallback(downloaded, total)
            return false // completed

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            return false
        } finally {
            inputStream?.close()
            outputStream?.close()
            connection?.disconnect()
        }
    }
}