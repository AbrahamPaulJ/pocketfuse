package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The `/stream` WebSocket: send one image, receive one processed JPEG. One frame is in flight at a time,
 * which is also what keeps a slow GPU from building a queue of stale frames.
 *
 * The server answers only decodable images and closes the socket with the session, so a closed socket
 * fails the pending frame rather than hanging it. The guide limits a session to one such socket.
 */
class FrameStream(private val client: FaceFusionClient) {
    private val mutex = Mutex()
    private var socket: WebSocket? = null
    private var pending: CompletableDeferred<ByteArray>? = null
    @Volatile private var closedReason: String? = null

    val isOpen: Boolean get() = socket != null && closedReason == null

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            pending?.complete(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail("The server closed the stream ($code).")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            fail(if (status != null) describe(status, Operation.Stream) else "The stream connection dropped.")
        }
    }

    private fun fail(reason: String) {
        closedReason = reason
        socket = null
        pending?.completeExceptionally(StreamClosedException(reason))
    }

    /** Sends [jpeg] and waits for the processed frame. Opens the socket on first use. */
    suspend fun process(jpeg: ByteArray, timeoutMs: Long = 60_000): ByteArray = mutex.withLock {
        val open = socket ?: client.openSocket("/stream", listener).also {
            socket = it
            closedReason = null
        }
        val reply = CompletableDeferred<ByteArray>()
        pending = reply
        if (!open.send(jpeg.toByteString())) throw StreamClosedException(closedReason ?: "The stream is closed.")
        try {
            withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending = null
        }
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}

class StreamClosedException(val userMessage: String) : Exception(userMessage)
