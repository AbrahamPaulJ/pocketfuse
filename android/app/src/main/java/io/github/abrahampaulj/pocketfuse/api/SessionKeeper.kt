package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    data object Closed : SessionState
    data object Opening : SessionState
    /** [generation] increases with every new session: asset and job ids from an older one are gone. */
    data class Open(val server: ServerInfo, val generation: Int) : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * Keeps one FaceFusion session alive. A session lives 10 minutes and both tokens share that expiry, so
 * the keeper rotates them well before it ([REFRESH_EVERY_MS]). When the server says the session is gone
 * (401 or 426) the keeper never retries the call that failed: it reports the loss, because the server
 * has already dropped the session's assets and jobs, and opens a new session only when asked.
 */
class SessionKeeper(
    val client: FaceFusionClient,
    private val apiKey: String?,
    private val scope: CoroutineScope,
    private val refreshEveryMs: Long = REFRESH_EVERY_MS,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Closed)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var refresher: Job? = null
    private var generation = 0

    suspend fun open(): SessionState = mutex.withLock {
        if (_state.value is SessionState.Open) return _state.value
        _state.value = SessionState.Opening
        _state.value = try {
            val server = client.root()
            client.openSession(apiKey)
            generation += 1
            startRefreshing()
            SessionState.Open(server, generation)
        } catch (exception: ApiException) {
            SessionState.Failed(exception.userMessage)
        } catch (exception: UnreachableException) {
            SessionState.Failed(exception.userMessage)
        }
        _state.value
    }

    /** Runs [block] against the open session; marks the session lost when the server says so. */
    suspend fun <T> call(block: suspend FaceFusionClient.() -> T): T {
        try {
            return client.block()
        } catch (exception: ApiException) {
            if (exception.sessionLost) markLost(exception.userMessage)
            throw exception
        }
    }

    suspend fun refreshNow() {
        try {
            client.refreshSession()
        } catch (exception: ApiException) {
            if (exception.sessionLost) markLost(exception.userMessage)
        } catch (_: UnreachableException) {
            // The next tick tries again; the session survives a short network drop.
        }
    }

    suspend fun close() {
        refresher?.cancel()
        refresher = null
        runCatching { client.closeSession() }
        _state.value = SessionState.Closed
    }

    private fun startRefreshing() {
        refresher?.cancel()
        refresher = scope.launch {
            while (isActive) {
                delay(refreshEveryMs)
                refreshNow()
            }
        }
    }

    private fun markLost(message: String) {
        refresher?.cancel()
        refresher = null
        client.tokens = null
        _state.value = SessionState.Failed(message)
    }

    companion object {
        const val REFRESH_EVERY_MS = 7 * 60 * 1000L
    }
}
