package com.beatwave.android.ui.arrangement

import com.beatwave.android.data.model.LoopBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 3 cutover gates: [gridColumnCount] and [gridCellDescription]. */
class GridCutoverGatesTest {

    private fun block(start: Int, length: Int) =
        LoopBlock(id = "b$start", sampleId = "s", startGridUnit = start, lengthGridUnits = length)

    @Test
    fun emptyOrShortProjectKeepsTheDefaultWidth() {
        assertEquals(64, gridColumnCount(emptyList()))
        assertEquals(64, gridColumnCount(listOf(block(10, 4))))
    }

    @Test
    fun blockPastTheOldCeilingWidensTheGridByOneBar() {
        // ends at 100 -> 7 bars cover it (112), plus one empty bar = 128
        assertEquals(128, gridColumnCount(listOf(block(96, 4))))
        // every block's last column must be reachable
        for (end in listOf(63, 64, 65, 200, 500)) {
            val cols = gridColumnCount(listOf(block(end - 1, 1)))
            assertTrue("end=$end cols=$cols", cols > end && cols % 16 == 0)
        }
    }

    @Test
    fun cellDescriptionIsPositionAwareAndKeepsTheFilledEmptyPrefix() {
        assertEquals("Empty cell, +7 semitones, bar 1 beat 1 step 1", gridCellDescription(false, "+7 semitones", 0))
        assertEquals("Filled cell, Basic Kick, bar 2 beat 3 step 2", gridCellDescription(true, "Basic Kick", 16 + 8 + 1))
        assertNotEquals(gridCellDescription(false, "x", 3), gridCellDescription(false, "x", 4))
        assertTrue(gridCellDescription(true, "x", 0).startsWith("Filled"))
        assertTrue(gridCellDescription(false, "x", 0).startsWith("Empty"))
    }
}
