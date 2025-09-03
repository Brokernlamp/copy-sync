package com.universalclipboard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var serverIpEditText: EditText
    private lateinit var serverPortEditText: EditText
    
    private var pairedServerUri: String? = null
    
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pairingData = result.data?.getStringExtra(QRScannerActivity.EXTRA_PAIRING_DATA)
            if (pairingData != null) {
                handlePairingData(pairingData)
            }
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission is required for sync status", Toast.LENGTH_LONG).show()
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupClickListeners()
        checkPermissions()
        
        Log.i(TAG, "MainActivity created")
    }
    
    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        serverIpEditText = findViewById(R.id.serverIpEditText)
        serverPortEditText = findViewById(R.id.serverPortEditText)
        
        // Set default values
        serverIpEditText.setText("192.168.1.100")
        serverPortEditText.setText("8765")
        
        updateStatus("Ready to pair")
    }
    
    private fun setupClickListeners() {
        findViewById<Button>(R.id.scanQrButton).setOnClickListener {
            launchQRScanner()
        }
        
        findViewById<Button>(R.id.manualPairButton).setOnClickListener {
            manualPair()
        }
        
        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            startSyncService()
        }
        
        findViewById<Button>(R.id.stopServiceButton).setOnClickListener {
            stopSyncService()
        }
        
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openSettings()
        }
    }
    
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA
        )
        
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
                // Show settings button for denied permissions
                showPermissionSettingsDialog()
            }
        }
    }
    
    private fun showPermissionSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Some permissions are required for the app to work properly. Please grant them in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun launchQRScanner() {
        try {
            val intent = Intent(this, QRScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching QR scanner", e)
            Toast.makeText(this, "Error launching QR scanner: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun handlePairingData(pairingData: String) {
        try {
            val pairingJson = JSONObject(pairingData)
            val serverIp = pairingJson.getString("server_ip")
            val serverPort = pairingJson.getInt("server_port")
            val deviceName = pairingJson.getString("device_name")
            
            pairedServerUri = "ws://$serverIp:$serverPort"
            
            // Update UI
            serverIpEditText.setText(serverIp)
            serverPortEditText.setText(serverPort.toString())
            
            updateStatus("Paired with $deviceName")
            Toast.makeText(this, "Successfully paired with $deviceName", Toast.LENGTH_SHORT).show()
            
            Log.i(TAG, "Paired with device: $deviceName at $serverIp:$serverPort")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling pairing data", e)
            Toast.makeText(this, "Error processing pairing data: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun manualPair() {
        try {
            val serverIp = serverIpEditText.text.toString().trim()
            val serverPort = serverPortEditText.text.toString().trim()
            
            if (serverIp.isEmpty() || serverPort.isEmpty()) {
                Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
                return
            }
            
            val port = serverPort.toIntOrNull()
            if (port == null || port <= 0 || port > 65535) {
                Toast.makeText(this, "Please enter a valid port number (1-65535)", Toast.LENGTH_SHORT).show()
                return
            }
            
            pairedServerUri = "ws://$serverIp:$serverPort"
            updateStatus("Manually paired with $serverIp:$serverPort")
            Toast.makeText(this, "Manually paired with $serverIp:$serverPort", Toast.LENGTH_SHORT).show()
            
            Log.i(TAG, "Manually paired with server: $serverIp:$serverPort")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in manual pairing", e)
            Toast.makeText(this, "Error in manual pairing: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startSyncService() {
        try {
            val serverUri = pairedServerUri ?: run {
                Toast.makeText(this, "Please pair with a device first", Toast.LENGTH_SHORT).show()
                return
            }
            
            val intent = Intent(this, ClipboardSyncService::class.java).apply {
                action = ClipboardSyncService.ACTION_START_SYNC
                putExtra(ClipboardSyncService.EXTRA_SERVER_URI, serverUri)
            }
            
            startForegroundService(intent)
            updateStatus("Starting sync...")
            Toast.makeText(this, "Sync service started", Toast.LENGTH_SHORT).show()
            
            Log.i(TAG, "Started sync service with URI: $serverUri")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sync service", e)
            Toast.makeText(this, "Error starting sync service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopSyncService() {
        try {
            val intent = Intent(this, ClipboardSyncService::class.java).apply {
                action = ClipboardSyncService.ACTION_STOP_SYNC
            }
            
            startService(intent)
            updateStatus("Sync stopped")
            Toast.makeText(this, "Sync service stopped", Toast.LENGTH_SHORT).show()
            
            Log.i(TAG, "Stopped sync service")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sync service", e)
            Toast.makeText(this, "Error stopping sync service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateStatus(message: String) {
        statusText.text = message
        Log.d(TAG, "Status updated: $message")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MainActivity destroyed")
    }
}
