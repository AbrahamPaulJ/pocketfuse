package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import android.net.Uri
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.CaptureSubject
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.FrameStream
import io.github.abrahampaulj.pocketfuse.api.MetricsFeed
import io.github.abrahampaulj.pocketfuse.api.SessionKeeper
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

sealed interface FaceCheck {
    data object Unknown : FaceCheck
    data object NoFace : FaceCheck
    class Found(val crop: ByteArray) : FaceCheck
}

/**
 * Everything tied to one paired server: the session, the capability list, uploads already on the server,
 * and the two long-lived sockets. Uploads are remembered per session [generation][SessionState.Open]
 * because the server drops a session's files with the session.
 */
class Connection(private val context: Context, val config: ServerConfig, scope: CoroutineScope) {
    val client = FaceFusionClient(config.baseUrl)
    val keeper = SessionKeeper(client, config.apiKey, scope)
    val frames = FrameStream(client)
    val metrics = MetricsFeed(client)

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    val session: StateFlow<SessionState> get() = keeper.state

    private val uploadLock = Mutex()
    private val uploaded = mutableMapOf<String, String>()
    private var appliedState: Map<String, JsonElement> = emptyMap()

    private val generation: Int get() = (keeper.state.value as? SessionState.Open)?.generation ?: -1

    suspend fun connect(): SessionState {
        val state = keeper.open()
        if (state is SessionState.Open) {
            if (_capabilities.value == null) _capabilities.value = runCatching { client.capabilities() }.getOrNull()
            metrics.start { keeper.state.value is SessionState.Open }
        }
        return state
    }

    /** Opens a fresh session when the last one was lost. Files and jobs from the old one are gone. */
    suspend fun ensureOpen(): SessionState {
        val state = keeper.state.value
        if (state is SessionState.Open) return state
        frames.close()
        metrics.stop()
        appliedState = emptyMap()
        return connect()
    }

    /** Uploads [media] once per session; later calls return the same asset id. */
    suspend fun upload(type: AssetType, media: LocalMedia, onProgress: (Float) -> Unit = {}): String = uploadLock.withLock {
        val key = "$generation|${type.wire}|${media.uri}"
        uploaded[key]?.let { return it }
        val part = MediaInspector.prepareUpload(context, media, type)
        val assetId = try {
            keeper.call { upload(type, listOf(part), onProgress) }.first()
        } finally {
            // The asset id is remembered for the session, so the phone's copy is never read again.
            part.temporary?.let { file -> withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        }
        uploaded[key] = assetId
        assetId
    }

    fun knownAsset(type: AssetType, uri: Uri): String? = uploaded["$generation|${type.wire}|$uri"]

    /**
     * Deletes [assetIds] from the PC once the phone has what it needs. Never throws: a file already gone
     * answers 404, and Windows can answer 500 while a file is still held open; DELETE /session or the PC
     * tab's delete button still covers what is left. The ids leave the upload cache so the next use uploads again.
     */
    suspend fun forget(assetIds: Collection<String>) {
        uploadLock.withLock {
            assetIds.forEach { assetId -> runCatching { keeper.call { deleteAsset(assetId) } } }
            uploaded.values.removeAll(assetIds.toSet())
        }
    }

    /**
     * Deletes every upload and result this session put on the PC and returns how many are still there;
     * throws what the server answered. The server answers 200 even for a file Windows holds open, so the count is checked.
     */
    suspend fun forgetAll(): Int = uploadLock.withLock {
        try {
            keeper.call { deleteAssets() }
            keeper.call { assets() }.size
        } finally {
            uploaded.clear()
        }
    }

    /** Sends only the options that differ from what this session already has. */
    suspend fun applyState(values: Map<String, JsonElement>) {
        val changed = values.filter { (key, value) -> appliedState[key] != value }
        if (changed.isEmpty()) return
        keeper.call { setState(changed) }
        appliedState = appliedState + changed
    }

    /** [values] plus the server default for every option this session has that [values] leaves out; see [resetLeftovers]. */
    fun withDefaults(values: Map<String, JsonElement>): Map<String, JsonElement> =
        _capabilities.value?.let { resetLeftovers(values, appliedState, it) } ?: values

    /**
     * The face the PC finds in a source photo, as a JPEG crop, or [FaceCheck.NoFace]: a source without
     * a detectable face makes every job fail, so the app checks before the user waits for one.
     */
    suspend fun detectFace(media: LocalMedia): FaceCheck = try {
        if (ensureOpen() !is SessionState.Open) FaceCheck.Unknown
        else {
            val assetId = upload(AssetType.Source, media)
            val capture = keeper.call { capture(assetId, CaptureSubject.Face, 96, 96) }
            val crop = capture.parts.firstOrNull { it.isNotEmpty() }
            if (crop == null) FaceCheck.NoFace else FaceCheck.Found(crop)
        }
    } catch (exception: ApiException) {
        if (exception.status == 400) FaceCheck.NoFace else FaceCheck.Unknown
    } catch (exception: UnreachableException) {
        FaceCheck.Unknown
    }

    suspend fun close() {
        frames.close()
        metrics.stop()
        keeper.close()
        uploaded.clear()
    }
}
