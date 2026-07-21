package com.example.gamerecorder

import android.app.Activity
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

class MainActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var etOutputPath: TextInputEditText
    private lateinit var rvVideoGallery: RecyclerView
    private lateinit var tvEmptyGallery: TextView
    private lateinit var videoAdapter: VideoAdapter

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
        rvVideoGallery = findViewById(R.id.rvVideoGallery)
        tvEmptyGallery = findViewById(R.id.tvEmptyGallery)

        val btnRefreshGallery = findViewById<ImageButton>(R.id.btnRefreshGallery)
        btnRefreshGallery.setOnClickListener { refreshGallery() }

        videoAdapter = VideoAdapter(
            videos = emptyList(),
            onItemClick = { video -> playVideo(video) },
            onDeleteClick = { video -> confirmDeleteVideo(video) }
        )
        rvVideoGallery.adapter = videoAdapter

        val btnGrantCapture = findViewById<Button>(R.id.btnGrantCapture)
        btnGrantCapture.setOnClickListener {
            checkPermissionsAndProceed()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGallery()
    }

    private fun refreshGallery() {
        val videoList = loadVideosFromMediaStore()
        if (videoList.isEmpty()) {
            rvVideoGallery.visibility = View.GONE
            tvEmptyGallery.visibility = View.VISIBLE
        } else {
            rvVideoGallery.visibility = View.VISIBLE
            tvEmptyGallery.visibility = View.GONE
            videoAdapter.updateVideos(videoList)
        }
    }

    private fun loadVideosFromMediaStore(): List<VideoItem> {
        val videoList = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("GameRecorder_%", "PokemonUnite_%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Untitled"
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    videoList.add(VideoItem(id, contentUri, name, duration, size, dateAdded))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return videoList
    }

    private fun playVideo(video: VideoItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No video player application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteVideo(video: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete ${video.name}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(video.uri, null, null)
                    Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
                    refreshGallery()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to delete video", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                Uri.parse("package:$packageName")
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
