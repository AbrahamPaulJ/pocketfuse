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
 * Keeps one FaceFusion session alive. On `v4-challenge` both tokens share one 10 minute expiry and the
 * server deletes the session the moment it passes, so the keeper rotates them well before it
 * ([REFRESH_EVERY_MS]). On `v4` since 647be8b the access token lasts half the session and the refresh token
 * the whole of it, so a call turned away with 401 is answered the standard way: refresh, then send the call
 * once more. The phone's clock is never compared with the server's `expires_at`: they can differ. When the
 * refresh fails too, the session is gone: the keeper reports the loss and opens a new session only when
 * asked. A 401 for an access token the keeper itself rotated away while the call was in flight is sent
 * again with the new token, without a second refresh.
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
    /** Held while tokens rotate and while a 401 or 426 is judged, so a rotation in progress never reads as a lost session. */
    private val refreshLock = Mutex()
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
    suspend fun <T> call(block: suspend FaceFusionClient.() -> T): T = attempt(block, mayRepeat = true)

    private suspend fun <T> attempt(block: suspend FaceFusionClient.() -> T, mayRepeat: Boolean): T {
        val session = _state.value
        val sent = client.tokens?.accessToken
        try {
            return client.block()
        } catch (exception: ApiException) {
            if (!exception.sessionLost) throw exception
            val sendAgain = refreshLock.withLock { settle(exception, session, sent, mayRepeat) }
            if (!sendAgain) throw exception
        }
        // The server turned the old token away before any handler ran, so even a POST is safe to send again.
        return attempt(block, mayRepeat = false)
    }

    /**
     * Judges a 401 or 426 once any refresh under way has finished. True when the call only carried an
     * access token the keeper has since rotated away, or when a refresh renews the session now; otherwise
     * the session is marked lost. A call whose
     * session is no longer the open one (closed, already lost, being reopened or replaced) changes nothing:
     * marking it lost then would wipe the tokens of a session that [open] is setting up.
     */
    private suspend fun settle(exception: ApiException, session: SessionState, sent: String?, mayRepeat: Boolean): Boolean {
        val now = _state.value
        if (now !is SessionState.Open || now != session) return false
        val current = client.tokens?.accessToken
        if (mayRepeat && exception.status == 401 && sent != null && current != null && current != sent) return true
        if (mayRepeat && sent != null && current == sent && renew()) return true
        markLost(exception.userMessage)
        return false
    }

    /** Refreshes after the server turned the access token away; false when the refresh token is refused too. */
    private suspend fun renew(): Boolean = try {
        client.refreshSession()
        true
    } catch (_: ApiException) {
        false
    } catch (_: UnreachableException) {
        false
    }

    /**
     * Rotates both tokens. A refresh already in flight is joined, never repeated: both would carry the same
     * refresh token, and the server accepts it only once.
     */
    suspend fun refreshNow() {
        if (!refreshLock.tryLock()) {
            refreshLock.withLock { }
            return
        }
        try {
            if (_state.value !is SessionState.Open) return
            client.refreshSession()
        } catch (exception: ApiException) {
            if (exception.sessionLost) markLost(exception.userMessage)
        } catch (_: UnreachableException) {
            // The next tick tries again; the session survives a short network drop.
        } finally {
            refreshLock.unlock()
        }
    }

    suspend fun close() {
        refresher?.cancel()
        refresher = null
        refreshLock.withLock {
            runCatching { client.closeSession() }
            _state.value = SessionState.Closed
        }
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
        const val REFRESH_EVERY_MS = 3 * 60 * 1000L
    }
}
