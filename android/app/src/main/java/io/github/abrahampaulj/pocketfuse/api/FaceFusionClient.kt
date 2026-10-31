package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A file to upload: opened lazily so a large video is streamed, never held in memory. [temporary] is a copy
 * the phone made just for this upload, deleted by the caller once the upload is over.
 */
class UploadPart(
    val fileName: String,
    val mediaType: String,
    val length: Long,
    val temporary: File? = null,
    val open: () -> InputStream,
)

enum class AssetType(val wire: String) { Source("source"), Target("target") }

enum class CaptureSubject(val wire: String) { Frame("frame"), Face("face") }

/**
 * The FaceFusion v4 HTTP and WebSocket API, one call per endpoint. Holds the current tokens; session
 * lifetime (refresh, recovery) is [SessionKeeper]'s job. Tokens only ever travel in the Authorization
 * header or the WebSocket subprotocol, never in a URL, and are never logged.
 */
class FaceFusionClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    val host: String get() = baseUrl.host + ":" + baseUrl.port

    @Volatile
    var tokens: Tokens? = null

    // ---- system

    suspend fun root(): ServerInfo = decode(execute(get("/"), Operation.Capabilities, auth = false))

    suspend fun capabilities(): Capabilities =
        Capabilities.parse(decodeObject(execute(get("/capabilities"), Operation.Capabilities, auth = false)))

    suspend fun metrics(): Metrics = Metrics.parse(decodeObject(execute(get("/metrics"), Operation.Metrics)))

    // ---- session

    suspend fun openSession(apiKey: String?): Tokens {
        val body = buildJsonObject { if (!apiKey.isNullOrEmpty()) put("api_key", apiKey) }
        val opened: Tokens = decode(execute(post("/session", body.toBody()), Operation.OpenSession, auth = false))
        tokens = opened
        return opened
    }

    suspend fun refreshSession(): Tokens {
        val current = tokens ?: throw ApiException(401, null, Operation.RefreshSession)
        val body = buildJsonObject { put("refresh_token", current.refreshToken) }
        val rotated: Tokens = decode(execute(request("/session").put(body.toBody()).build(), Operation.RefreshSession, auth = false))
        tokens = rotated
        return rotated
    }

    suspend fun session(): SessionInfo = decode(execute(get("/session"), Operation.OpenSession))

    suspend fun closeSession() {
        try {
            execute(request("/session").delete().build(), Operation.CloseSession).close()
        } finally {
            tokens = null
        }
    }

    // ---- state

    suspend fun state(): JsonObject = decodeObject(execute(get("/state"), Operation.State))

    /** All or nothing on the server: one invalid value and nothing is applied (400). */
    suspend fun setState(values: Map<String, JsonElement>): JsonObject =
        decodeObject(execute(request("/state").put(JsonObject(values).toBody()).build(), Operation.State))

    suspend fun selectSources(assetIds: List<String>): JsonObject {
        val body = buildJsonObject { putJsonArray("asset_ids") { assetIds.forEach { add(it) } } }
        val url = url("/state") { addQueryParameter("action", "select"); addQueryParameter("type", "source") }
        return decodeObject(execute(Request.Builder().url(url).put(body.toBody()).build(), Operation.SelectAssets))
    }

    suspend fun selectTarget(assetId: String): JsonObject {
        val body = buildJsonObject { put("asset_id", assetId) }
        val url = url("/state") { addQueryParameter("action", "select"); addQueryParameter("type", "target") }
        return decodeObject(execute(Request.Builder().url(url).put(body.toBody()).build(), Operation.SelectAssets))
    }

    // ---- assets

    suspend fun upload(type: AssetType, parts: List<UploadPart>, onProgress: (Float) -> Unit = {}): List<String> {
        require(parts.isNotEmpty() && parts.size <= MAX_UPLOAD_FILES)
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        var sent = 0L
        val progress = ProgressThrottle(onProgress)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            parts.forEach { part ->
                addFormDataPart("file", part.fileName, StreamingBody(part) { delta ->
                    sent += delta
                    progress.report(sent, total)
                })
            }
        }.build()
        val url = url("/assets") { addQueryParameter("type", type.wire) }
        val response = execute(Request.Builder().url(url).post(multipart).build(), Operation.Upload, longRunning = true)
        return decode<AssetIds>(response).assetIds
    }

    suspend fun assets(): List<Asset> = decode<AssetList>(execute(get("/assets"), Operation.Assets)).assets

    suspend fun asset(assetId: String): Asset = decode(execute(get("/assets/$assetId"), Operation.Assets))

    suspend fun deleteAsset(assetId: String) {
        execute(request("/assets/$assetId").delete().build(), Operation.Assets).close()
    }

    /** Every asset of this session: uploads and outputs. Other sessions' files are out of its reach. */
    suspend fun deleteAssets() {
        execute(request("/assets").delete().build(), Operation.Assets).close()
    }

    /**
     * Frames or detected faces of an asset as JPEG tiles. A frame index is always sent: the `v4` branch
     * requires one even for an image, and `v4-challenge` accepts it.
     */
    suspend fun capture(assetId: String, subject: CaptureSubject, width: Int, height: Int, frameIndexes: List<Int> = listOf(0)): Capture {
        val url = url("/assets/$assetId") {
            addQueryParameter("action", "capture")
            addQueryParameter("subject", subject.wire)
            addQueryParameter("resolution", "${width}x$height")
            frameIndexes.forEach { addQueryParameter("frame_index", it.toString()) }
        }
        return execute(Request.Builder().url(url).get().build(), Operation.Capture, longRunning = true).use {
            Capture.from(it.body!!.bytes(), it.header("Content-Type"))
        }
    }

    /**
     * Saves the asset to [into]. The bytes go to a `.part` file renamed only once complete, so a dropped
     * connection never leaves a truncated result behind; it ends as [UnreachableException], and a phone
     * too full for the rest as the plain [IOException].
     */
    suspend fun download(assetId: String, into: File, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val url = url("/assets/$assetId") { addQueryParameter("action", "download") }
        val partial = File(into.path + PARTIAL_SUFFIX)
        val progress = ProgressThrottle(onProgress)
        execute(Request.Builder().url(url).get().build(), Operation.Download, longRunning = true, streamBody = true).use { response ->
            val body = response.body!!
            val total = body.contentLength()
            try {
                into.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) progress.report(copied, total)
                        }
                    }
                }
            } catch (exception: IOException) {
                // A full phone fails the write, not the connection: when the rest could never fit, it is the phone's fault.
                val phoneFull = total > 0 && partial.length() + (partial.parentFile?.usableSpace ?: 0L) < total
                partial.delete()
                throw if (phoneFull) exception else UnreachableException(host, exception)
            }
        }
        if (!partial.renameTo(into)) {
            partial.delete()
            throw IOException("Could not save the result")
        }
        into
    }

    // ---- jobs

    suspend fun createJob(): String = decode<CreatedJob>(execute(post("/jobs", "{}".toRequestBody(JSON)), Operation.Jobs)).jobId

    /** The step body takes the capability options; source, target and output come from the session state. */
    suspend fun addStep(jobId: String, step: Map<String, JsonElement>) {
        val url = url("/jobs/$jobId") { addQueryParameter("action", "add") }
        execute(Request.Builder().url(url).post(JsonObject(step).toBody()).build(), Operation.Jobs).close()
    }

    suspend fun submitJob(jobId: String) = jobAction(jobId, "submit")
    suspend fun runJob(jobId: String) = jobAction(jobId, "run")
    suspend fun retryJob(jobId: String) = jobAction(jobId, "retry")

    suspend fun job(jobId: String): Job = decode(execute(get("/jobs/$jobId"), Operation.Jobs))

    /** Job ids for one status, newest last. */
    suspend fun jobIds(status: String): List<String> =
        decodeObject(execute(Request.Builder().url(url("/jobs") { addQueryParameter("status", status) }).get().build(), Operation.Jobs)).keys.sorted()

    suspend fun deleteJob(jobId: String) {
        execute(request("/jobs/$jobId").delete().build(), Operation.Jobs).close()
    }

    private suspend fun jobAction(jobId: String, action: String) {
        val url = url("/jobs/$jobId") { addQueryParameter("action", action) }
        execute(Request.Builder().url(url).patch("".toRequestBody(null)).build(), Operation.Jobs).close()
    }

    // ---- stream

    suspend fun postStreamOffer(sdpOffer: String): String {
        val request = request("/stream").post(sdpOffer.toRequestBody(SDP)).build()
        return execute(request, Operation.Stream, longRunning = true).use { it.body!!.string() }
    }

    suspend fun deleteStream() {
        execute(request("/stream").delete().build(), Operation.Stream).close()
    }

    /** /stream, /metrics or /ping. The access token rides in the subprotocol, as the guide requires. */
    fun openSocket(path: String, listener: WebSocketListener): WebSocket {
        val token = tokens?.accessToken ?: throw ApiException(401, null, Operation.Stream)
        val wsUrl = baseUrl.newBuilder().encodedPath(path).build().toString()
            .replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        val request = Request.Builder().url(wsUrl).header("Sec-WebSocket-Protocol", "access_token.$token").build()
        return http.newWebSocket(request, listener)
    }

    // ---- plumbing

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder().encodedPath(path).apply(query).build()

    private fun request(path: String): Request.Builder = Request.Builder().url(url(path))
    private fun get(path: String): Request = request(path).get().build()
    private fun post(path: String, body: RequestBody): Request = request(path).post(body).build()

    /**
     * Sends [request] and returns the response with its body already read into memory, all on the IO
     * dispatcher: reading or even closing an OkHttp body touches the socket, which Android forbids on the
     * main thread, and callers include the UI. Only [streamBody] (downloads) hands back the live body.
     */
    private suspend fun execute(
        request: Request,
        operation: Operation,
        auth: Boolean = true,
        longRunning: Boolean = false,
        streamBody: Boolean = false,
    ): Response = withContext(Dispatchers.IO) {
        val authorized = if (auth) {
            val token = tokens?.accessToken ?: throw ApiException(401, null, operation)
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else request
        val client = if (longRunning) http.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build() else http
        val response = try {
            client.newCall(authorized).await()
        } catch (exception: IOException) {
            throw UnreachableException(host, exception)
        }
        if (!response.isSuccessful) {
            val message = response.use { runCatching { json.decodeFromString<Message>(it.body!!.string()).message }.getOrNull() }
            throw ApiException(response.code, message, operation)
        }
        if (streamBody) return@withContext response
        try {
            response.use { live ->
                val body = live.body!!
                live.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
            }
        } catch (exception: IOException) {
            throw UnreachableException(host, exception)
        }
    }

    private inline fun <reified T> decode(response: Response): T = response.use { json.decodeFromString(it.body!!.string()) }
    private fun decodeObject(response: Response): JsonObject = response.use { json.parseToJsonElement(it.body!!.string()).jsonObject }
    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    companion object {
        const val MAX_UPLOAD_FILES = 32
        const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024
        const val MAX_RESOLUTION = 4096
        /** Marks a download still in progress; never listed as a result. */
        const val PARTIAL_SUFFIX = ".part"
        val JSON: MediaType = "application/json".toMediaType()
        val SDP: MediaType = "application/sdp".toMediaType()
        val json = Json { ignoreUnknownKeys = true }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Passes progress on only when the whole percent changes, so at most 101 times per transfer: okio moves
 * 8 KiB per read, and a caller that posts a notification per call would otherwise post thousands.
 */
internal class ProgressThrottle(private val onProgress: (Float) -> Unit) {
    private var lastPercent = -1

    fun report(done: Long, total: Long) {
        val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
        val percent = (fraction * 100).toInt()
        if (percent == lastPercent) return
        lastPercent = percent
        onProgress(fraction)
    }
}

private class StreamingBody(private val part: UploadPart, private val onBytes: (Long) -> Unit) : RequestBody() {
    override fun contentType(): MediaType = part.mediaType.toMediaType()
    override fun contentLength(): Long = part.length
    override fun writeTo(sink: BufferedSink) {
        part.open().source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 64 * 1024)
                if (read < 0) break
                sink.write(buffer, read)
                onBytes(read)
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
