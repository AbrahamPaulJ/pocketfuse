package io.github.abrahampaulj.pocketfuse

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.JobCenter
import io.github.abrahampaulj.pocketfuse.core.Workspace
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import io.github.abrahampaulj.pocketfuse.pair.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PocketfuseApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var serverStore: ServerStore
        private set
    lateinit var workspace: Workspace
        private set
    lateinit var jobs: JobCenter
        private set

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        serverStore = ServerStore(this)
        workspace = Workspace(this)
        jobs = JobCenter(this)
        createChannels()
        serverStore.load()?.let { pair(it, remember = false) }
    }

    private var connecting: Job? = null

    /**
     * Switches to [config], closing the previous server's session first. Pairing the server already in use
     * keeps its session: opening a second one would count twice against the server's session limit.
     */
    fun pair(config: ServerConfig, remember: Boolean = true) {
        if (remember) serverStore.save(config)
        val previous = _connection.value
        if (previous?.config == config) {
            if (previous.session.value is SessionState.Failed) connecting = scope.launch { previous.ensureOpen() }
            return
        }
        connecting?.cancel()
        val next = Connection(this, config, scope)
        _connection.value = next
        connecting = scope.launch {
            previous?.close()
            next.connect()
        }
    }

    fun unpair() {
        val previous = _connection.value ?: return
        connecting?.cancel()
        _connection.value = null
        serverStore.forget()
        scope.launch { previous.close() }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_JOBS, getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_JOBS = "jobs"
        const val CHANNEL_DONE = "done"
    }
}
