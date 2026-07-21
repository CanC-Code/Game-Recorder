package com.example.gamerecorder

import android.content.ContentValues
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File

class MuxerPipeline(
    private val mediaProjection: MediaProjection,
    private val customOutputPath: String? = null
) {
    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaMuxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null

    private var trackIndex = -1
    private var isMuxerStarted = false

    @Volatile private var isRunning = false
    private var workerThread: Thread? = null

    var isPaused = false
        private set

    private var totalPauseTimeUs = 0L
    private var pauseStartTimeUs = 0L

    companion object {
        private const val TAG = "MuxerPipeline"
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 60
        private const val I_FRAME_INTERVAL = 1
    }

    fun start() {
        if (isRunning) return
        try {
            val metrics = App.context.resources.displayMetrics
            var width = metrics.widthPixels
            var height = metrics.heightPixels

            if (width <= 0 || height <= 0) {
                width = 1080
                height = 1920
            }

            val maxDim = 1920
            if (width > maxDim || height > maxDim) {
                if (width > height) {
                    height = (height * maxDim) / width
                    width = maxDim
                } else {
                    width = (width * maxDim) / height
                    height = maxDim
                }
            }

            if (width % 2 != 0) width--
            if (height % 2 != 0) height--

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = createInputSurface()

                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "GameRecorderDisplay",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null
                )

                mediaMuxer = createMediaMuxer()
                start()
            }

            isRunning = true
            isPaused = false
            totalPauseTimeUs = 0L
            pauseStartTimeUs = 0L

            startEncodingLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MuxerPipeline", e)
            finalizeAndRelease()
        }
    }

    private fun createMediaMuxer(): MediaMuxer {
        val fileName = "GameRecorder_${System.currentTimeMillis()}.mp4"

        if (!customOutputPath.isNullOrEmpty()) {
            try {
                val customDir = File(customOutputPath)
                if (!customDir.exists()) {
                    customDir.mkdirs()
                }
                val file = File(customDir, fileName)
                return MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                Log.w(TAG, "Custom output path failed, falling back to MediaStore", e)
            }
        }

        val resolver = App.context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GameRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        currentUri = uri
        pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Failed to open ParcelFileDescriptor")

        return MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun startEncodingLoop() {
        workerThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isRunning) {
                    val encoderRef = encoder ?: break
                    val outputIndex = try {
                        encoderRef.dequeueOutputBuffer(bufferInfo, 10000)
                    } catch (e: Exception) {
                        break
                    }

                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!isMuxerStarted) {
                                trackIndex = mediaMuxer?.addTrack(encoderRef.outputFormat) ?: -1
                                mediaMuxer?.start()
                                isMuxerStarted = true
                            }
                        }
                        outputIndex >= 0 -> {
                            val encodedData = encoderRef.getOutputBuffer(outputIndex)
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (encodedData != null && isMuxerStarted && bufferInfo.size > 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    if (isPaused) {
                                        if (pauseStartTimeUs == 0L) {
                                            pauseStartTimeUs = bufferInfo.presentationTimeUs
                                        }
                                    } else {
                                        if (pauseStartTimeUs != 0L) {
                                            totalPauseTimeUs += (bufferInfo.presentationTimeUs - pauseStartTimeUs)
                                            pauseStartTimeUs = 0L
                                        }
                                        bufferInfo.presentationTimeUs -= totalPauseTimeUs
                                        if (bufferInfo.presentationTimeUs < 0) bufferInfo.presentationTimeUs = 0
                                        mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }
                            encoderRef.releaseOutputBuffer(outputIndex, false)

                            if (isEos) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during encoding loop", e)
            } finally {
                finalizeAndRelease()
            }
        }.apply { start() }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            encoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to signal end of input stream", e)
        }

        try {
            workerThread?.join(1500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for encoding thread", e)
        }

        try {
            mediaProjection.stop()
        } catch (_: Exception) {}
    }

    private fun finalizeAndRelease() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping encoder", e)
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing encoder", e)
            }
            encoder = null

            if (isMuxerStarted) {
                try {
                    mediaMuxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping mediaMuxer", e)
                }
                try {
                    mediaMuxer?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing mediaMuxer", e)
                }
                mediaMuxer = null
                isMuxerStarted = false
            }

            pfd?.close()
            pfd = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                App.context.contentResolver.update(currentUri!!, values, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during finalizeAndRelease", e)
        }
    }
}
