package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The `/metrics` WebSocket: the server pushes a snapshot every 2 seconds. Keeps a short history for charts. */
class MetricsFeed(private val client: FaceFusionClient, private val historySize: Int = 60) {
    private val _latest = MutableStateFlow<Metrics?>(null)
    val latest: StateFlow<Metrics?> = _latest.asStateFlow()

    private val _history = MutableStateFlow<List<Metrics>>(emptyList())
    val history: StateFlow<List<Metrics>> = _history.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var socket: WebSocket? = null

    fun start() {
        if (socket != null) return
        socket = client.openSocket("/metrics", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { Metrics.parse(FaceFusionClient.json.parseToJsonElement(text).jsonObject) }.getOrNull()?.let(::publish)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected()
        })
    }

    internal fun publish(metrics: Metrics) {
        _latest.value = metrics
        _history.value = (_history.value + metrics).takeLast(historySize)
    }

    private fun disconnected() {
        _connected.value = false
        socket = null
    }

    fun stop() {
        socket?.close(1000, null)
        disconnected()
    }
}
