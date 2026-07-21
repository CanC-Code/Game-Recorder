package com.example.gamerecorder

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MuxerPipeline(
    private val projection: MediaProjection,
    private val width: Int = 1920,
    private val height: Int = 1080
) {
    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    @Volatile var isRecording = false
    @Volatile var isPaused = false

    private var pauseOffsetUs: Long = 0
    private var lastPauseStartUs: Long = 0

    @SuppressLint("MissingPermission")
    fun start() {
        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "UniteCapture_${System.currentTimeMillis()}.mp4"
        )
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Setup Video (H.265 / HEVC @ 5 Mbps)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000) 
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        videoCodec?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoCodec?.createInputSurface()
        
        projection.createVirtualDisplay(
            "GameRecorder", width, height, 400,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
        videoCodec?.start()

        // Setup Internal Audio (AudioPlaybackCapture)
        val sampleRate = 48000
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192000)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec?.start()

        val audioConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
            
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build())
            .setAudioPlaybackCaptureConfig(audioConfig)
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()
            
        audioRecord?.startRecording()
        isRecording = true

        startVideoThread()
        startAudioThread()
    }

    private fun startVideoThread() = thread {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            val index = videoCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackIndex = muxer!!.addTrack(videoCodec!!.outputFormat)
                checkStartMuxer()
            } else if (index >= 0) {
                val encodedData = videoCodec?.getOutputBuffer(index)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                if (bufferInfo.size != 0 && isMuxerStarted && !isPaused) {
                    bufferInfo.presentationTimeUs -= pauseOffsetUs
                    muxer?.writeSampleData(videoTrackIndex, encodedData!!, bufferInfo)
                }
                videoCodec?.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun startAudioThread() = thread {
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ByteBuffer.allocateDirect(4096)
        while (isRecording) {
            if (isPaused) {
                Thread.sleep(10)
                continue
            }
            val read = audioRecord?.read(pcmBuffer, 4096) ?: 0
            if (read > 0) {
                val inIndex = audioCodec?.dequeueInputBuffer(10000) ?: -1
                if (inIndex >= 0) {
                    val inBuffer = audioCodec?.getInputBuffer(inIndex)
                    inBuffer?.clear()
                    inBuffer?.put(pcmBuffer)
                    audioCodec?.queueInputBuffer(inIndex, 0, read, System.nanoTime() / 1000 - pauseOffsetUs, 0)
                }
            }
            val outIndex = audioCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackIndex = muxer!!.addTrack(audioCodec!!.outputFormat)
                checkStartMuxer()
            } else if (outIndex >= 0) {
                val encodedData = audioCodec?.getOutputBuffer(outIndex)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                if (bufferInfo.size != 0 && isMuxerStarted) {
                    muxer?.writeSampleData(audioTrackIndex, encodedData!!, bufferInfo)
                }
                audioCodec?.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private fun checkStartMuxer() {
        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !isMuxerStarted) {
            muxer?.start()
            isMuxerStarted = true
        }
    }

    fun pause() {
        if (!isPaused) {
            lastPauseStartUs = System.nanoTime() / 1000
            isPaused = true
        }
    }

    fun resume() {
        if (isPaused) {
            pauseOffsetUs += (System.nanoTime() / 1000) - lastPauseStartUs
            isPaused = false
        }
    }

    fun stop() {
        isRecording = false
        Thread.sleep(200) 
        audioRecord?.stop()
        audioRecord?.release()
        videoCodec?.stop()
        videoCodec?.release()
        audioCodec?.stop()
        audioCodec?.release()
        if (isMuxerStarted) {
            muxer?.stop()
            muxer?.release()
        }
        projection.stop()
    }
}
