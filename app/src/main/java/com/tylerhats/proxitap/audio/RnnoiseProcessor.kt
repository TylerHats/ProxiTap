package com.tylerhats.proxitap.audio

class RnnoiseProcessor {

    init {
        System.loadLibrary("proxitap")
    }

    external fun initRnnoise()
    external fun destroyRnnoise()
    external fun processAudioFrame(inputFrame: FloatArray): FloatArray

    fun start() {
        initRnnoise()
    }

    fun stop() {
        destroyRnnoise()
    }

    fun process(audioChunk: FloatArray): FloatArray {
        return processAudioFrame(audioChunk)
    }
}
