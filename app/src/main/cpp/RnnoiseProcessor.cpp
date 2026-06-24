#include <jni.h>
#include <string>
#include <vector>

// Forward declarations for RNNoise (we stub these here for the compiler, 
// in a full build they link to the actual RNNoise C library)
extern "C" {
    typedef struct DenoiseState DenoiseState;
    DenoiseState *rnnoise_create(void *model);
    void rnnoise_destroy(DenoiseState *st);
    float rnnoise_process_frame(DenoiseState *st, float *out, const float *in);
}

// Global state for simplicity in this stub
DenoiseState* st = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_tylerhats_proxitap_audio_RnnoiseProcessor_initRnnoise(JNIEnv* env, jobject /* this */) {
    // st = rnnoise_create(nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tylerhats_proxitap_audio_RnnoiseProcessor_destroyRnnoise(JNIEnv* env, jobject /* this */) {
    /*
    if (st != nullptr) {
        rnnoise_destroy(st);
        st = nullptr;
    }
    */
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tylerhats_proxitap_audio_RnnoiseProcessor_processAudioFrame(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray inputFrame) {
    
    jsize len = env->GetArrayLength(inputFrame);
    jfloat *inData = env->GetFloatArrayElements(inputFrame, 0);

    jfloatArray outputFrame = env->NewFloatArray(len);
    jfloat *outData = env->GetFloatArrayElements(outputFrame, 0);

    // In a real RNNoise implementation, we process 480 samples (10ms at 48kHz)
    // float prob = rnnoise_process_frame(st, outData, inData);

    // Stub: just copy input to output for now to prove JNI pipeline works
    for(int i = 0; i < len; i++) {
        outData[i] = inData[i];
    }

    env->ReleaseFloatArrayElements(inputFrame, inData, 0);
    env->ReleaseFloatArrayElements(outputFrame, outData, 0);

    return outputFrame;
}
