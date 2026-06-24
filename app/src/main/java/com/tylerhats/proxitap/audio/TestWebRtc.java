package com.tylerhats.proxitap.audio;

import android.content.Context;
import org.webrtc.audio.JavaAudioDeviceModule;

public class TestWebRtc {
    public static void printMethods() {
        try {
            Class<?> clazz = Class.forName("org.webrtc.audio.JavaAudioDeviceModule$Builder");
            for (java.lang.reflect.Method m : clazz.getMethods()) {
                System.out.println("METHOD_FOUND: " + m.getName() + " -> " + m.getParameterTypes().length + " args");
            }
        } catch (Exception e) {}
    }
}
