package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import android.net.Uri
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.FrameStream
import io.github.abrahampaulj.pocketfuse.api.MetricsFeed
import io.github.abrahampaulj.pocketfuse.api.SessionKeeper
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

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
            metrics.start()
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
        val part = MediaInspector.prepareUpload(context, media)
        val assetId = keeper.call { upload(type, listOf(part), onProgress) }.first()
        uploaded[key] = assetId
        assetId
    }

    fun knownAsset(type: AssetType, uri: Uri): String? = uploaded["$generation|${type.wire}|$uri"]

    /** Sends only the options that differ from what this session already has. */
    suspend fun applyState(values: Map<String, JsonElement>) {
        val changed = values.filter { (key, value) -> appliedState[key] != value }
        if (changed.isEmpty()) return
        keeper.call { setState(changed) }
        appliedState = appliedState + changed
    }

    suspend fun close() {
        frames.close()
        metrics.stop()
        keeper.close()
        uploaded.clear()
    }
}
