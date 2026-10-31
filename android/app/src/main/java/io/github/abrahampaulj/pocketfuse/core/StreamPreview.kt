package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.api.StreamClosedException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import java.io.IOException

sealed interface FrameResult {
    data class Ready(val jpeg: ByteArray, val millis: Long) : FrameResult
    data class Failed(val message: String) : FrameResult
}

/**
 * Runs single frames through the server with the `/stream` WebSocket instead of a job: nothing is
 * written on the server and a frame comes back in a fraction of a second. Used by the swap preview,
 * the live stream setup, the settings lab and the puppet.
 */
class StreamPreview(context: Context, private val connection: Connection, private val onStatus: (String) -> Unit = {}) {
    private val warmup = ModelWarmup(context, connection)
    private var sourceIds: List<String> = emptyList()

    /** Uploads (once) and selects the source faces, the one input the stream reads from the session. */
    suspend fun prepare(sources: List<LocalMedia>): FrameResult.Failed? = guarded {
        val session = connection.ensureOpen()
        if (session !is SessionState.Open) return@guarded FrameResult.Failed((session as? SessionState.Failed)?.message ?: "Not connected.")
        if (sources.isNotEmpty()) {
            sourceIds = sources.map { connection.upload(AssetType.Source, it) }
            connection.keeper.call { selectSources(sourceIds) }
        }
        null
    } as FrameResult.Failed?

    /** Makes the models in [state] available on the PC and applies [state]. Needed before any streaming. */
    suspend fun apply(state: Map<String, JsonElement>): FrameResult.Failed? = guarded {
        warmup.ensure(state, sourceIds, onStatus)?.let { return@guarded FrameResult.Failed(it) }
        connection.applyState(state)
        null
    } as FrameResult.Failed?

    suspend fun process(frame: ByteArray, state: Map<String, JsonElement>): FrameResult = guarded {
        apply(state)?.let { return@guarded it }
        val started = System.currentTimeMillis()
        val output = connection.frames.process(frame)
        FrameResult.Ready(output, System.currentTimeMillis() - started)
    } ?: FrameResult.Failed("No answer from the server.")

    private suspend fun guarded(block: suspend () -> FrameResult?): FrameResult? = try {
        block()
    } catch (exception: ApiException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: UnreachableException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: StreamClosedException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: TimeoutCancellationException) {
        FrameResult.Failed("The server took too long to answer. It may still be loading models: try again in a moment.")
    } catch (exception: IOException) {
        FrameResult.Failed(phoneFileMessage(exception))
    } catch (exception: SecurityException) {
        FrameResult.Failed(phoneFileMessage(exception))
    }
}
