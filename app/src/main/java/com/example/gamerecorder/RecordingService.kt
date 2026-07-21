package com.example.gamerecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {
    private var controlManager: FloatingControlManager? = null
    private var muxerPipeline: MuxerPipeline? = null
    private var dndManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "recording_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "Recording Service", NotificationManager.IMPORTANCE_LOW))
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Game Recorder")
            .setContentText("Awaiting match capture")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        
        startForeground(1, notification)
        dndManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        
        if (code != 0 && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(code, data)
            
            muxerPipeline = MuxerPipeline(projection)
            
            controlManager = FloatingControlManager(
                this,
                onStart = {
                    dndManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    muxerPipeline?.start()
                },
                onPauseResume = {
                    if (muxerPipeline?.isPaused == true) muxerPipeline?.resume()
                    else muxerPipeline?.pause()
                },
                onStop = {
                    dndManager?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    muxerPipeline?.stop()
                    stopSelf()
                }
            )
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
