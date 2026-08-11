package com.beatwave.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a [Sample]'s audio data lives. kotlinx.serialization encodes this as
 * a JSON object with a "type" class-discriminator key (see the SerialName on
 * each subtype) alongside the subtype's own fields.
 */
@Serializable
sealed class SampleSource {

    /** A loop bundled into app assets. [assetPath] is relative to assets/,
     *  e.g. "loops/kick_basic_01.wav". */
    @Serializable
    @SerialName("bundled_asset")
    data class BundledAsset(val assetPath: String) : SampleSource()

    /** A user-imported sample. [uri] is an Android content URI as a String.
     *  Wired up in Phase 4 (Storage Access Framework import). */
    @Serializable
    @SerialName("imported_file")
    data class ImportedFile(val uri: String) : SampleSource()
}
