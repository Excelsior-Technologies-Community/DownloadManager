package com.ext.download_manager

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Downloader {

    companion object {
        private const val TAG = "Downloader"
        private const val BUFFER_SIZE = 8 * 1024 // 8KB
        private const val TIMEOUT = 30000 // 30 seconds
    }

    fun download(
        url: String,
        output: File,
        progressCallback: (downloaded: Long, total: Long) -> Unit,
        cancelChecker: () -> Boolean
    ) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            Log.d(TAG, "Starting download from: $url")

            val urlConnection = URL(url).openConnection() as HttpURLConnection
            connection = urlConnection

            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.requestMethod = "GET"
            connection.connect()

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode !in 200..299) {
                throw Exception("HTTP error: $responseCode")
            }

            val total = connection.contentLengthLong
            Log.d(TAG, "Content length: $total bytes")

            inputStream = connection.inputStream
            outputStream = FileOutputStream(output)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var downloaded = 0L
            var lastProgressUpdate = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (cancelChecker()) {
                    Log.d(TAG, "Download cancelled by checker")
                    break
                }

                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                // Update progress every 500ms to avoid too many updates
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 500) {
                    progressCallback(downloaded, total)
                    lastProgressUpdate = now
                }
            }

            // Final progress update
            progressCallback(downloaded, total)

            outputStream.flush()
            Log.d(TAG, "Download completed: $downloaded bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            throw e
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing resources", e)
            }
        }
    }
}

