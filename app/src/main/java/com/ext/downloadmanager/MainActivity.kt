package com.ext.downloadmanager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ext.download_manager.DownloadForegroundService
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 123
    }

    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private var lastDownloadedFile: File? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(DownloadForegroundService.EXTRA_STATUS) ?: return
            Log.d(TAG, "✅ Received broadcast: $status")

            runOnUiThread {
                when (status) {
                    "progress" -> {
                        val d = intent.getLongExtra(DownloadForegroundService.EXTRA_DOWNLOADED, 0)
                        val t = intent.getLongExtra(DownloadForegroundService.EXTRA_TOTAL, -1)
                        val text = if (t > 0) {
                            val percent = (d * 100 / t)
                            "⬇️ Downloading: $percent%\n${formatBytes(d)} / ${formatBytes(t)}"
                        } else {
                            "⬇️ Downloading: ${formatBytes(d)}"
                        }
                        tvStatus.text = text
                        Log.d(TAG, "UI Updated: $text")
                    }

                    "completed" -> {
                        tvStatus.text = "✅ Download Completed!\nTap 'View Files' to open"

                        // Store the downloaded file reference
                        val downloadsDir = File(getExternalFilesDir(null), "downloads")
                        val files = downloadsDir.listFiles()
                        if (files != null && files.isNotEmpty()) {
                            lastDownloadedFile = files.maxByOrNull { it.lastModified() }
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "✅ Download completed!",
                            Toast.LENGTH_LONG
                        ).show()

                        // Show dialog to open file
                        showFileOpenDialog()
                    }

                    "paused" -> {
                        tvStatus.text = "⏸️ Download Paused"
                        Toast.makeText(this@MainActivity, "Download paused", Toast.LENGTH_SHORT)
                            .show()
                    }

                    "canceled" -> {
                        tvStatus.text = "❌ Download Canceled"
                        Toast.makeText(this@MainActivity, "Download canceled", Toast.LENGTH_SHORT)
                            .show()
                    }

                    "error" -> {
                        tvStatus.text = "❌ Download Error"
                        Toast.makeText(
                            this@MainActivity,
                            "Download error occurred",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<LinearLayout>(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        Log.d(TAG, "MainActivity onCreate")

        etUrl = findViewById(R.id.etUrl)
        tvStatus = findViewById(R.id.tvStatus)

        // Show current download location
        val downloadsDir = File(getExternalFilesDir(null), "downloads")
        findViewById<TextView>(R.id.tvDownloadPath)?.text =
            "📁 ${downloadsDir.absolutePath}"

        // Check and request notification permission immediately
        checkNotificationPermission()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!checkNotificationPermission()) {
                showNotificationPermissionDialog()
                return@setOnClickListener
            }

            val url = etUrl.text.toString().trim()

            if (url.isEmpty()) {
                Toast.makeText(this, "⚠️ Please enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(
                    this,
                    "⚠️ URL must start with http:// or https://",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            Log.d(TAG, "Starting download for: $url")
            tvStatus.text = "🔄 Starting download..."

            val intent = Intent(this, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START
                putExtra(DownloadForegroundService.EXTRA_URL, url)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.d(TAG, "Service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
                tvStatus.text = "❌ Failed to start download"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnPause).setOnClickListener {
            Log.d(TAG, "Pause button clicked")
            val intent = Intent(this, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_PAUSE
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            Log.d(TAG, "Cancel button clicked")
            val intent = Intent(this, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_CANCEL
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnViewFiles)?.setOnClickListener {
            showDownloadedFiles()
        }
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "Notification permission granted: $isGranted")
            return isGranted
        }
        return true // Pre-Android 13 doesn't need runtime permission
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔔 Notification Permission Required")
            .setMessage("This app MUST have notification permission to show download progress.\n\nWithout it:\n• No download progress visible\n• No completion notification\n• You won't know when downloads finish\n\nPlease grant permission now.")
            .setCancelable(false)
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        PERMISSION_REQUEST_CODE
                    )
                }
            }
            .setNegativeButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showFileOpenDialog() {
        lastDownloadedFile?.let { file ->
            AlertDialog.Builder(this)
                .setTitle("✅ Download Complete")
                .setMessage("${file.name}\n${formatBytes(file.length())}\n\nWould you like to open it?")
                .setPositiveButton("📂 Open") { _, _ ->
                    openFile(file)
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun showDownloadedFiles() {
        val downloadsDir = File(getExternalFilesDir(null), "downloads")

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val files = downloadsDir.listFiles()?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📂 Downloaded Files")
                .setMessage("No files downloaded yet.\n\nDownload location:\n${downloadsDir.absolutePath}")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val fileNames = files.map {
            "📄 ${it.name}\n   ${formatBytes(it.length())} • ${getTimeAgo(it.lastModified())}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📂 Downloaded Files (${files.size})")
            .setItems(fileNames) { _, which ->
                openFile(files[which])
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open Folder") { _, _ ->
                openDownloadsFolder()
            }
            .show()
    }

    private fun openDownloadsFolder() {
        val downloadsDir = File(getExternalFilesDir(null), "downloads")

        if (!downloadsDir.exists() || downloadsDir.listFiles().isNullOrEmpty()) {
            Toast.makeText(this, "No files to show", Toast.LENGTH_SHORT).show()
            return
        }

        val fileToShare = downloadsDir.listFiles()?.get(0) // Pick first file to get a valid URI

        if (fileToShare == null) {
            Toast.makeText(
                this,
                "Download folder path:\n${downloadsDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", fileToShare)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                throw Exception("No file manager")
            }
        } catch (e: Exception) {
            // Fallback: Open system file manager to the closest possible location
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("file://${downloadsDir.absolutePath}")
                type = "resource/folder"
            }

            try {
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                // Final fallback
                Toast.makeText(
                    this,
                    "Open this path manually:\n${downloadsDir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                // Copy to clipboard
                val cm =
                    getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "path",
                        downloadsDir.absolutePath
                    )
                )
                Toast.makeText(this, "Path copied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(Intent.createChooser(intent, "Open with"))
            } catch (e: Exception) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file", e)
            Toast.makeText(
                this,
                "Cannot open file. Please install appropriate app for ${file.extension} files.",
                Toast.LENGTH_LONG
            ).show()
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
            filename.endsWith(
                ".apk",
                ignoreCase = true
            ) -> "application/vnd.android.package-archive"

            else -> "*/*"
        }
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60000 -> "just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            else -> "${diff / 86400000}d ago"
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "📡 onStart - registering receiver")

        val filter = IntentFilter(DownloadForegroundService.BROADCAST_PROGRESS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "📡 onStop - unregistering receiver")
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("✅ Permission Granted")
                    .setMessage("Notification permission granted!\n\nYou'll now see:\n• Download progress\n• Completion notifications\n• Real-time status updates")
                    .setPositiveButton("Start Download") { _, _ ->
                        findViewById<Button>(R.id.btnStart).performClick()
                    }
                    .setNegativeButton("OK", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("❌ Permission Denied")
                    .setMessage("Without notification permission:\n\n❌ No download progress visible\n❌ No completion notification\n❌ Can't see when downloads finish\n\nDownloads will still work but you won't see any notifications.\n\nYou can enable it in:\nSettings > Apps > Download Manager > Permissions > Notifications")
                    .setPositiveButton("Open Settings") { _, _ ->
                        openAppSettings()
                    }
                    .setNegativeButton("Continue Anyway", null)
                    .show()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}

