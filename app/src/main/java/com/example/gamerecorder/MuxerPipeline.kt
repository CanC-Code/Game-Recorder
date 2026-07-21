package com.example.gamerecorder

import android.content.ContentValues
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MuxerPipeline(
    private val mediaProjection: MediaProjection,
    private val customOutputPath: String? = null
) {
    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isMuxerStarted = false
    private var isRunning = false
    var isPaused = false
        private set

    companion object {
        private const val TAG = "MuxerPipeline"
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private const val BIT_RATE = 5_000_000 // 5 Mbps VBR
        private const val FRAME_RATE = 60
        private const val I_FRAME_INTERVAL = 1
    }

    fun start() {
        if (isRunning) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = createInputSurface()
                
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "GameRecorderDisplay",
                    WIDTH, HEIGHT, 1,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null
                )

                mediaMuxer = createMediaMuxer()
                start()
            }

            isRunning = true
            isPaused = false
            startEncodingLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MuxerPipeline", e)
            stop()
        }
    }

    private fun createMediaMuxer(): MediaMuxer {
        val fileName = "PokemonUnite_${System.currentTimeMillis()}.mp4"

        if (!customOutputPath.isNullOrEmpty()) {
            val customDir = File(customOutputPath)
            if (!customDir.exists()) {
                customDir.mkdirs()
            }
            val file = File(customDir, fileName)
            return MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        // Default to MediaStore Movies directory if no custom path provided
        val resolver = App.context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_DIR, Environment.DIRECTORY_MOVIES + "/GameRecorder")
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Failed to open file descriptor for MediaStore entry")

        return MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun startEncodingLoop() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val encoderRef = encoder ?: break
                    val outputIndex = encoderRef.dequeueOutputBuffer(bufferInfo, 10000)

                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = mediaMuxer?.addTrack(encoderRef.outputFormat) ?: -1
                            mediaMuxer?.start()
                            isMuxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encodedData = encoderRef.getOutputBuffer(outputIndex)
                            if (encodedData != null && isMuxerStarted) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                                }
                            }
                            encoderRef.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in encoding loop", e)
                    break
                }
            }
        }.start()
    }

    fun pause() {
        isPaused = true
        // Additional pause signal handling if surface routing drops frames
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            encoder?.stop()
            encoder?.release()
            encoder = null

            if (isMuxerStarted) {
                mediaMuxer?.stop()
                mediaMuxer?.release()
                mediaMuxer = null
                isMuxerStarted = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MuxerPipeline", e)
        }
    }
}
