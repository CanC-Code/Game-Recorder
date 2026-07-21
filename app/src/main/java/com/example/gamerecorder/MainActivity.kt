package com.example.gamerecorder

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var etOutputPath: TextInputEditText

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val outputPath = etOutputPath.text?.toString()?.trim()
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                if (!outputPath.isNullOrEmpty()) {
                    putExtra("output_path", outputPath)
                }
            }
            startForegroundService(serviceIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        etOutputPath = findViewById(R.id.etOutputPath)

        // Clear pre-filled absolute paths to ensure default MediaStore placement in Movies/GameRecorder
        etOutputPath.hint = "Default: Movies/GameRecorder"

        val btnGrantCapture = findViewById<Button>(R.id.btnGrantCapture)
        btnGrantCapture.setOnClickListener {
            checkPermissionsAndProceed()
        }
    }

    private fun checkPermissionsAndProceed() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }
}
