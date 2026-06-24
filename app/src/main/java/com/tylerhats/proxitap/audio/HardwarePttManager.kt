package com.tylerhats.proxitap.audio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.util.Log

class HardwarePttManager(
    context: Context,
    private val onPttToggled: (Boolean) -> Unit
) {
    private var mediaSession: MediaSession? = null
    private var isPttMuted = false
    var isHardwarePttEnabled = false

    init {
        mediaSession = MediaSession(context, "ProxiTap_PTT")
        
        // We set the state to playing so we can intercept hardware media buttons
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(state)

        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                if (!isHardwarePttEnabled) return super.onMediaButtonEvent(mediaButtonIntent)

                val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            Log.d("HardwarePtt", "PTT Button Pressed!")
                            isPttMuted = !isPttMuted
                            onPttToggled(isPttMuted)
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
    }

    fun start() {
        if (isHardwarePttEnabled) {
            mediaSession?.isActive = true
        }
    }

    fun stop() {
        mediaSession?.isActive = false
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
    }
}
