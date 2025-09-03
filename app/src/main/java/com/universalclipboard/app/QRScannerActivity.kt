package com.universalclipboard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentIntegrator
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class QRScannerActivity : AppCompatActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startQRScanner()
        } else {
            Toast.makeText(this, "Camera permission is required for QR scanning", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQRResult(result.contents)
        } else {
            Toast.makeText(this, "QR scan cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    companion object {
        private const val TAG = "QRScannerActivity"
        const val EXTRA_PAIRING_DATA = "pairing_data"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startQRScanner()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    private fun startQRScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanIntentIntegrator.QR_CODE)
            setPrompt("Scan QR code to pair with desktop device")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(false)
        }
        
        qrScanLauncher.launch(options)
    }
    
    private fun handleQRResult(qrContent: String) {
        try {
            Log.d(TAG, "QR Code scanned: $qrContent")
            
            // Try to parse as JSON first
            val pairingData = try {
                JSONObject(qrContent)
            } catch (e: Exception) {
                // If not JSON, create a simple pairing data object
                JSONObject().apply {
                    put("server_ip", qrContent)
                    put("server_port", 8765)
                    put("device_name", "Desktop Device")
                    put("pairing_code", "default")
                }
            }
            
            // Validate pairing data
            if (isValidPairingData(pairingData)) {
                // Return pairing data to calling activity
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_PAIRING_DATA, pairingData.toString())
                }
                setResult(RESULT_OK, resultIntent)
                Toast.makeText(this, "Device paired successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling QR result", e)
            Toast.makeText(this, "Error processing QR code: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
    
    private fun isValidPairingData(pairingData: JSONObject): Boolean {
        return try {
            // Check for required fields
            pairingData.has("server_ip") && 
            pairingData.has("server_port") &&
            pairingData.has("device_name")
        } catch (e: Exception) {
            Log.e(TAG, "Error validating pairing data", e)
            false
        }
    }
}
