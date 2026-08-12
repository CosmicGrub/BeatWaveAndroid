#include <jni.h>
#include <android/asset_manager_jni.h>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "WavWriter.h"

// Single narrow JNI surface between Kotlin (AudioEngineBridge.kt) and the
// native engine, per the implementation plan. Add new native methods here
// as later phases need them (recording in Phase 5) rather than opening
// additional JNI entry points elsewhere.
//
// Threading contract: the schedule-building calls (nativeInit, beginProject,
// addTrack, addLoopBlock, commitProject, and their nativeTest* offline
// counterparts) do asset I/O, heap allocation, and (addLoopBlock) sample
// decode/resample -- callers MUST invoke these from a background thread,
// never the main/UI thread and never from inside the audio callback. The
// transport controls (play/pause/stop/seekToFrame/getCurrentFrame) and
// getSampleRate are cheap atomic ops and safe to call from any thread.

namespace {

// The live singleton engine backing startEngine/stopEngine and the
// mandate-9 transport/schedule surface.
std::unique_ptr<beatwave::AudioEngine> g_engine;

beatwave::AudioEngine &engine() {
    if (!g_engine) {
        g_engine = std::make_unique<beatwave::AudioEngine>();
    }
    return *g_engine;
}

std::string jstringToStd(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

// Mandate 10: each offline/test engine is its own AudioEngine instance,
// identified to Kotlin by an opaque handle (the instance's raw pointer,
// reinterpreted as a jlong). Entirely separate from g_engine above, but
// built from the exact same AudioEngine/ScoreBuilder/renderScore code.
inline beatwave::AudioEngine *handleToEngine(jlong handle) {
    return reinterpret_cast<beatwave::AudioEngine *>(handle);
}

} // namespace

extern "C" {

// --- Phase 0 (unchanged): real hardware stream lifecycle ---

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_startEngine(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_stopEngine(JNIEnv *, jobject) {
    if (g_engine) {
        g_engine->stop();
        g_engine.reset();
    }
}

// --- Phase 2: schedule building + transport (mandate 9) ---

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeInit(JNIEnv *env, jobject, jobject assetManager) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    engine().init(mgr);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_beginProject(JNIEnv *, jobject, jint bpm) {
    engine().beginProject(bpm);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_addTrack(JNIEnv *, jobject, jint slot) {
    engine().addTrack(slot);
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_addLoopBlock(
        JNIEnv *env, jobject,
        jint trackSlot, jstring sampleAssetPath, jint startGridUnit, jint lengthGridUnits,
        jfloat volume, jlong trimStartMs, jlong trimEndMs, jfloat pitchSemitones) {
    const std::string path = jstringToStd(env, sampleAssetPath);
    const bool ok = engine().addLoopBlock(
            trackSlot, path, startGridUnit, lengthGridUnits, volume, trimStartMs, trimEndMs, pitchSemitones);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_commitProject(JNIEnv *, jobject) {
    engine().commitProject();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_play(JNIEnv *, jobject) {
    engine().play();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_pause(JNIEnv *, jobject) {
    engine().pause();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_stop(JNIEnv *, jobject) {
    engine().stopTransport();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_seekToFrame(JNIEnv *, jobject, jlong frame) {
    engine().seekToFrame(frame);
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_getCurrentFrame(JNIEnv *, jobject) {
    return engine().getCurrentFrame();
}

JNIEXPORT jint JNICALL
Java_com_beatwave_android_AudioEngineBridge_getSampleRate(JNIEnv *, jobject) {
    return engine().getSampleRate();
}

// --- Phase 5: recording (mandates 1-7) ---
// Matches the external fun declarations already landed in
// AudioEngineBridge.kt exactly -- see that file's "Recording" section.

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_startRecording(JNIEnv *, jobject) {
    return engine().startRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_stopRecording(JNIEnv *env, jobject, jstring outputFilePath) {
    const std::string path = jstringToStd(env, outputFilePath);
    return engine().stopRecording(path);
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_isRecording(JNIEnv *, jobject) {
    return engine().isRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_getRecordingStartFrame(JNIEnv *, jobject) {
    return engine().getRecordingStartFrame();
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_getRecordedFrameCount(JNIEnv *, jobject) {
    return engine().getRecordedFrameCount();
}

JNIEXPORT jdouble JNICALL
Java_com_beatwave_android_AudioEngineBridge_getInputLatencyMillis(JNIEnv *, jobject) {
    return engine().getInputLatencyMillis();
}

JNIEXPORT jdouble JNICALL
Java_com_beatwave_android_AudioEngineBridge_getOutputLatencyMillis(JNIEnv *, jobject) {
    return engine().getOutputLatencyMillis();
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_isRecordingCapReached(JNIEnv *, jobject) {
    return engine().isRecordingCapReached() ? JNI_TRUE : JNI_FALSE;
}

// --- Test-only diagnostic natives (mandate 10) ---
// Each operates on its own offline AudioEngine instance (opaque jlong
// handle), entirely separate from the live g_engine singleton above, so a
// test can build a score and advance it without requiring/interfering with
// a real live Oboe output stream.

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestCreateOfflineEngine(
        JNIEnv *env, jobject, jobject assetManager, jint sampleRate) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    auto *offlineEngine = new beatwave::AudioEngine(sampleRate);
    offlineEngine->init(mgr);
    return reinterpret_cast<jlong>(offlineEngine);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestBeginProject(JNIEnv *, jobject, jlong handle, jint bpm) {
    handleToEngine(handle)->beginProject(bpm);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestAddTrack(JNIEnv *, jobject, jlong handle, jint slot) {
    handleToEngine(handle)->addTrack(slot);
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestAddLoopBlock(
        JNIEnv *env, jobject, jlong handle,
        jint trackSlot, jstring sampleAssetPath, jint startGridUnit, jint lengthGridUnits,
        jfloat volume, jlong trimStartMs, jlong trimEndMs, jfloat pitchSemitones) {
    const std::string path = jstringToStd(env, sampleAssetPath);
    const bool ok = handleToEngine(handle)->addLoopBlock(
            trackSlot, path, startGridUnit, lengthGridUnits, volume, trimStartMs, trimEndMs, pitchSemitones);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestCommitProject(JNIEnv *, jobject, jlong handle) {
    handleToEngine(handle)->commitProject();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestAdvanceOffline(JNIEnv *, jobject, jlong handle, jint numFrames) {
    // Scratch buffer for the fixed 2-channel offline render -- the test
    // cares about internal position state, not this audio content. This
    // allocation happens on the *calling* (test/instrumentation) thread,
    // never on an audio callback thread, so it's not subject to mandate 7.
    static thread_local std::vector<float> scratch;
    const size_t needed = static_cast<size_t>(numFrames) * 2;
    if (scratch.size() < needed) {
        scratch.resize(needed);
    }
    handleToEngine(handle)->renderOffline(numFrames, scratch.data());
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestGetBlockStartFrame(
        JNIEnv *, jobject, jlong handle, jint trackSlot, jint blockIndex) {
    return handleToEngine(handle)->testGetBlockStartFrame(trackSlot, blockIndex);
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestGetLoopContentLengthFrames(
        JNIEnv *, jobject, jlong handle, jint trackSlot, jint blockIndex) {
    return handleToEngine(handle)->testGetLoopContentLengthFrames(trackSlot, blockIndex);
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestGetLoopLocalFrame(
        JNIEnv *, jobject, jlong handle, jint trackSlot, jint blockIndex) {
    return handleToEngine(handle)->testGetLoopLocalFrame(trackSlot, blockIndex);
}

// --- Phase 5, mandate 8: offline/test-only recording natives ---
// Same handle-based offline engine as the natives above; lets an
// instrumented test deterministically simulate "start recording at
// simulated transport frame X (not necessarily grid-aligned), advance N
// frames via nativeTestAdvanceOffline, stop recording" and get back a real
// WAV file + the real recordingStartFrame/frame-count the SAME production
// derivation code computed -- no separate implementation to audit, and no
// real human voice required (silence is fed in by AudioEngine::renderOffline
// whenever a test recording is active).
//
// NOTE for the Kotlin-side integrator: AudioEngineBridge.kt does not yet
// declare `external fun` bindings for these five natives -- add them there
// with exactly these names/signatures (see report) before an instrumented
// test can call them.

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestStartRecording(JNIEnv *, jobject, jlong handle) {
    handleToEngine(handle)->testStartRecording();
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestStopRecording(
        JNIEnv *env, jobject, jlong handle, jstring outputFilePath) {
    const std::string path = jstringToStd(env, outputFilePath);
    return handleToEngine(handle)->testStopRecording(path);
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestIsRecording(JNIEnv *, jobject, jlong handle) {
    return handleToEngine(handle)->isRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestGetRecordingStartFrame(JNIEnv *, jobject, jlong handle) {
    return handleToEngine(handle)->getRecordingStartFrame();
}

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestGetRecordedFrameCount(JNIEnv *, jobject, jlong handle) {
    return handleToEngine(handle)->getRecordedFrameCount();
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeTestDestroyOfflineEngine(JNIEnv *, jobject, jlong handle) {
    delete handleToEngine(handle);
}

// --- Phase 7: offline export -- renders a project to a WAV file ---
//
// An "offline export engine" is functionally identical to the nativeTest*
// offline engine above -- both are just a beatwave::AudioEngine constructed
// at a fixed sample rate that never touches real hardware (mandate 10's
// whole point: live and offline modes share the exact same
// ScoreBuilder/renderScore code, see AudioEngine.h's class doc comment).
// These are separate, identically-implemented entry points rather than
// reusing nativeTestCreateOfflineEngine et al. directly, purely so
// production export code (ArrangementViewModel, via PlaybackEngine) never
// calls anything named "Test" -- the two families are free to diverge later
// without one accidentally breaking the other's callers.

JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportCreateEngine(
        JNIEnv *env, jobject, jobject assetManager, jint sampleRate) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    auto *exportEngine = new beatwave::AudioEngine(sampleRate);
    exportEngine->init(mgr);
    return reinterpret_cast<jlong>(exportEngine);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportBeginProject(JNIEnv *, jobject, jlong handle, jint bpm) {
    handleToEngine(handle)->beginProject(bpm);
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportAddTrack(JNIEnv *, jobject, jlong handle, jint slot) {
    handleToEngine(handle)->addTrack(slot);
}

JNIEXPORT jboolean JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportAddLoopBlock(
        JNIEnv *env, jobject, jlong handle,
        jint trackSlot, jstring sampleAssetPath, jint startGridUnit, jint lengthGridUnits,
        jfloat volume, jlong trimStartMs, jlong trimEndMs, jfloat pitchSemitones) {
    const std::string path = jstringToStd(env, sampleAssetPath);
    const bool ok = handleToEngine(handle)->addLoopBlock(
            trackSlot, path, startGridUnit, lengthGridUnits, volume, trimStartMs, trimEndMs, pitchSemitones);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportCommitProject(JNIEnv *, jobject, jlong handle) {
    handleToEngine(handle)->commitProject();
}

/**
 * Renders the full [totalFrames]-frame arrangement already committed on
 * [handle] in one shot (renderOffline has no realtime deadline and no
 * per-call chunk-size assumption -- see AudioEngine::renderOffline -- so
 * unlike the live callback path there is no reason to chunk this), then
 * writes it out via WavWriter exactly like stopRecording()/testStopRecording()
 * already do for a captured recording buffer. Returns totalFrames on
 * success, -1 on failure (degenerate totalFrames, or a WavWriter failure --
 * e.g. an unwritable outputFilePath).
 */
JNIEXPORT jlong JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportRenderToFile(
        JNIEnv *env, jobject, jlong handle, jlong totalFrames, jstring outputFilePath) {
    if (totalFrames <= 0) {
        return -1;
    }
    beatwave::AudioEngine *exportEngine = handleToEngine(handle);
    std::vector<float> buffer(static_cast<size_t>(totalFrames) * 2);
    exportEngine->renderOffline(static_cast<int32_t>(totalFrames), buffer.data());
    const bool ok = beatwave::WavWriter::writeFile(
            jstringToStd(env, outputFilePath),
            buffer.data(),
            totalFrames,
            /*channelCount=*/2,
            exportEngine->getSampleRate());
    return ok ? totalFrames : -1;
}

JNIEXPORT void JNICALL
Java_com_beatwave_android_AudioEngineBridge_nativeExportDestroyEngine(JNIEnv *, jobject, jlong handle) {
    delete handleToEngine(handle);
}

} // extern "C"
