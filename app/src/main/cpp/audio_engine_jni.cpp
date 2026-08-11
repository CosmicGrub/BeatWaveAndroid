#include <jni.h>
#include <memory>
#include "AudioEngine.h"

// Single narrow JNI surface between Kotlin (AudioEngineBridge.kt) and the
// native engine, per the implementation plan. Add new native methods here
// as later phases need them (mixing controls in Phase 2, recording in
// Phase 5) rather than opening additional JNI entry points elsewhere.

namespace {
std::unique_ptr<beatwave::AudioEngine> g_engine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_startEngine(JNIEnv *, jobject) {
    if (!g_engine) {
        g_engine = std::make_unique<beatwave::AudioEngine>();
    }
    return g_engine->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_stopEngine(JNIEnv *, jobject) {
    if (g_engine) {
        g_engine->stop();
        g_engine.reset();
    }
}
