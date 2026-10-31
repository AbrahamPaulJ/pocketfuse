package io.github.abrahampaulj.pocketfuse.core

import io.github.abrahampaulj.pocketfuse.api.Capabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

enum class FaceScope { MainPerson, Everyone }

/**
 * What the user chose for a swap, as FaceFusion option keys. Only [overrides] are sent: anything the user
 * did not touch keeps the server's default, which the capabilities list reports.
 */
@Serializable
data class SwapOptions(
    val enhance: Boolean = false,
    val restoreExpression: Boolean = false,
    val lipSync: Boolean = false,
    val keepMouth: Boolean = false,
    val faceScope: FaceScope = FaceScope.MainPerson,
    val overrides: Map<String, JsonElement> = emptyMap(),
) {
    val processors: List<String> get() = processors(hasFaces = true, hasVoice = false)

    /** Without a source face there is nothing to swap; without a voice there is nothing to lip sync to. */
    fun processors(hasFaces: Boolean, hasVoice: Boolean): List<String> = buildList {
        if (hasFaces) add("face_swapper")
        if (lipSync && hasVoice) add("lip_syncer")
        if (restoreExpression) add("expression_restorer")
        if (enhance) add("face_enhancer")
    }

    /**
     * The state for a job. A job can use `reference` (track the first person by identity), but the stream
     * cannot: it has no reference frame and silently processes nothing under that mode, so [forStream]
     * maps the main person to `one` (the largest face) instead.
     */
    fun toState(forStream: Boolean, hasFaces: Boolean = true, hasVoice: Boolean = false): Map<String, JsonElement> {
        val selector = when (faceScope) {
            FaceScope.Everyone -> "many"
            FaceScope.MainPerson -> if (forStream) "one" else "reference"
        }
        val masks = if (keepMouth) mouthMasks() else emptyMap()
        val maskDefaults = if (forStream && !keepMouth) DEFAULT_MASKS else emptyMap()
        return maskDefaults + overrides + masks + mapOf(
            "processors" to JsonArray(processors(hasFaces, hasVoice).map(::JsonPrimitive)),
            "face_selector_mode" to JsonPrimitive(selector),
        )
    }

    /**
     * The face parser region mask, minus the mouth and lips, on top of the mask types the user picked: the
     * server keeps the swapped face only where every mask agrees, so the real mouth shows through. The job
     * path drops these again through the leftover reset; the stream has none, so [toState] sends
     * [DEFAULT_MASKS] for it while the toggle is off.
     */
    private fun mouthMasks(): Map<String, JsonElement> {
        val picked = (overrides[FACE_MASK_TYPES] as? JsonArray)?.toList() ?: listOf(JsonPrimitive("box"))
        return mapOf(
            FACE_MASK_TYPES to JsonArray((picked + JsonPrimitive("region")).distinct()),
            FACE_MASK_REGIONS to JsonArray(MOUTHLESS_REGIONS.map(::JsonPrimitive)),
        )
    }

    fun with(key: String, value: JsonElement?): SwapOptions =
        copy(overrides = if (value == null) overrides - key else overrides + (key to value))

    /** Drops overrides the server no longer offers (a different FaceFusion version, say). */
    fun validFor(capabilities: Capabilities): SwapOptions = copy(
        overrides = overrides.filter { (key, value) ->
            val argument = capabilities[key] ?: return@filter false
            val choices = argument.choices ?: return@filter true
            if (value is JsonArray) value.all { it in choices } else value in choices
        },
    )
}

private const val FACE_MASK_TYPES = "face_mask_types"
private const val FACE_MASK_REGIONS = "face_mask_regions"

/** facefusion.choices.face_mask_regions, the server's default for face_mask_regions. */
private val FACE_MASK_REGION_CHOICES = listOf("skin", "left-eyebrow", "right-eyebrow", "left-eye", "right-eye", "glasses", "nose", "mouth", "upper-lip", "lower-lip")
private val MOUTHLESS_REGIONS = FACE_MASK_REGION_CHOICES - setOf("mouth", "upper-lip", "lower-lip")
private val DEFAULT_MASKS: Map<String, JsonElement> = mapOf(
    FACE_MASK_TYPES to JsonArray(listOf(JsonPrimitive("box"))),
    FACE_MASK_REGIONS to JsonArray(FACE_MASK_REGION_CHOICES.map(::JsonPrimitive)),
)

/**
 * [state] plus the server default for every option in [applied] (what the session was given earlier) that
 * [state] leaves out. A job reads any option it does not name from the session, so a value a preview, the
 * Lab or the puppet left there would otherwise carry over into the job unseen. Options whose default is
 * null are left alone: the server would refuse a null.
 */
fun resetLeftovers(state: Map<String, JsonElement>, applied: Map<String, JsonElement>, capabilities: Capabilities): Map<String, JsonElement> {
    val defaults = applied.keys.filter { it !in state }.mapNotNull { key ->
        capabilities[key]?.default?.takeIf { it != JsonNull }?.let { key to it }
    }
    return defaults.toMap() + state
}
