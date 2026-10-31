package io.github.abrahampaulj.pocketfuse.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapOptionsTest {
    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    @Test
    fun keepMouthMasksEverythingButTheMouthAndLips() {
        val state = SwapOptions(keepMouth = true).toState(forStream = true)
        assertEquals(strings("box", "region"), state["face_mask_types"])
        val regions = (state["face_mask_regions"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("skin" in regions)
        assertFalse("mouth" in regions || "upper-lip" in regions || "lower-lip" in regions)
    }

    @Test
    fun keepMouthAddsRegionToTheUserMaskTypes() {
        val state = SwapOptions(keepMouth = true).with("face_mask_types", strings("box", "occlusion")).toState(forStream = false)
        assertEquals(strings("box", "occlusion", "region"), state["face_mask_types"])
    }

    @Test
    fun streamWithoutKeepMouthRestoresTheDefaultMasks() {
        val state = SwapOptions().toState(forStream = true)
        assertEquals(strings("box"), state["face_mask_types"])
        assertEquals(10, (state["face_mask_regions"] as JsonArray).size)
        assertEquals(strings("occlusion"), SwapOptions().with("face_mask_types", strings("occlusion")).toState(forStream = true)["face_mask_types"])
        assertNull("the job path resets through Connection.withDefaults", SwapOptions().toState(forStream = false)["face_mask_types"])
    }
}
