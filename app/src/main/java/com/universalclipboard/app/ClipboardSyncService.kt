package com.universalclipboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ClipboardSyncService : Service() {
    private val channelId = "clipboard_sync_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Universal Clipboard")
            .setContentText("Syncing clipboard across devices")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Start WebSocket client, clipboard listener, pairing, etc.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Stop clients/listeners
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val desc = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            channel.description = desc
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
