package com.ext.download_manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DownloadActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DownloadActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            DownloadForegroundService.ACTION_PAUSE -> {
                val serviceIntent = Intent(context, DownloadForegroundService::class.java).apply {
                    action = DownloadForegroundService.ACTION_PAUSE
                }
                context.startService(serviceIntent)
            }

            DownloadForegroundService.ACTION_CANCEL -> {
                val serviceIntent = Intent(context, DownloadForegroundService::class.java).apply {
                    action = DownloadForegroundService.ACTION_CANCEL
                }
                context.startService(serviceIntent)
            }
        }
    }
}