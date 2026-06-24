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
import java.nio.ByteBuffer

class MediaStreamer(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isStreaming = false
    
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN_MEDIA = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_IN_MIC = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val CHANNEL_CONFIG_OUT_MONO = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    private val bufferSizeMedia = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN_MEDIA, AUDIO_FORMAT) * 2
    private val bufferSizeMic = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN_MIC, AUDIO_FORMAT) * 2

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

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeMedia)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        isStreaming = true
        audioRecord?.startRecording()

        scope.launch {
            val buffer = ByteArray(bufferSizeMedia)
            while (isActive && isStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    onAudioDataReceived(data)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicrophoneCapture(onAudioDataReceived: (ByteArray) -> Unit) {
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG_IN_MIC)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeMic)
            .build()

        isStreaming = true
        audioRecord?.startRecording()

        scope.launch {
            val buffer = ByteArray(bufferSizeMic)
            while (isActive && isStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    onAudioDataReceived(data)
                }
            }
        }
    }

    fun startPlayback(isMono: Boolean = false) {
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(if (isMono) CHANNEL_CONFIG_OUT_MONO else CHANNEL_CONFIG_OUT)
            .build()

        val attributes = AudioAttributes.Builder()
            .setUsage(if (isMono) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (isMono) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
            
        val bufferSizeOut = if (isMono) bufferSizeMic else bufferSizeMedia

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeOut)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun playAudioData(data: ByteArray) {
        audioTrack?.write(data, 0, data.size)
    }

    fun stop() {
        isStreaming = false
        scope.coroutineContext.cancelChildren()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        mediaProjection?.stop()
        mediaProjection = null
    }
}
