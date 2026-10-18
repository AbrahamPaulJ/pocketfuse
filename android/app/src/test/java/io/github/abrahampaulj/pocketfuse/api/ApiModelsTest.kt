package io.github.abrahampaulj.pocketfuse.api

import io.github.abrahampaulj.pocketfuse.core.FaceScope
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No user-facing text may contain it (project rule); spelled as a code point so this file has none either. */
private val EM_DASH = Char(0x2014)

/** Parses JSON captured from a real FaceFusion v4 server (src/test/resources). */
class ApiModelsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val capabilities = Capabilities.parse(FaceFusionClient.json.parseToJsonElement(resource("capabilities.json")).jsonObject)

    @Test
    fun capabilitiesExposeEveryProcessorSection() {
        listOf("face_swapper", "face_enhancer", "face_editor", "lip_syncer", "frame_enhancer").forEach {
            assertTrue("missing $it", capabilities.section(it).isNotEmpty())
        }
        assertEquals("hyperswap_1a_256", capabilities["face_swapper_model"]!!.default.asText())
        assertTrue("ghost_1_256" in capabilities.choicesOf("face_swapper_model"))
    }

    @Test
    fun capabilitiesTellControlsApart() {
        assertTrue(capabilities["face_editor_head_yaw"]!!.isNumeric)
        assertEquals(41, capabilities["face_editor_head_yaw"]!!.choices!!.size)
        assertTrue(capabilities["processors"]!!.isMulti)
        assertFalse(capabilities["face_swapper_model"]!!.isNumeric)
    }

    @Test
    fun formatsAreCheckedBeforeUploading() {
        assertTrue(capabilities.accepts("MP4"))
        assertTrue(capabilities.accepts("webp"))
        assertFalse(capabilities.accepts("heic"))
    }

    @Test
    fun metricsParseIntoGpuAndSystemLoad() {
        val metrics = Metrics.parse(FaceFusionClient.json.parseToJsonElement(resource("metrics.json")).jsonObject)
        assertEquals("NVIDIA GeForce RTX 3050 6GB Laptop GPU", metrics.gpus.single().name)
        assertEquals("6 GB", metrics.gpus.single().memoryTotal.toString())
        assertEquals("%", metrics.processorUtilization!!.unit)
        assertEquals("GB", metrics.diskFree!!.unit)
    }

    @Test
    fun jobStatusIsDerivedFromSteps() {
        fun job(vararg statuses: String) = Job(steps = statuses.map { JobStep(StepArgs(listOf("face_swapper")), it) })
        assertEquals(JobStatus.Completed, job("completed", "completed").status)
        assertEquals(JobStatus.Failed, job("completed", "failed").status)
        assertEquals(JobStatus.Running, job("completed", "started").status)
        assertEquals(JobStatus.Queued, job("queued").status)
        assertEquals(JobStatus.Drafted, job("drafted").status)
    }

    @Test
    fun everyDocumentedStatusHasItsOwnSentence() {
        val statuses = listOf(400, 401, 404, 409, 413, 415, 422, 426, 429, 500)
        Operation.entries.forEach { operation ->
            statuses.forEach { status ->
                val sentence = describe(status, operation)
                assertFalse("$status $operation fell through", sentence.startsWith("Unexpected"))
                assertFalse("em dash in: $sentence", EM_DASH in sentence)
            }
        }
        assertTrue(ApiException(426, null, Operation.Jobs).sessionLost)
        assertTrue(ApiException(401, null, Operation.Jobs).sessionLost)
        assertFalse(ApiException(409, null, Operation.Jobs).sessionLost)
    }

    @Test
    fun streamNeverUsesTheReferenceSelector() {
        val options = SwapOptions(enhance = true)
        assertEquals(JsonPrimitive("reference"), options.toState(forStream = false)["face_selector_mode"])
        assertEquals(JsonPrimitive("one"), options.toState(forStream = true)["face_selector_mode"])
        assertEquals(JsonPrimitive("many"), options.copy(faceScope = FaceScope.Everyone).toState(forStream = true)["face_selector_mode"])
        assertEquals(JsonArray(listOf(JsonPrimitive("face_swapper"), JsonPrimitive("face_enhancer"))), options.toState(false)["processors"])
    }

    @Test
    fun overridesTheServerNoLongerOffersAreDropped() {
        val options = SwapOptions()
            .with("face_swapper_model", JsonPrimitive("ghost_2_256"))
            .with("face_swapper_model_from_the_future", JsonPrimitive("x"))
            .with("face_enhancer_blend", JsonPrimitive(250))
        assertEquals(setOf("face_swapper_model"), options.validFor(capabilities).overrides.keys)
    }
}
