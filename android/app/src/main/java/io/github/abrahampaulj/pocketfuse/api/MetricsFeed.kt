package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The `/metrics` WebSocket: the server pushes a snapshot every 2 seconds. Keeps a short history for charts. */
class MetricsFeed(
    private val client: FaceFusionClient,
    private val historySize: Int = 60,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _latest = MutableStateFlow<Metrics?>(null)
    val latest: StateFlow<Metrics?> = _latest.asStateFlow()

    private val _history = MutableStateFlow<List<Metrics>>(emptyList())
    val history: StateFlow<List<Metrics>> = _history.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var keepOpen: () -> Boolean = { false }

    @Volatile
    private var retry: Job? = null

    @Volatile
    private var retryDelayMs = RETRY_FIRST_MS

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            retryDelayMs = RETRY_FIRST_MS
            runCatching { Metrics.parse(FaceFusionClient.json.parseToJsonElement(text).jsonObject) }.getOrNull()?.let(::publish)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    /**
     * Opens the socket. While [keepOpen] holds (the session is still open), a socket that drops is opened
     * again with backoff: a Wi-Fi blip or a missed ping ends the socket, not the session.
     */
    fun start(keepOpen: () -> Boolean = { false }) {
        this.keepOpen = keepOpen
        connect()
    }

    @Synchronized
    private fun connect() {
        if (socket != null) return
        socket = try {
            client.openSocket("/metrics", listener)
        } catch (_: ApiException) {
            null
        }
    }

    internal fun publish(metrics: Metrics) {
        _latest.value = metrics
        _history.value = (_history.value + metrics).takeLast(historySize)
    }

    private fun dropped(webSocket: WebSocket) {
        if (webSocket !== socket) return
        disconnected()
        if (!keepOpen()) return
        val backoff = retryDelayMs
        retryDelayMs = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        retry?.cancel()
        retry = scope.launch {
            delay(backoff)
            if (keepOpen()) connect()
        }
    }

    private fun disconnected() {
        _connected.value = false
        socket = null
    }

    fun stop() {
        keepOpen = { false }
        retry?.cancel()
        retry = null
        retryDelayMs = RETRY_FIRST_MS
        socket?.close(1000, null)
        disconnected()
    }

    companion object {
        const val RETRY_FIRST_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
