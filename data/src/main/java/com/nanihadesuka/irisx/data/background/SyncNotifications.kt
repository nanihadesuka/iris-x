package com.nanihadesuka.irisx.data.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.TransferState

/** Builds the quiet ongoing status notification and the "photos saved" notification. */
class SyncNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Camera connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows that Iris X is watching for your camera"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(PHOTOS_CHANNEL, "New photos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tells you when photos were saved from the camera"
            },
        )
    }

    fun status(connection: ConnectionState, transfer: TransferState): Notification {
        val (title, text) = when {
            transfer is TransferState.Downloading ->
                "Downloading photos" to "${transfer.done + 1} of ${transfer.total}  ${transfer.currentName}"
            transfer is TransferState.WifiConnected -> "Connected to camera Wi-Fi" to "Starting transfer"
            transfer is TransferState.Browsing -> "Browsing camera photos" to "Connected to camera Wi-Fi"
            transfer is TransferState.WaitingForCamera -> "Waking camera Wi-Fi" to "Asking the camera to start its network"
            transfer is TransferState.JoiningWifi -> "Connecting to camera Wi-Fi" to "Preparing transfer"
            connection == ConnectionState.Connected -> "Camera connected" to "Syncing location, ready for new photos"
            connection == ConnectionState.Connecting -> "Connecting to camera" to "Almost there"
            connection == ConnectionState.CameraAway -> "Looking for camera" to "Will connect automatically"
            connection is ConnectionState.NeedsAttention -> "Iris X needs attention" to "Open the app to fix it"
            else -> "Iris X" to "No camera paired"
        }
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(launchIntent())
        if (transfer is TransferState.Downloading) {
            builder.setProgress(transfer.total, transfer.done, false)
        }
        return builder.build()
    }

    fun photosSaved(count: Int) {
        val text = if (count == 1) "1 new photo saved" else "$count new photos saved"
        manager.notify(
            PHOTOS_ID,
            NotificationCompat.Builder(context, PHOTOS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(text)
                .setContentText("In Pictures/Iris X, ready for your gallery")
                .setAutoCancel(true)
                .setContentIntent(launchIntent())
                .build(),
        )
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val STATUS_CHANNEL = "status"
        const val PHOTOS_CHANNEL = "photos"
        const val STATUS_ID = 1
        const val PHOTOS_ID = 2
    }
}
