package com.beatwave.android.audio

import android.content.Context
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleSource

/**
 * Bridges a Phase 1 [Project] into the native audio engine
 * ([AudioEngineBridge]): maps each [com.beatwave.android.data.model.LoopBlock]
 * onto its bundled [Sample]'s asset path and drives the
 * beginProject/addTrack/addLoopBlock/commitProject sequence, then exposes
 * simple transport controls.
 *
 * THREADING CONTRACT: [nativeInit] and [loadProject] call into
 * [AudioEngineBridge] schedule-building natives, which do asset I/O and
 * sample decode/resample -- both MUST be called from a background thread,
 * never the main/UI thread (see AudioEngineBridge's class doc comment).
 * The transport methods (play/pause/stop/seekToFrame/getCurrentFrame/
 * getSampleRate) are cheap and safe from any thread.
 */
class ProjectPlaybackController(private val context: Context) {

    /**
     * Gives the native engine an AssetManager to decode bundled/imported
     * WAV assets through. Must be called once, from a background thread,
     * before [loadProject] -- and needs a real [Context] to obtain the
     * AssetManager, which is why this isn't done in AudioEngineBridge's own
     * init{} block.
     */
    fun nativeInit() {
        AudioEngineBridge.nativeInit(context.assets)
    }

    /**
     * Builds and atomically publishes a new playback score for [project],
     * resolving each loop block's [com.beatwave.android.data.model.LoopBlock.sampleId]
     * to a sample path via [samples] (typically the id->Sample map merged
     * from com.beatwave.android.data.library.AssetLoopLibrary and, since
     * Phase 4, com.beatwave.android.data.library.ImportedSampleIndex).
     *
     * The path passed to [AudioEngineBridge.addLoopBlock] is the sample's
     * [SampleSource.BundledAsset.assetPath] (AAssetManager-relative, never
     * starts with '/') or [SampleSource.ImportedFile.uri] (an absolute
     * filesystem path under app-private storage, always starts with '/' on
     * Android -- despite the "uri" field name, Phase 4 stores an absolute
     * filesystem path there, not a content URI, per its own design). Either
     * way SampleBank::getOrLoad on the native side dispatches purely by
     * checking for a leading '/' -- see SampleBank.cpp -- so no further
     * marker is needed here.
     *
     * A loop block referencing an unknown sample id is silently skipped
     * rather than failing the whole load; every other block still loads and
     * plays normally.
     */
    fun loadProject(project: Project, samples: Map<String, Sample>) {
        AudioEngineBridge.beginProject(project.bpm)
        for (track in project.tracks) {
            AudioEngineBridge.addTrack(track.slot)
            for (block in track.loopBlocks) {
                val sample = samples[block.sampleId] ?: continue
                val assetPath = when (val source = sample.source) {
                    is SampleSource.BundledAsset -> source.assetPath
                    is SampleSource.ImportedFile -> source.uri
                }
                AudioEngineBridge.addLoopBlock(
                    track.slot,
                    assetPath,
                    block.startGridUnit,
                    block.lengthGridUnits,
                    block.volume,
                    block.trimStartMs,
                    block.trimEndMs ?: -1L,
                    block.pitchSemitones
                )
            }
        }
        AudioEngineBridge.commitProject()
    }

    /**
     * Renders [project] (via [samples], resolved exactly like [loadProject]
     * above -- same asset-path/imported-file resolution, same "unknown
     * sample id skipped" behavior) offline to a fresh 16-bit PCM WAV file at
     * [outputFilePath], at [sampleRate] and [totalFrames] long. Uses a
     * throwaway offline engine handle (see AudioEngine.h's "offline/test
     * mode" doc comment) entirely separate from the live engine this
     * controller otherwise drives, so exporting never touches -- and can
     * safely run concurrently with -- ongoing live playback. Returns true on
     * success.
     *
     * Background-thread only, same contract as [loadProject]: this does
     * asset decode/resample (addLoopBlock) and file I/O (the native
     * WavWriter call).
     */
    fun exportToFile(
        project: Project,
        samples: Map<String, Sample>,
        sampleRate: Int,
        totalFrames: Long,
        outputFilePath: String
    ): Boolean {
        val handle = AudioEngineBridge.nativeExportCreateEngine(context.assets, sampleRate)
        try {
            AudioEngineBridge.nativeExportBeginProject(handle, project.bpm)
            for (track in project.tracks) {
                AudioEngineBridge.nativeExportAddTrack(handle, track.slot)
                for (block in track.loopBlocks) {
                    val sample = samples[block.sampleId] ?: continue
                    val assetPath = when (val source = sample.source) {
                        is SampleSource.BundledAsset -> source.assetPath
                        is SampleSource.ImportedFile -> source.uri
                    }
                    AudioEngineBridge.nativeExportAddLoopBlock(
                        handle,
                        track.slot,
                        assetPath,
                        block.startGridUnit,
                        block.lengthGridUnits,
                        block.volume,
                        block.trimStartMs,
                        block.trimEndMs ?: -1L,
                        block.pitchSemitones
                    )
                }
            }
            AudioEngineBridge.nativeExportCommitProject(handle)
            return AudioEngineBridge.nativeExportRenderToFile(handle, totalFrames, outputFilePath) == totalFrames
        } finally {
            AudioEngineBridge.nativeExportDestroyEngine(handle)
        }
    }

    fun play() = AudioEngineBridge.play()
    fun pause() = AudioEngineBridge.pause()
    fun stop() = AudioEngineBridge.stop()
    fun seekToFrame(frame: Long) = AudioEngineBridge.seekToFrame(frame)
    fun getCurrentFrame(): Long = AudioEngineBridge.getCurrentFrame()
    fun getSampleRate(): Int = AudioEngineBridge.getSampleRate()

    // --- Recording (Phase 5) -- thin delegation to AudioEngineBridge,
    // mirroring the transport controls above exactly. Same threading
    // contract as those: cheap/safe from any thread, but callers must
    // still route them through ArrangementViewModel's companion-object
    // engineMutex like every other engine-touching call (see that class's
    // SERIALIZATION note) -- see AudioEngineBridge's "Recording" section
    // for the full contract. ---

    fun startRecording(): Boolean = AudioEngineBridge.startRecording()
    fun stopRecording(outputFilePath: String): Long = AudioEngineBridge.stopRecording(outputFilePath)
    fun isRecording(): Boolean = AudioEngineBridge.isRecording()
    fun getRecordingStartFrame(): Long = AudioEngineBridge.getRecordingStartFrame()
    fun getRecordedFrameCount(): Long = AudioEngineBridge.getRecordedFrameCount()
    fun getInputLatencyMillis(): Double = AudioEngineBridge.getInputLatencyMillis()
    fun getOutputLatencyMillis(): Double = AudioEngineBridge.getOutputLatencyMillis()
    fun isRecordingCapReached(): Boolean = AudioEngineBridge.isRecordingCapReached()
}
