package com.universalclipboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.URI
import java.util.*

class ClipboardSyncService : Service() {
    
    private val channelId = "clipboard_sync_channel"
    private val notificationId = 1
    
    // Core components
    private lateinit var clipboardManager: ClipboardManager
    private var webSocketClient: WebSocketClient? = null
    
    // State
    private var isServiceRunning = false
    private var serverUri: URI? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "ClipboardSyncService"
        const val ACTION_START_SYNC = "start_sync"
        const val ACTION_STOP_SYNC = "stop_sync"
        const val EXTRA_SERVER_URI = "server_uri"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ClipboardSyncService created")
        
        // Initialize clipboard manager
        clipboardManager = ClipboardManager(this)
        
        // Setup clipboard change listener
        clipboardManager.setOnClipboardChangeListener { clipboardItem ->
            Log.d(TAG, "Clipboard changed: ${clipboardItem.contentType}")
            webSocketClient?.sendClipboardUpdate(clipboardItem)
        }
        
        // Setup sync needed listener
        clipboardManager.setOnSyncNeededListener { clipboardItem ->
            Log.d(TAG, "Sync needed: ${clipboardItem.contentType}")
            webSocketClient?.sendClipboardUpdate(clipboardItem)
        }
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ClipboardSyncService onStartCommand")
        
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val serverUriString = intent.getStringExtra(EXTRA_SERVER_URI)
                if (serverUriString != null) {
                    startSync(serverUriString)
                } else {
                    Log.e(TAG, "No server URI provided")
                    stopSelf()
                }
            }
            ACTION_STOP_SYNC -> {
                stopSync()
                stopSelf()
            }
            else -> {
                // Default action - start with default server
                startSync("ws://192.168.1.100:8765")
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ClipboardSyncService destroyed")
        stopSync()
        scope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startSync(serverUriString: String) {
        try {
            if (isServiceRunning) {
                Log.w(TAG, "Sync already running")
                return
            }
            
            Log.i(TAG, "Starting sync with server: $serverUriString")
            
            // Parse server URI
            serverUri = URI(serverUriString)
            
            // Generate device ID and name
            val deviceId = generateDeviceId()
            val deviceName = getDeviceName()
            
            // Create WebSocket client
            webSocketClient = WebSocketClient(serverUri!!, deviceId, deviceName)
            
            // Setup WebSocket callbacks
            webSocketClient?.setOnConnectedListener {
                Log.i(TAG, "WebSocket connected")
                updateNotification("Connected to ${serverUri!!.host}")
            }
            
            webSocketClient?.setOnDisconnectedListener {
                Log.i(TAG, "WebSocket disconnected")
                updateNotification("Disconnected from server")
            }
            
            webSocketClient?.setOnMessageListener { message ->
                handleWebSocketMessage(message)
            }
            
            webSocketClient?.setOnErrorListener { error ->
                Log.e(TAG, "WebSocket error", error)
                updateNotification("Connection error: ${error.message}")
            }
            
            // Connect WebSocket
            webSocketClient?.connect()
            
            // Start clipboard monitoring
            clipboardManager.startMonitoring()
            
            // Start foreground service
            startForeground(notificationId, createNotification("Starting sync..."))
            
            isServiceRunning = true
            Log.i(TAG, "Sync started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sync", e)
            updateNotification("Error starting sync: ${e.message}")
            stopSelf()
        }
    }
    
    private fun stopSync() {
        try {
            Log.i(TAG, "Stopping sync")
            
            // Stop clipboard monitoring
            clipboardManager.stopMonitoring()
            
            // Disconnect WebSocket
            webSocketClient?.disconnect()
            webSocketClient = null
            
            isServiceRunning = false
            Log.i(TAG, "Sync stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sync", e)
        }
    }
    
    private fun handleWebSocketMessage(message: Map<String, Any>) {
        try {
            val messageType = message["type"] as? String ?: return
            
            when (messageType) {
                "clipboard_update" -> {
                    val clipboardItem = message["clipboard_item"] as? ClipboardManager.ClipboardItem
                    if (clipboardItem != null) {
                        Log.d(TAG, "Received clipboard update: ${clipboardItem.contentType}")
                        
                        // Apply clipboard content to local clipboard
                        clipboardManager.copyToClipboard(clipboardItem.content, clipboardItem.contentType)
                        
                        // Update notification
                        updateNotification("Synced: ${clipboardItem.contentType}")
                    }
                }
                "device_info" -> {
                    val deviceName = message["device_name"] as? String
                    val deviceType = message["device_type"] as? String
                    Log.d(TAG, "Received device info: $deviceName ($deviceType)")
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $messageType")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebSocket message", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val desc = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            channel.description = desc
            channel.setShowBadge(false)
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Universal Clipboard")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }
    
    private fun generateDeviceId(): String {
        val timestamp = System.currentTimeMillis().toString()
        val randomSalt = UUID.randomUUID().toString()
        return hashString("$timestamp$randomSalt")
    }
    
    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
    
    private fun hashString(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
