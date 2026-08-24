#include "audio_output.h"
#include "dsp_engine.h"

#include <cmath>
#include <cstdio>
#include <jni.h>
#include <new>

#define JNI_METHOD(name) Java_com_moozik_player_audio_Dsp_##name

using namespace moozik;

static AudioOutput* g_output = nullptr;

static jlong ptrToHandle(DspEngine* e) { return reinterpret_cast<jlong>(e); }
static DspEngine* handleToPtr(jlong h) { return reinterpret_cast<DspEngine*>(h); }

extern "C" {

JNIEXPORT jstring JNI_METHOD(version)(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("moozik-dsp 0.1.0");
}

JNIEXPORT jfloatArray JNI_METHOD(peakingCoefficients)(
        JNIEnv* env, jobject /*thiz*/,
        jdouble sampleRate, jdouble freq, jdouble q, jdouble gainDb) {
    Biquad f{};
    designPeaking(sampleRate, freq, q, gainDb, f);

    jfloat coeffs[5] = {
        static_cast<jfloat>(f.b0),
        static_cast<jfloat>(f.b1),
        static_cast<jfloat>(f.b2),
        static_cast<jfloat>(f.a1),
        static_cast<jfloat>(f.a2),
    };

    auto result = env->NewFloatArray(5);
    env->SetFloatArrayRegion(result, 0, 5, coeffs);
    return result;
}

JNIEXPORT jboolean JNI_METHOD(selfCheck)(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jdouble sampleRate, jdouble freq, jdouble q, jdouble gainDb) {
    Biquad f{};
    designPeaking(sampleRate, freq, q, gainDb, f);

    const double targetGain = std::pow(10.0, gainDb / 20.0);
    constexpr double eps = 1e-4;

    const double dc = magnitudeAt(f, sampleRate, 1e-6);
    const double nyq = magnitudeAt(f, sampleRate, sampleRate / 2.0 - 1e-3);
    const double center = magnitudeAt(f, sampleRate, freq);

    return (std::abs(dc - 1.0) < eps)
        && (std::abs(nyq - 1.0) < eps)
        && (std::abs(center - targetGain) < eps);
}

JNIEXPORT jlong JNI_METHOD(createEngine)(JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate) {
    auto* engine = new (std::nothrow) DspEngine(sampleRate);
    return ptrToHandle(engine);
}

JNIEXPORT void JNI_METHOD(destroyEngine)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete handleToPtr(handle);
}

JNIEXPORT void JNI_METHOD(setPreamp)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat gainDb) {
    if (auto* e = handleToPtr(handle)) e->setPreampDb(gainDb);
}

JNIEXPORT void JNI_METHOD(setBand)(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
        jint index, jint type, jdouble freq, jdouble q, jdouble gainDb, jboolean enabled) {
    if (auto* e = handleToPtr(handle)) {
        e->setBand(index, static_cast<FilterType>(type), freq, q, gainDb, enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNI_METHOD(reset)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto* e = handleToPtr(handle)) e->reset();
}

JNIEXPORT void JNI_METHOD(process)(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jfloatArray interleaved, jint frames) {
    auto* e = handleToPtr(handle);
    if (!e || !interleaved || frames <= 0) return;

    jsize length = env->GetArrayLength(interleaved);
    if (frames * 2 > length) return;

    // Real-time friendly critical-section access; no copy in/out on most VMs.
    auto* data = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
    if (data) {
        e->processInterleaved(data, frames);
        env->ReleasePrimitiveArrayCritical(interleaved, data, 0);
    }
}

// ---- output backend (single instance) ----

JNIEXPORT jboolean JNI_METHOD(openOutput)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint sampleRate) {
    auto* engine = handleToPtr(handle);
    if (!engine || sampleRate <= 0) return JNI_FALSE;

    delete g_output;
    g_output = new AudioOutput();
    return g_output->open(engine, sampleRate) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNI_METHOD(closeOutput)(JNIEnv* /*env*/, jobject /*thiz*/) {
    delete g_output;
    g_output = nullptr;
}

JNIEXPORT void JNI_METHOD(setOutputPaused)(JNIEnv* /*env*/, jobject /*thiz*/, jboolean paused) {
    if (g_output) g_output->setPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNI_METHOD(drainOutput)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_output) g_output->clearRing();
}

JNIEXPORT void JNI_METHOD(writeOutput)(
        JNIEnv* env, jobject /*thiz*/, jfloatArray interleaved, jint frames) {
    if (!g_output || !interleaved || frames <= 0) return;

    jsize length = env->GetArrayLength(interleaved);
    if (frames * 2 > length) return;

    // Get<Float>ArrayElements (not PrimitiveArrayCritical): the write below
    // may block on the ring buffer, and blocking inside a critical section
    // stalls the GC. Elements-pinning is safe for the duration.
    jfloat* data = env->GetFloatArrayElements(interleaved, nullptr);
    if (data) {
        g_output->write(data, static_cast<size_t>(frames) * 2);
        env->ReleaseFloatArrayElements(interleaved, data, JNI_ABORT);
    }
}

JNIEXPORT jstring JNI_METHOD(outputInfo)(JNIEnv* env, jobject /*thiz*/) {
    if (!g_output || !g_output->isOpen()) {
        return env->NewStringUTF("");
    }
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%s · %d Hz%s",
                  g_output->modeText(), g_output->actualSampleRate(),
                  g_output->isNativeRate() ? "" : " · resampled");
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNI_METHOD(setExclusiveEnabled)(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    AudioOutput::setExclusiveAllowed(enabled == JNI_TRUE);
}

} // extern "C"
