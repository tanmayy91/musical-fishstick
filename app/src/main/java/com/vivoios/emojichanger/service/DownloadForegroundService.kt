package com.vivoios.emojichanger.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vivoios.emojichanger.EmojiChangerApp
import com.vivoios.emojichanger.R
import com.vivoios.emojichanger.db.AppDatabase
import com.vivoios.emojichanger.engine.PackDownloader
import com.vivoios.emojichanger.model.DownloadState
import com.vivoios.emojichanger.model.EmojiPack
import com.vivoios.emojichanger.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Foreground service that handles downloading emoji packs in the background,
 * showing persistent notification with progress.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()
    private lateinit var downloader: PackDownloader
    private lateinit var notificationManager: NotificationManager

    inner class LocalBinder : Binder() {
        fun getService() = this@DownloadForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(applicationContext)
        downloader = PackDownloader(applicationContext, db.emojiPackDao())
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packId = intent?.getStringExtra(EXTRA_PACK_ID) ?: return START_NOT_STICKY
        val packName = intent.getStringExtra(EXTRA_PACK_NAME) ?: "Emoji Pack"
        val driveFileId = intent.getStringExtra(EXTRA_DRIVE_FILE_ID) ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification(packName, 0, 0))
        serviceScope.launch { downloadPack(packId, packName, driveFileId) }

        return START_REDELIVER_INTENT
    }

    private suspend fun downloadPack(packId: String, packName: String, driveFileId: String) {
        val db = AppDatabase.getInstance(applicationContext)
        val pack = db.emojiPackDao().getPackById(packId)
            ?: EmojiPack(
                id = packId,
                name = packName,
                description = "",
                driveFileId = driveFileId,
                downloadUrl = "https://drive.google.com/uc?export=download&id=$driveFileId"
            )

        downloader.downloadPack(pack) { downloaded, total ->
            val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
            updateNotification(packName, progress, downloaded, total)
        }.onSuccess {
            showCompleteNotification(packName)
        }.onFailure { error ->
            showErrorNotification(packName, error.message ?: "Unknown error")
        }

        stopSelf()
    }

    private fun buildNotification(
        packName: String,
        progress: Int,
        downloadedMb: Long
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, EmojiChangerApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_emoji_download)
            .setContentTitle("Downloading $packName")
            .setContentText(if (downloadedMb > 0) "Downloaded ${downloadedMb / (1024 * 1024)} MB" else "Starting download…")
            .setProgress(100, progress, progress == 0)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(packName: String, progress: Int, downloaded: Long, total: Long) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(packName, progress, downloaded)
        )
    }

    private fun showCompleteNotification(packName: String) {
        val notification = NotificationCompat.Builder(this, EmojiChangerApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_emoji_check)
            .setContentTitle("Download Complete")
            .setContentText("$packName is ready to apply!")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_COMPLETE_ID, notification)
    }

    private fun showErrorNotification(packName: String, error: String) {
        val notification = NotificationCompat.Builder(this, EmojiChangerApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_emoji_error)
            .setContentTitle("Download Failed")
            .setContentText("$packName: $error")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ERROR_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val EXTRA_PACK_ID = "extra_pack_id"
        const val EXTRA_PACK_NAME = "extra_pack_name"
        const val EXTRA_DRIVE_FILE_ID = "extra_drive_file_id"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_COMPLETE_ID = 1002
        const val NOTIFICATION_ERROR_ID = 1003
    }
}
