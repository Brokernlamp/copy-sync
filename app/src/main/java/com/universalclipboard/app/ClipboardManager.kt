package com.universalclipboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.*

class ClipboardManager(private val context: Context) {
    
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callbacks
    private var onClipboardChangeListener: ((ClipboardItem) -> Unit)? = null
    private var onSyncNeededListener: ((ClipboardItem) -> Unit)? = null
    
    // State
    private var lastClipboardHash = ""
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    
    companion object {
        private const val TAG = "ClipboardManager"
        private const val MONITORING_INTERVAL = 100L // milliseconds
    }
    
    data class ClipboardItem(
        val id: String,
        val content: Any,
        val contentType: String,
        val sourceDevice: String,
        val timestamp: Long,
        val size: Int,
        val hash: String,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    fun startMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "Clipboard monitoring already active")
            return
        }
        
        isMonitoring = true
        monitoringJob = scope.launch {
            while (isMonitoring) {
                try {
                    checkClipboardChanges()
                    delay(MONITORING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard monitoring loop", e)
                    delay(1000) // Wait longer on error
                }
            }
        }
        Log.i(TAG, "Clipboard monitoring started")
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        Log.i(TAG, "Clipboard monitoring stopped")
    }
    
    private fun checkClipboardChanges() {
        try {
            val currentContent = getCurrentClipboardContent()
            if (currentContent != null) {
                val currentHash = calculateHash(currentContent)
                if (currentHash != lastClipboardHash) {
                    lastClipboardHash = currentHash
                    handleClipboardChange(currentContent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard changes", e)
        }
    }
    
    private fun getCurrentClipboardContent(): Any? {
        return try {
            if (!clipboardManager.hasPrimaryClip()) {
                return null
            }
            
            val clipData = clipboardManager.primaryClip ?: return null
            val item = clipData.getItemAt(0)
            
            // Check for text
            val text = item.text
            if (!text.isNullOrEmpty()) {
                return text.toString()
            }
            
            // Check for URI (file/image)
            val uri = item.uri
            if (uri != null) {
                return uri
            }
            
            // Check for HTML text
            val htmlText = item.htmlText
            if (!htmlText.isNullOrEmpty()) {
                return htmlText
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clipboard content", e)
            null
        }
    }
    
    private fun handleClipboardChange(content: Any) {
        try {
            Log.i(TAG, "Clipboard content changed, type: ${content::class.simpleName}")
            
            val processedContent = processContent(content)
            val clipboardItem = createClipboardItem(processedContent)
            
            // Notify listeners
            onClipboardChangeListener?.invoke(clipboardItem)
            onSyncNeededListener?.invoke(clipboardItem)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change", e)
        }
    }
    
    private fun processContent(content: Any): Map<String, Any> {
        return when (content) {
            is String -> processTextContent(content)
            is Uri -> processUriContent(content)
            else -> mapOf(
                "type" to "unknown",
                "data" to content.toString(),
                "error" to "Unsupported content type"
            )
        }
    }
    
    private fun processTextContent(text: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["type"] = "text"
        result["data"] = text
        result["length"] = text.length
        result["encoding"] = "utf-8"
        
        // Detect patterns
        val patterns = mutableMapOf<String, List<String>>()
        
        // URL pattern
        val urlPattern = Regex("https?://[^\\s]+")
        val urls = urlPattern.findAll(text).map { it.value }.toList()
        if (urls.isNotEmpty()) {
            patterns["url"] = urls
            result["type"] = "url"
        }
        
        // Email pattern
        val emailPattern = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")
        val emails = emailPattern.findAll(text).map { it.value }.toList()
        if (emails.isNotEmpty()) {
            patterns["email"] = emails
        }
        
        // Code pattern
        val codePattern = Regex("(def|class|function|import|from|if|for|while|try|catch)\\s")
        if (codePattern.containsMatchIn(text)) {
            result["type"] = "code"
            result["code_language"] = detectCodeLanguage(text)
        }
        
        if (patterns.isNotEmpty()) {
            result["patterns"] = patterns
        }
        
        return result
    }
    
    private fun processUriContent(uri: Uri): Map<String, Any> {
        return try {
            val result = mutableMapOf<String, Any>()
            result["type"] = "file"
            result["uri"] = uri.toString()
            
            // Try to get file info
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    
                    if (displayNameIndex >= 0) {
                        result["name"] = it.getString(displayNameIndex) ?: "Unknown"
                    }
                    if (sizeIndex >= 0) {
                        result["size"] = it.getLong(sizeIndex)
                    }
                }
            }
            
            // Check if it's an image
            val mimeType = contentResolver.getType(uri)
            if (mimeType?.startsWith("image/") == true) {
                result["type"] = "image"
                result["mime_type"] = mimeType
                
                // Try to get image dimensions
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                        result["width"] = options.outWidth
                        result["height"] = options.outHeight
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get image dimensions", e)
                }
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error processing URI content", e)
            mapOf(
                "type" to "file",
                "uri" to uri.toString(),
                "error" to e.message
            )
        }
    }
    
    private fun detectCodeLanguage(text: String): String {
        val patterns = mapOf(
            "python" to listOf("def\\s+\\w+\\(", "import\\s+\\w+", "from\\s+\\w+\\s+import", "print\\s*\\("),
            "javascript" to listOf("function\\s+\\w+\\(", "const\\s+\\w+", "let\\s+\\w+", "var\\s+\\w+"),
            "html" to listOf("<html>", "<head>", "<body>", "<div>", "<span>"),
            "css" to listOf("\\{", "\\}", ":\\s*;", "@media", "@import"),
            "sql" to listOf("SELECT\\s+", "INSERT\\s+INTO", "UPDATE\\s+", "DELETE\\s+FROM")
        )
        
        for ((language, patternList) in patterns) {
            for (pattern in patternList) {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    return language
                }
            }
        }
        
        return "unknown"
    }
    
    private fun createClipboardItem(processedContent: Map<String, Any>): ClipboardItem {
        val id = generateId()
        val contentType = processedContent["type"] as? String ?: "unknown"
        val timestamp = System.currentTimeMillis()
        val size = calculateSize(processedContent)
        val hash = calculateHash(processedContent)
        
        return ClipboardItem(
            id = id,
            content = processedContent,
            contentType = contentType,
            sourceDevice = "android",
            timestamp = timestamp,
            size = size,
            hash = hash,
            metadata = processedContent.filterKeys { it != "type" && it != "data" }
        )
    }
    
    private fun generateId(): String {
        val timestamp = System.currentTimeMillis().toString()
        val randomSalt = UUID.randomUUID().toString()
        return hashString("$timestamp$randomSalt")
    }
    
    private fun calculateSize(content: Any): Int {
        return when (content) {
            is String -> content.toByteArray().size
            is Map<*, *> -> {
                val jsonString = JSONObject(content as Map<String, Any>).toString()
                jsonString.toByteArray().size
            }
            else -> content.toString().toByteArray().size
        }
    }
    
    private fun calculateHash(content: Any): String {
        val contentString = when (content) {
            is String -> content
            is Map<*, *> -> JSONObject(content as Map<String, Any>).toString()
            else -> content.toString()
        }
        return hashString(contentString)
    }
    
    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    fun copyToClipboard(content: Any, contentType: String) {
        try {
            when (contentType) {
                "text" -> {
                    val text = content.toString()
                    val clipData = ClipData.newPlainText("Universal Clipboard", text)
                    clipboardManager.setPrimaryClip(clipData)
                }
                "url" -> {
                    val url = content.toString()
                    val clipData = ClipData.newPlainText("Universal Clipboard", url)
                    clipboardManager.setPrimaryClip(clipData)
                }
                "code" -> {
                    val code = content.toString()
                    val clipData = ClipData.newPlainText("Universal Clipboard", code)
                    clipboardManager.setPrimaryClip(clipData)
                }
                else -> {
                    val text = content.toString()
                    val clipData = ClipData.newPlainText("Universal Clipboard", text)
                    clipboardManager.setPrimaryClip(clipData)
                }
            }
            Log.i(TAG, "Content copied to clipboard: $contentType")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }
    
    fun getCurrentClipboardItem(): ClipboardItem? {
        return try {
            val content = getCurrentClipboardContent()
            if (content != null) {
                val processedContent = processContent(content)
                createClipboardItem(processedContent)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current clipboard item", e)
            null
        }
    }
    
    // Callback setters
    fun setOnClipboardChangeListener(listener: (ClipboardItem) -> Unit) {
        onClipboardChangeListener = listener
    }
    
    fun setOnSyncNeededListener(listener: (ClipboardItem) -> Unit) {
        onSyncNeededListener = listener
    }
    
    fun isMonitoring(): Boolean = isMonitoring
    
    fun cleanup() {
        stopMonitoring()
        scope.cancel()
    }
}
