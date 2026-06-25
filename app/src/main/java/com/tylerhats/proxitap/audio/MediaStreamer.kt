package com.tylerhats.proxitap.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

class MediaStreamer(private val context: Context) {

    private val prefs = context.getSharedPreferences("ProxiTapSettings", Context.MODE_PRIVATE)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var playbackChannel: kotlinx.coroutines.channels.Channel<ByteArray>? = null
    private var playbackJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isStreaming = false
    
    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG_IN_MEDIA = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_IN_MIC = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val CHANNEL_CONFIG_OUT_MONO = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun startCapture(projection: MediaProjection, onAudioDataReceived: (ByteArray) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e("MediaStreamer", "AudioPlaybackCapture requires Android 10+")
            return
        }
        
        mediaProjection = projection
        
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
            
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN_MEDIA, AUDIO_FORMAT)

        val recordBuilder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize)
            .setAudioPlaybackCaptureConfig(config)

        val record = recordBuilder.build()
        audioRecord = record
        isStreaming = true
        record.startRecording()

        scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            // Send in smaller chunks (20ms frames) for low latency
            val frameSize = (SAMPLE_RATE * 2 * 2 * 0.02).toInt() // 20ms Stereo PCM 16-bit
            val buffer = ByteArray(frameSize)
            while (isActive && isStreaming) {
                val read = record.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    onAudioDataReceived(data)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicrophoneCapture(sampleRate: Int, onAudioDataReceived: (ByteArray) -> Unit) {
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_CONFIG_IN_MIC)
            .build()
            
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG_IN_MIC, AUDIO_FORMAT)

        val recordBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize)

        val record = recordBuilder.build()
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
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            // Send in smaller chunks (20ms frames) for low latency
            val frameSize = (sampleRate * 1 * 2 * 0.02).toInt() // 20ms Mono PCM 16-bit
            val buffer = ByteArray(frameSize)
            val dtxEnabled = prefs.getBoolean("opus_dtx", true)
            var hangoverFrames = 0
            val maxHangover = 30 // 30 frames * 20ms = 600ms hangover window
            
            while (isActive && isStreaming) {
                val read = record.read(buffer, 0, buffer.size) ?: 0
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
                // Correct little-endian 16-bit PCM parsing with sign extension prevention
                val sample = (((data[i+1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)).toShort()
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum / numSamples)
        return rms < threshold
    }

    fun startPlayback(isMono: Boolean = false, sampleRate: Int = 48000) {
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (isMono) CHANNEL_CONFIG_OUT_MONO else CHANNEL_CONFIG_OUT)
            .build()

        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(if (isMono) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_GAME)
            .setContentType(if (isMono) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_SONIFICATION)
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        val attributes = attributesBuilder.build()
            
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, if (isMono) CHANNEL_CONFIG_OUT_MONO else CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        
        val track = trackBuilder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val frameSizeInBytes = (if (isMono) 1 else 2) * 2
            val chunkInFrames = (sampleRate * 0.02).toInt()
            val targetBufferSizeInFrames = chunkInFrames * 2
            try {
                val actualBufferSize = track.setBufferSizeInFrames(targetBufferSizeInFrames)
                Log.d("MediaStreamer", "Requested buffer size: $targetBufferSizeInFrames frames, set to: $actualBufferSize frames")
            } catch (e: Exception) {
                Log.e("MediaStreamer", "Failed to set buffer size in frames", e)
            }
        }
        audioTrack = track
        
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(
            capacity = 2,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        playbackChannel = channel
        
        playbackJob = scope.launch(Dispatchers.Default) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                for (data in channel) {
                    val activeTrack = audioTrack ?: break
                    activeTrack.write(data, 0, data.size)
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
