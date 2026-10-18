package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.JobStatus
import io.github.abrahampaulj.pocketfuse.api.asText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Makes sure the PC has the models a stream is about to use.
 *
 * FaceFusion downloads a processor's model only when a JOB needs it; a `/stream` frame that needs a
 * missing model crashes the socket instead. So the first time a combination of processors and models
 * is used for streaming, Pocketfuse runs a one-image job (the source face as its own target), which
 * downloads whatever is missing. Combinations already warmed are remembered per server.
 */
class ModelWarmup(context: Context, private val connection: Connection) {
    private val prefs = context.getSharedPreferences("warmed", Context.MODE_PRIVATE)
    private val storeKey = "server:" + connection.config.label

    fun modelKey(state: Map<String, JsonElement>, capabilities: Capabilities?): String {
        val processors = (state["processors"] as? JsonArray)?.map { it.asText() }.orEmpty()
        return processors.sorted().joinToString("|") { processor ->
            val modelKey = processor + "_model"
            val model = state[modelKey] ?: capabilities?.get(modelKey)?.default
            processor + ":" + (model?.asText() ?: "-")
        }
    }

    fun isWarm(key: String): Boolean = key in prefs.getStringSet(storeKey, emptySet())!!

    /** Returns null when ready, or a sentence for the user. [onStatus] is told when a download starts. */
    suspend fun ensure(state: Map<String, JsonElement>, sourceAssetIds: List<String>, onStatus: (String) -> Unit = {}): String? {
        val key = modelKey(state, connection.capabilities.value)
        if (key.isEmpty() || isWarm(key) || sourceAssetIds.isEmpty()) return null
        onStatus("Getting models ready on your PC (first use of $key). This can take a few minutes once.")
        val keeper = connection.keeper
        keeper.call { selectSources(sourceAssetIds) }
        keeper.call { selectTarget(sourceAssetIds.first()) }
        val jobState = state + ("face_selector_mode" to JsonPrimitive("one"))
        connection.applyState(jobState)
        val jobId = keeper.call { createJob() }
        keeper.call { addStep(jobId, jobState) }
        keeper.call { submitJob(jobId) }
        keeper.call { runJob(jobId) }
        while (true) {
            when (keeper.call { job(jobId) }.status) {
                JobStatus.Completed -> break
                JobStatus.Failed -> return "Your PC could not prepare the models for $key. Its console says why."
                else -> delay(1000)
            }
        }
        runCatching { keeper.call { deleteJob(jobId) } }
        prefs.edit().putStringSet(storeKey, prefs.getStringSet(storeKey, emptySet())!! + key).apply()
        return null
    }
}
