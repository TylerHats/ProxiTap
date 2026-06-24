package com.tylerhats.proxitap.audio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.util.Log

class VolumePttAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        Log.d("VolumePtt", "Volume PTT Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used, we only care about key events
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only intercept if the user has it enabled in the app settings
        val prefs = getSharedPreferences("ProxiTapSettings", MODE_PRIVATE)
        val pttEnabled = prefs.getBoolean("volume_ptt", false)

        if (!pttEnabled) {
            return super.onKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val isMuted = event.action == KeyEvent.ACTION_UP
            
            Log.d("VolumePtt", "Intercepted Volume Key. Action: ${event.action}, setting mute=$isMuted")
            
            // Broadcast the mute state to CallService
            val intent = Intent(CallService.ACTION_SET_MUTE).apply {
                putExtra("isMuted", isMuted)
            }
            sendBroadcast(intent)
            
            return true // We consumed the event, preventing the volume UI from showing
        }

        return super.onKeyEvent(event)
    }
}
