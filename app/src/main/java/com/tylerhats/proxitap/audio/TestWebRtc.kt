package com.tylerhats.proxitap.audio

import android.content.Context
import org.webrtc.audio.JavaAudioDeviceModule

fun testADM(context: Context) {
    val builder = JavaAudioDeviceModule.builder(context)
    val methods = builder::class.java.methods
    for (m in methods) {
        println("BUILDER_METHOD: ${m.name}")
    }
}
