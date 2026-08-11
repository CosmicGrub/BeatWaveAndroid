package com.beatwave.android.data.library

import android.content.Context
import com.beatwave.android.data.model.Sample

/**
 * Thin Android-facing adapter that loads the bundled loop pack's [Sample]
 * metadata from `assets/loops/manifest.json` via [Context.getAssets]. Holds
 * no parsing logic of its own -- that lives in [LoopManifestParser], which is
 * unit-testable without any Android framework classes.
 */
class AssetLoopLibrary(private val context: Context) {

    /** Reads and parses the bundled manifest, returning its [Sample]s. */
    fun loadSamples(): List<Sample> =
        context.assets.open(MANIFEST_ASSET_PATH).use { stream ->
            LoopManifestParser.parse(stream)
        }

    companion object {
        private const val MANIFEST_ASSET_PATH = "loops/manifest.json"
    }
}
