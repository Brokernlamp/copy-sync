package com.universalclipboard.app

import android.util.Log
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class WebSocketClient(
    private val serverUri: URI,
    private val deviceId: String,
    private val deviceName: String
) {
    
    private var webSocket: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callbacks
    private var onConnectedListener: (() -> Unit)? = null
    private var onDisconnectedListener: (() -> Unit)? = null
    private var onMessageListener: ((Map<String, Any>) -> Unit)? = null
    private var onErrorListener: ((Exception) -> Unit)? = null
    
    // Encryption
    private val encryptionKey = generateEncryptionKey()
    private val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    
    companion object {
        private const val TAG = "WebSocketClient"
        private const val PING_INTERVAL = 30000L // 30 seconds
    }
    
    private inner class UniversalWebSocketClient(serverUri: URI) : WebSocketClient(serverUri) {
        
        override fun onOpen(handshake: ServerHandshake?) {
            Log.i(TAG, "WebSocket connection opened")
            
            // Send device info
            sendDeviceInfo()
            
            // Start ping loop
            startPingLoop()
            
            onConnectedListener?.invoke()
        }
        
        override fun onMessage(message: String?) {
            try {
                Log.d(TAG, "Received message: $message")
                
                val messageData = JSONObject(message)
                val messageType = messageData.optString("type")
                
                when (messageType) {
                    "clipboard_update" -> handleClipboardUpdate(messageData)
                    "device_info" -> handleDeviceInfo(messageData)
                    "pong" -> handlePong(messageData)
                    else -> Log.w(TAG, "Unknown message type: $messageType")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message", e)
                onErrorListener?.invoke(e)
            }
        }
        
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "WebSocket connection closed: $code - $reason")
            onDisconnectedListener?.invoke()
        }
        
        override fun onError(ex: Exception?) {
            Log.e(TAG, "WebSocket error", ex)
            onErrorListener?.invoke(ex ?: Exception("Unknown WebSocket error"))
        }
    }
    
    fun connect() {
        try {
            if (webSocket?.isOpen == true) {
                Log.w(TAG, "WebSocket already connected")
                return
            }
            
            webSocket = UniversalWebSocketClient(serverUri)
            webSocket?.connect()
            Log.i(TAG, "Connecting to WebSocket: $serverUri")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to WebSocket", e)
            onErrorListener?.invoke(e)
        }
    }
    
    fun disconnect() {
        try {
            webSocket?.close()
            webSocket = null
            Log.i(TAG, "WebSocket disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
    }
    
    fun sendClipboardUpdate(clipboardItem: ClipboardManager.ClipboardItem) {
        try {
            if (webSocket?.isOpen != true) {
                Log.w(TAG, "WebSocket not connected, cannot send clipboard update")
                return
            }
            
            val message = createClipboardUpdateMessage(clipboardItem)
            webSocket?.send(message)
            Log.d(TAG, "Sent clipboard update: ${clipboardItem.contentType}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending clipboard update", e)
            onErrorListener?.invoke(e)
        }
    }
    
    fun sendPing() {
        try {
            if (webSocket?.isOpen != true) {
                return
            }
            
            val pingMessage = JSONObject().apply {
                put("type", "ping")
                put("timestamp", System.currentTimeMillis())
                put("device_id", deviceId)
            }
            
            webSocket?.send(pingMessage.toString())
            Log.d(TAG, "Sent ping")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }
    
    private fun sendDeviceInfo() {
        try {
            val deviceInfo = JSONObject().apply {
                put("type", "device_info")
                put("device_id", deviceId)
                put("device_name", deviceName)
                put("device_type", "android")
                put("capabilities", listOf("text", "url", "code", "image", "file"))
                put("timestamp", System.currentTimeMillis())
            }
            
            webSocket?.send(deviceInfo.toString())
            Log.d(TAG, "Sent device info")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device info", e)
        }
    }
    
    private fun createClipboardUpdateMessage(clipboardItem: ClipboardManager.ClipboardItem): String {
        val messageData = JSONObject().apply {
            put("type", "clipboard_update")
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("type", clipboardItem.contentType)
                put("data", clipboardItem.content)
                put("size", clipboardItem.size)
                put("hash", clipboardItem.hash)
                put("metadata", JSONObject(clipboardItem.metadata))
            })
        }
        
        return messageData.toString()
    }
    
    private fun handleClipboardUpdate(messageData: JSONObject) {
        try {
            val data = messageData.getJSONObject("data")
            val contentType = data.getString("type")
            val content = data.get("data")
            val size = data.optInt("size", 0)
            val hash = data.optString("hash", "")
            val metadata = data.optJSONObject("metadata")?.let { json ->
                json.keys().asSequence().associateWith { key ->
                    json.get(key)
                }
            } ?: emptyMap()
            
            val clipboardItem = ClipboardManager.ClipboardItem(
                id = generateId(),
                content = content,
                contentType = contentType,
                sourceDevice = messageData.optString("device_id", "unknown"),
                timestamp = System.currentTimeMillis(),
                size = size,
                hash = hash,
                metadata = metadata
            )
            
            val messageMap = mapOf(
                "type" to "clipboard_update",
                "clipboard_item" to clipboardItem
            )
            
            onMessageListener?.invoke(messageMap)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update", e)
        }
    }
    
    private fun handleDeviceInfo(messageData: JSONObject) {
        try {
            val deviceId = messageData.getString("device_id")
            val deviceName = messageData.getString("device_name")
            val deviceType = messageData.getString("device_type")
            val capabilities = messageData.optJSONArray("capabilities")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList()
            
            val messageMap = mapOf(
                "type" to "device_info",
                "device_id" to deviceId,
                "device_name" to deviceName,
                "device_type" to deviceType,
                "capabilities" to capabilities
            )
            
            onMessageListener?.invoke(messageMap)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling device info", e)
        }
    }
    
    private fun handlePong(messageData: JSONObject) {
        try {
            val timestamp = messageData.getLong("timestamp")
            val latency = System.currentTimeMillis() - timestamp
            Log.d(TAG, "Received pong, latency: ${latency}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling pong", e)
        }
    }
    
    private fun startPingLoop() {
        scope.launch {
            while (webSocket?.isOpen == true) {
                delay(PING_INTERVAL)
                sendPing()
            }
        }
    }
    
    private fun generateId(): String {
        val timestamp = System.currentTimeMillis().toString()
        val randomSalt = UUID.randomUUID().toString()
        return hashString("$timestamp$randomSalt")
    }
    
    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun generateEncryptionKey(): SecretKeySpec {
        val password = "universal_clipboard_secret_key_2024"
        val salt = "universal_clipboard_salt"
        val key = "$password$salt".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key)
        return SecretKeySpec(hash, "AES")
    }
    
    private fun encryptData(data: String): String {
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val encryptedBytes = cipher.doFinal(data.toByteArray())
        return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
    }
    
    private fun decryptData(encryptedData: String): String {
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey)
        val encryptedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }
    
    fun isConnected(): Boolean = webSocket?.isOpen == true
    
    // Callback setters
    fun setOnConnectedListener(listener: () -> Unit) {
        onConnectedListener = listener
    }
    
    fun setOnDisconnectedListener(listener: () -> Unit) {
        onDisconnectedListener = listener
    }
    
    fun setOnMessageListener(listener: (Map<String, Any>) -> Unit) {
        onMessageListener = listener
    }
    
    fun setOnErrorListener(listener: (Exception) -> Unit) {
        onErrorListener = listener
    }
    
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
