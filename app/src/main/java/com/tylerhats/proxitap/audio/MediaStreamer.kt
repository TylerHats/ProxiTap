package com.tylerhats.proxitap.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.min

class MediaStreamer(private val context: Context) {

    private val prefs = context.getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isMicrophoneCapture = false
    private var preferredInputDevice: AudioDeviceInfo? = null
    private var preferredOutputDevice: AudioDeviceInfo? = null
    private var playbackChannel: kotlinx.coroutines.channels.Channel<ByteArray>? = null
    private var playbackJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isStreaming = false
    
    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG_IN_MEDIA = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_IN_MIC = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val CHANNEL_CONFIG_OUT_MONO = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun setPreferredDevices(inputDevice: AudioDeviceInfo?, outputDevice: AudioDeviceInfo?) {
        preferredInputDevice = inputDevice
        preferredOutputDevice = outputDevice
        try {
            if (isMicrophoneCapture) {
                audioRecord?.setPreferredDevice(inputDevice)
            }
        } catch (e: Exception) {
            Log.e("MediaStreamer", "Failed to set preferred input device on AudioRecord", e)
        }
        try {
            audioTrack?.setPreferredDevice(outputDevice)
        } catch (e: Exception) {
            Log.e("MediaStreamer", "Failed to set preferred output device on AudioTrack", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(projection: MediaProjection, onAudioDataReceived: (ByteArray) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e("MediaStreamer", "AudioPlaybackCapture requires Android 10+")
            return
        }
        
        mediaProjection = projection
        isMicrophoneCapture = false
        
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
            
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG_IN_MEDIA)
            .build()
            
        // 5ms Stereo 16-bit PCM @ 48kHz = 960 bytes (fits in a single unfragmented UDP packet!)
        val frameSize = (SAMPLE_RATE * 2 * 2 * 0.005).toInt()
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN_MEDIA, AUDIO_FORMAT)
        val recordBufSize = maxOf(minBufSize * 2, frameSize * 8)

        val recordBuilder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(recordBufSize)
            .setAudioPlaybackCaptureConfig(config)

        val record = recordBuilder.build()
        audioRecord = record
        isStreaming = true
        record.startRecording()

        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(frameSize)
            while (isActive && isStreaming) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    onAudioDataReceived(data)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicrophoneCapture(sampleRate: Int, onAudioDataReceived: (ByteArray) -> Unit) {
        isMicrophoneCapture = true
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_CONFIG_IN_MIC)
            .build()
            
        val frameSize = (sampleRate * 1 * 2 * 0.02).toInt() // 20ms Mono PCM 16-bit
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG_IN_MIC, AUDIO_FORMAT)
        val recordBufSize = maxOf(minBufSize * 2, frameSize * 4)

        val recordBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(recordBufSize)

        val record = recordBuilder.build()
        try {
            preferredInputDevice?.let { record.setPreferredDevice(it) }
        } catch (e: Exception) {
            Log.e("MediaStreamer", "Error setting initial preferred input device", e)
        }
        audioRecord = record

        // Apply NS and AEC if enabled in settings
        val nsEnabled = prefs.getBoolean("ns_enabled", true)
        val aecEnabled = prefs.getBoolean("aec_enabled", true)
        var ns: android.media.audiofx.NoiseSuppressor? = null
        var aec: android.media.audiofx.AcousticEchoCanceler? = null

        if (nsEnabled && android.media.audiofx.NoiseSuppressor.isAvailable()) {
            try {
                ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
                ns?.enabled = true
            } catch (e: Exception) { Log.e("MediaStreamer", "Failed to enable NoiseSuppressor", e) }
        }
        if (aecEnabled && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            try {
                aec = android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)
                aec?.enabled = true
            } catch (e: Exception) { Log.e("MediaStreamer", "Failed to enable AcousticEchoCanceler", e) }
        }

        isStreaming = true
        record.startRecording()

        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(frameSize)
            val dtxEnabled = prefs.getBoolean("opus_dtx", true)
            var hangoverFrames = 0
            val maxHangover = 30 // 30 frames * 20ms = 600ms hangover window
            
            while (isActive && isStreaming) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    if (dtxEnabled) {
                        val thresh = prefs.getFloat("dtx_threshold", 50f)
                        val silent = isSilence(data, thresh)
                        if (silent) {
                            if (hangoverFrames > 0) {
                                hangoverFrames--
                                onAudioDataReceived(data)
                            } else {
                                continue
                            }
                        } else {
                            hangoverFrames = maxHangover
                            onAudioDataReceived(data)
                        }
                    } else {
                        onAudioDataReceived(data)
                    }
                }
            }
            
            try { ns?.release() } catch (e: Exception) {}
            try { aec?.release() } catch (e: Exception) {}
        }
    }

    private fun isSilence(data: ByteArray, threshold: Float): Boolean {
        var sum = 0.0
        val numSamples = data.size / 2
        if (numSamples == 0) return true
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) {
                val sample = (((data[i+1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)).toShort()
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum / numSamples)
        return rms < threshold
    }

    /**
     * Smoothly crossfades two PCM 16-bit little-endian chunks into a single chunk
     * so excess buffer latency can be shaved off with zero waveform discontinuity (no clicks/pops).
     */
    private fun crossfadeChunks(chunkA: ByteArray, chunkB: ByteArray, isMono: Boolean): ByteArray {
        if (chunkA.size != chunkB.size || chunkA.isEmpty()) return chunkB
        val out = ByteArray(chunkA.size)
        val channels = if (isMono) 1 else 2
        val bytesPerFrame = channels * 2
        val totalFrames = chunkA.size / bytesPerFrame
        if (totalFrames <= 1) return chunkB

        for (frame in 0 until totalFrames) {
            val weightB = frame.toFloat() / (totalFrames - 1).toFloat()
            val weightA = 1f - weightB
            for (ch in 0 until channels) {
                val idx = (frame * channels + ch) * 2
                val sampleA = (((chunkA[idx + 1].toInt() and 0xFF) shl 8) or (chunkA[idx].toInt() and 0xFF)).toShort()
                val sampleB = (((chunkB[idx + 1].toInt() and 0xFF) shl 8) or (chunkB[idx].toInt() and 0xFF)).toShort()
                val mixed = (sampleA * weightA + sampleB * weightB).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out[idx] = (mixed.toInt() and 0xFF).toByte()
                out[idx + 1] = ((mixed.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    fun startPlayback(isMono: Boolean = false, sampleRate: Int = 48000) {
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (isMono) CHANNEL_CONFIG_OUT_MONO else CHANNEL_CONFIG_OUT)
            .build()

        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(if (isMono) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (isMono) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        val attributes = attributesBuilder.build()
            
        val bytesPerFrame = (if (isMono) 1 else 2) * 2
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, if (isMono) CHANNEL_CONFIG_OUT_MONO else CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
        // Allocate ~150ms hardware ring buffer capacity so Wi-Fi Aware DW bursts never overflow or block,
        // while keeping actual playout cushion tightly regulated at ~25-45ms in the write loop below.
        val targetRingBufferBytes = maxOf(minBufSize * 2, (sampleRate * 0.15 * bytesPerFrame).toInt())

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(targetRingBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        
        val track = trackBuilder.build()
        try {
            preferredOutputDevice?.let { track.setPreferredDevice(it) }
        } catch (e: Exception) {
            Log.e("MediaStreamer", "Error setting initial preferred output device", e)
        }
        audioTrack = track
        
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(
            capacity = 32,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        playbackChannel = channel
        
        playbackJob = scope.launch(Dispatchers.Default) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var totalFramesWritten = 0L
            var lastWriteTimestamp = 0L
            // 25ms pre-roll cushion prevents underruns across Wi-Fi Aware 16ms discovery windows
            val preRollFrames = (sampleRate * 0.025).toInt()
            val preRollSilence = ByteArray(preRollFrames * bytesPerFrame)
            val maxTargetLatencyMs = if (isMono) 75L else 60L
            val hardResetLatencyMs = if (isMono) 140L else 110L

            try {
                for (firstData in channel) {
                    val activeTrack = audioTrack ?: break
                    val now = System.currentTimeMillis()
                    val headPos = activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    var bufferedFrames = (totalFramesWritten - headPos).coerceAtLeast(0L)
                    val bufferedMs = (bufferedFrames * 1000L) / sampleRate

                    // If buffer drained completely after an idle gap (>65ms), inject a 25ms silent pre-roll
                    // so the very first Wi-Fi jitter window doesn't cause an AudioTrack underrun glitch.
                    if (bufferedFrames == 0L && (now - lastWriteTimestamp) > 65L) {
                        val preWritten = activeTrack.write(preRollSilence, 0, preRollSilence.size)
                        if (preWritten > 0) {
                            totalFramesWritten += preWritten / bytesPerFrame
                            bufferedFrames += preWritten / bytesPerFrame
                        }
                    }

                    var frameToWrite = firstData

                    // If a large stall caused a burst of frames to pile up, drain old queued frames
                    // and crossfade into the newest frame so latency snaps back cleanly without a pop.
                    if (bufferedMs > hardResetLatencyMs) {
                        var latest = channel.tryReceive().getOrNull()
                        while (latest != null) {
                            val next = channel.tryReceive().getOrNull()
                            if (next == null) {
                                frameToWrite = crossfadeChunks(frameToWrite, latest, isMono)
                                break
                            }
                            latest = next
                        }
                    } else if (bufferedMs > maxTargetLatencyMs) {
                        // Gentle catch-up: crossfade two consecutive chunks into one (shaves 5ms with zero click)
                        val nextChunk = channel.tryReceive().getOrNull()
                        if (nextChunk != null) {
                            frameToWrite = crossfadeChunks(frameToWrite, nextChunk, isMono)
                        }
                    }

                    val written = activeTrack.write(frameToWrite, 0, frameToWrite.size)
                    if (written > 0) {
                        totalFramesWritten += written / bytesPerFrame
                        lastWriteTimestamp = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaStreamer", "Error in playback loop", e)
            }
        }
        
        track.play()
    }

    fun playAudioData(data: ByteArray) {
        playbackChannel?.trySend(data)
    }

    fun stop() {
        isStreaming = false
        try { playbackChannel?.close() } catch (e: Exception) {}
        playbackChannel = null
        try { playbackJob?.cancel() } catch (e: Exception) {}
        playbackJob = null
        
        try { scope.cancel() } catch (e: Exception) {}
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) { Log.e("MediaStreamer", "Error stopping audioRecord", e) }
        try {
            audioRecord?.release()
        } catch (e: Exception) { Log.e("MediaStreamer", "Error releasing audioRecord", e) }
        audioRecord = null
        
        try {
            audioTrack?.stop()
        } catch (e: Exception) { Log.e("MediaStreamer", "Error stopping audioTrack", e) }
        try {
            audioTrack?.release()
        } catch (e: Exception) { Log.e("MediaStreamer", "Error releasing audioTrack", e) }
        audioTrack = null
        
        try {
            mediaProjection?.stop()
        } catch (e: Exception) { Log.e("MediaStreamer", "Error stopping mediaProjection", e) }
        mediaProjection = null
    }
}
