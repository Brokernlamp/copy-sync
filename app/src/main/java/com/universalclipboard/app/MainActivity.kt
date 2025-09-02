package com.universalclipboard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.scanQrButton).setOnClickListener {
            // TODO: Launch QR scanner and save pairing info
            statusText.text = "Paired (demo)"
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            val i = Intent(this, ClipboardSyncService::class.java)
            startForegroundService(i)
            statusText.text = "Sync running"
        }

        findViewById<Button>(R.id.stopServiceButton).setOnClickListener {
            val i = Intent(this, ClipboardSyncService::class.java)
            stopService(i)
            statusText.text = "Sync stopped"
        }
    }
}
