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
     * to a bundled asset path via [samples] (typically the id->Sample map
     * loaded by com.beatwave.android.data.library.AssetLoopLibrary).
     *
     * A loop block referencing an unknown sample id, or a sample whose
     * source isn't [SampleSource.BundledAsset] (e.g. an imported file --
     * wired up in Phase 4), is silently skipped rather than failing the
     * whole load; every other block still loads and plays normally.
     */
    fun loadProject(project: Project, samples: Map<String, Sample>) {
        AudioEngineBridge.beginProject(project.bpm)
        for (track in project.tracks) {
            AudioEngineBridge.addTrack(track.slot)
            for (block in track.loopBlocks) {
                val sample = samples[block.sampleId] ?: continue
                val assetPath = (sample.source as? SampleSource.BundledAsset)?.assetPath ?: continue
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

    fun play() = AudioEngineBridge.play()
    fun pause() = AudioEngineBridge.pause()
    fun stop() = AudioEngineBridge.stop()
    fun seekToFrame(frame: Long) = AudioEngineBridge.seekToFrame(frame)
    fun getCurrentFrame(): Long = AudioEngineBridge.getCurrentFrame()
    fun getSampleRate(): Int = AudioEngineBridge.getSampleRate()
}
