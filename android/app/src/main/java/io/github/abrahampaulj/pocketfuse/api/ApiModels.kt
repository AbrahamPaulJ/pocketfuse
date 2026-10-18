package io.github.abrahampaulj.pocketfuse.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Field names follow the FaceFusion v4 developer guide exactly. */

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class SessionInfo(
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ServerInfo(val name: String, val version: String)

@Serializable
data class Asset(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val type: String,
    val media: String,
    val name: String,
    val format: String,
    val size: Long,
    val metadata: JsonObject? = null,
) {
    val isVideo: Boolean get() = media == "video"
    val frameTotal: Int? get() = metadata?.get("frame_total")?.jsonPrimitive?.doubleOrNull?.toInt()
    val fps: Double? get() = metadata?.get("fps")?.jsonPrimitive?.doubleOrNull
    val duration: Double? get() = metadata?.get("duration")?.jsonPrimitive?.doubleOrNull
}

@Serializable
internal data class AssetList(val assets: List<Asset>)

@Serializable
internal data class AssetIds(@SerialName("asset_ids") val assetIds: List<String>)

@Serializable
internal data class CreatedJob(@SerialName("job_id") val jobId: String)

@Serializable
data class JobStep(val args: StepArgs, val status: String)

@Serializable
data class StepArgs(val processors: List<String> = emptyList())

@Serializable
data class Job(
    val version: String? = null,
    @SerialName("date_created") val dateCreated: String? = null,
    @SerialName("date_updated") val dateUpdated: String? = null,
    val steps: List<JobStep> = emptyList(),
) {
    /** The guide: the job status is not part of the job, derive it from the steps. */
    val status: JobStatus
        get() = when {
            steps.any { it.status == "failed" } -> JobStatus.Failed
            steps.isNotEmpty() && steps.all { it.status == "completed" } -> JobStatus.Completed
            steps.any { it.status == "started" } -> JobStatus.Running
            steps.any { it.status == "queued" } -> JobStatus.Queued
            else -> JobStatus.Drafted
        }

    val completedSteps: Int get() = steps.count { it.status == "completed" }
}

enum class JobStatus { Drafted, Queued, Running, Completed, Failed }

@Serializable
internal data class Message(val message: String? = null)

/** One option from GET /capabilities. Numeric ranges arrive fully expanded in [choices]. */
data class Argument(
    val key: String,
    val section: String,
    val default: JsonElement,
    val choices: List<JsonElement>?,
) {
    val isMulti: Boolean get() = default is JsonArray
    val isFreeInput: Boolean get() = choices == null
    val isNumeric: Boolean get() = choices?.firstOrNull()?.let { it is JsonPrimitive && !it.isString } == true
}

class Capabilities(val formats: Map<String, List<String>>, val arguments: Map<String, Argument>) {
    fun section(section: String): List<Argument> = arguments.values.filter { it.section == section }
    operator fun get(key: String): Argument? = arguments[key]

    fun choicesOf(key: String): List<String> = arguments[key]?.choices?.map { it.asText() }.orEmpty()

    fun accepts(extension: String): Boolean = formats.values.any { extension.lowercase() in it }

    companion object {
        fun parse(root: JsonObject): Capabilities {
            val formats = root["formats"]?.jsonObject.orEmpty().mapValues { (_, list) ->
                (list as? JsonArray)?.map { it.asText() }.orEmpty()
            }
            val arguments = linkedMapOf<String, Argument>()
            root["arguments"]?.jsonObject.orEmpty().forEach { (section, group) ->
                (group as? JsonObject)?.forEach { (key, spec) ->
                    val specObject = spec.jsonObject
                    arguments[key] = Argument(
                        key = key,
                        section = section,
                        default = specObject["default"] ?: JsonNull,
                        choices = (specObject["choices"] as? JsonArray)?.toList(),
                    )
                }
            }
            return Capabilities(formats, arguments)
        }
    }
}

/** Every metric value arrives as {"value": n, "unit": "..."}. */
data class Measure(val value: Double, val unit: String) {
    override fun toString(): String = (if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)) + " " + unit
}

data class GpuMetrics(
    val name: String,
    val memoryUsed: Measure?,
    val memoryTotal: Measure?,
    val utilization: Measure?,
    val temperature: Measure?,
)

data class Metrics(
    val gpus: List<GpuMetrics>,
    val memoryUtilization: Measure?,
    val processorUtilization: Measure?,
    val diskFree: Measure?,
) {
    companion object {
        fun parse(root: JsonObject): Metrics {
            fun JsonElement?.measure(vararg path: String): Measure? {
                var node: JsonElement? = this
                for (key in path) node = (node as? JsonObject)?.get(key)
                val leaf = node as? JsonObject ?: return null
                val value = leaf["value"]?.jsonPrimitive?.doubleOrNull ?: return null
                return Measure(value, leaf["unit"]?.jsonPrimitive?.content.orEmpty())
            }
            val gpus = (root["graphic_devices"] as? JsonArray).orEmpty().map { device ->
                GpuMetrics(
                    name = (device as? JsonObject)?.get("product")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "GPU",
                    memoryUsed = device.measure("memory", "used"),
                    memoryTotal = device.measure("memory", "total"),
                    utilization = device.measure("utilization", "gpu"),
                    temperature = device.measure("temperature", "gpu"),
                )
            }
            return Metrics(
                gpus = gpus,
                memoryUtilization = root["memory"].measure("utilization"),
                processorUtilization = root["processor"].measure("utilization"),
                diskFree = (root["disks"] as? JsonArray)?.firstOrNull().measure("free"),
            )
        }
    }
}

internal fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()
