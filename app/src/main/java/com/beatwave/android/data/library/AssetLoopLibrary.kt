package com.beatwave.android.data.library

import android.content.Context
import com.beatwave.android.audio.WaveformPeaksExtractor
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleSource
import java.io.IOException

/**
 * Thin Android-facing adapter that loads the bundled loop pack's [Sample]
 * metadata from `assets/loops/manifest.json` via [Context.getAssets]. Holds
 * no parsing logic of its own -- that lives in [LoopManifestParser], which is
 * unit-testable without any Android framework classes.
 *
 * Waveform-visualization upgrade: also populates each [Sample.waveformPeaks]
 * by decoding the sample's own bundled WAV asset via
 * [WaveformPeaksExtractor] -- bundled samples are never persisted across
 * launches (unlike imported/recorded ones, see [ImportedSampleIndex]), so
 * this runs fresh every app start. Cheap in practice: the whole loop pack is
 * a handful of short (a few seconds each) assets, and this already runs off
 * the main thread (see [com.beatwave.android.ui.arrangement.ArrangementViewModel.init]).
 * A per-sample decode failure degrades to an empty peaks list (no waveform
 * drawn for that one card) rather than failing the whole library load.
 */
class AssetLoopLibrary(private val context: Context) {

    /** Reads and parses the bundled manifest, returning its [Sample]s with
     *  [Sample.waveformPeaks] populated. */
    fun loadSamples(): List<Sample> {
        val samples = context.assets.open(MANIFEST_ASSET_PATH).use { stream ->
            LoopManifestParser.parse(stream)
        }
        return samples.map { sample -> sample.copy(waveformPeaks = peaksForBundledSample(sample)) }
    }

    private fun peaksForBundledSample(sample: Sample): List<Float> {
        val source = sample.source as? SampleSource.BundledAsset ?: return emptyList()
        return try {
            val wavBytes = context.assets.open(source.assetPath).use { it.readBytes() }
            WaveformPeaksExtractor.extract(wavBytes)
        } catch (e: IOException) {
            emptyList()
        }
    }

    companion object {
        private const val MANIFEST_ASSET_PATH = "loops/manifest.json"
    }
}
