package com.tylerhats.proxitap.audio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent

class HardwarePttManager(
    context: Context,
    private val onToggleMute: () -> Unit
) {
    private var mediaSession: MediaSession? = null
    var isHardwarePttEnabled: Boolean = false
        set(value) {
            field = value
            mediaSession?.isActive = value
        }

    init {
        mediaSession = MediaSession(context, "ProxiTap_PTT")

        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE
            )
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(state)

        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                if (!isHardwarePttEnabled) return super.onMediaButtonEvent(mediaButtonIntent)

                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }

                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            Log.d("HardwarePtt", "Headset/Earbud media button pressed — toggling mute")
                            onToggleMute()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
    }

    fun start() {
        mediaSession?.isActive = isHardwarePttEnabled
    }

    fun stop() {
        mediaSession?.isActive = false
    }

    fun release() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {}
        mediaSession = null
    }
}
