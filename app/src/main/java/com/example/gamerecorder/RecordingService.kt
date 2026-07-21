package com.example.gamerecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {
    private var controlManager: FloatingControlManager? = null
    private var muxerPipeline: MuxerPipeline? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "recording_channel"
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager?.createNotificationChannel(
            NotificationChannel(channelId, "Recording Service", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Game Recorder")
            .setContentText("Awaiting match capture")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        val customOutputPath = intent?.getStringExtra("output_path")

        if (code != 0 && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(code, data)

            if (projection != null) {
                muxerPipeline = MuxerPipeline(projection, customOutputPath)

                controlManager = FloatingControlManager(
                    this,
                    onStart = {
                        try {
                            if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                                notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                            }
                        } catch (_: Exception) {}
                        muxerPipeline?.start()
                    },
                    onPauseResume = {
                        if (muxerPipeline?.isPaused == true) {
                            muxerPipeline?.resume()
                            false // Recording active -> show Pause icon (⏸)
                        } else {
                            muxerPipeline?.pause()
                            true // Recording paused -> show Play icon (▶)
                        }
                    },
                    onStop = {
                        try {
                            if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                                notificationManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                            }
                        } catch (_: Exception) {}
                        muxerPipeline?.stop()
                        stopSelf()
                    }
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controlManager?.destroy()
        muxerPipeline?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
