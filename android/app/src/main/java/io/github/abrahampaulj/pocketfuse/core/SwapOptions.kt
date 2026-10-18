package io.github.abrahampaulj.pocketfuse.core

import io.github.abrahampaulj.pocketfuse.api.Capabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    val faceScope: FaceScope = FaceScope.MainPerson,
    val overrides: Map<String, JsonElement> = emptyMap(),
) {
    val processors: List<String>
        get() = buildList {
            add("face_swapper")
            if (restoreExpression) add("expression_restorer")
            if (enhance) add("face_enhancer")
        }

    /**
     * The state for a job. A job can use `reference` (track the first person by identity), but the stream
     * cannot: it has no reference frame and silently processes nothing under that mode, so [forStream]
     * maps the main person to `one` (the largest face) instead.
     */
    fun toState(forStream: Boolean): Map<String, JsonElement> {
        val selector = when (faceScope) {
            FaceScope.Everyone -> "many"
            FaceScope.MainPerson -> if (forStream) "one" else "reference"
        }
        return overrides + mapOf(
            "processors" to JsonArray(processors.map(::JsonPrimitive)),
            "face_selector_mode" to JsonPrimitive(selector),
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
