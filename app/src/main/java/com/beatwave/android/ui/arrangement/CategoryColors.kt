package com.beatwave.android.ui.arrangement

import androidx.compose.ui.graphics.Color
import com.beatwave.android.data.model.SampleCategory

/**
 * Category color coding, kept consistent between the timeline blocks and the
 * loop library cards, per the settled UX design (item 2).
 */
object CategoryColors {
    val Drums = Color(0xFFE8734A)
    val Bass = Color(0xFF4AE8B8)
    val Synth = Color(0xFF4A90E8)
    val Vocal = Color(0xFFE84A90)

    fun forCategory(category: SampleCategory): Color = when (category) {
        SampleCategory.DRUMS -> Drums
        SampleCategory.BASS -> Bass
        SampleCategory.SYNTH -> Synth
        SampleCategory.VOCAL -> Vocal
    }
}
