package io.github.abrahampaulj.pocketfuse.core

import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.api.StreamClosedException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement

sealed interface FrameResult {
    data class Ready(val jpeg: ByteArray, val millis: Long) : FrameResult
    data class Failed(val message: String) : FrameResult
}

/**
 * Runs single frames through the server with the `/stream` WebSocket instead of a job: nothing is
 * written on the server and a frame comes back in a fraction of a second. Used by the swap preview,
 * the settings lab and the puppet.
 */
class StreamPreview(private val connection: Connection) {

    /** Uploads (once) and selects the source faces, the one thing the stream reads from the session. */
    suspend fun prepare(sources: List<LocalMedia>): FrameResult? = guarded {
        val session = connection.ensureOpen()
        if (session !is SessionState.Open) return@guarded FrameResult.Failed((session as? SessionState.Failed)?.message ?: "Not connected.")
        if (sources.isNotEmpty()) {
            val ids = sources.map { connection.upload(AssetType.Source, it) }
            connection.keeper.call { selectSources(ids) }
        }
        null
    }

    suspend fun process(frame: ByteArray, state: Map<String, JsonElement>): FrameResult = guarded {
        connection.applyState(state)
        val started = System.currentTimeMillis()
        val output = connection.frames.process(frame)
        FrameResult.Ready(output, System.currentTimeMillis() - started)
    } ?: FrameResult.Failed("No answer from the server.")

    private suspend fun <T : FrameResult?> guarded(block: suspend () -> T): FrameResult? = try {
        block()
    } catch (exception: ApiException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: UnreachableException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: StreamClosedException) {
        FrameResult.Failed(exception.userMessage)
    } catch (exception: TimeoutCancellationException) {
        FrameResult.Failed("The server took too long to answer. It may still be loading models: try again in a moment.")
    }
}
