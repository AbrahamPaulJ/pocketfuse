package io.github.abrahampaulj.pocketfuse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.SessionInfo
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.Workspace
import io.github.abrahampaulj.pocketfuse.pair.AddressKind
import io.github.abrahampaulj.pocketfuse.pair.classify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val JOB_STATUSES = listOf("drafted", "queued", "completed", "failed")

/** The PC at a glance: live load from /metrics, the session, the jobs the server holds, and security. */
@Composable
fun ServerScreen(connection: Connection, onForget: () -> Unit, workspace: Workspace? = null) {
    val scope = rememberCoroutineScope()
    val session by connection.session.collectAsState()
    val latest by connection.metrics.latest.collectAsState()
    val history by connection.metrics.history.collectAsState()
    val live by connection.metrics.connected.collectAsState()
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var jobs by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        if (session !is SessionState.Open) return
        info = runCatching { connection.keeper.call { session() } }.getOrNull()
        jobs = JOB_STATUSES.associateWith { status -> runCatching { connection.keeper.call { jobIds(status) } }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(session) {
        while (true) {
            reload()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section(stringResource(R.string.server_load), trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (live) Ok else Warn)
                HGap(6.dp)
                Hint(stringResource(if (live) R.string.server_live else R.string.server_waiting))
            }
        }) {
            val metrics = latest
            if (metrics == null) Hint(stringResource(R.string.server_no_metrics))
            metrics?.gpus?.forEach { gpu ->
                Text(gpu.name, style = MaterialTheme.typography.titleMedium)
                Meter(stringResource(R.string.server_gpu), gpu.utilization?.toString() ?: "-", gpu.utilization?.value?.toFloat()?.div(100f))
                Sparkline(history.mapNotNull { it.gpus.firstOrNull()?.utilization?.value }, Modifier.fillMaxWidth().height(40.dp))
                val used = gpu.memoryUsed?.value
                val total = gpu.memoryTotal?.value
                Meter(stringResource(R.string.server_vram), "${gpu.memoryUsed ?: "-"} / ${gpu.memoryTotal ?: "-"}", if (used != null && total != null && total > 0) (used / total).toFloat() else null)
                gpu.temperature?.let { Hint(stringResource(R.string.server_temperature, it.toString())) }
            }
            metrics?.let {
                Meter(stringResource(R.string.server_cpu), it.processorUtilization?.toString() ?: "-", it.processorUtilization?.value?.toFloat()?.div(100f))
                Meter(stringResource(R.string.server_memory), it.memoryUtilization?.toString() ?: "-", it.memoryUtilization?.value?.toFloat()?.div(100f))
                it.diskFree?.let { free -> Hint(stringResource(R.string.server_disk_free, free.toString())) }
            }
        }

        workspace?.let { BenchmarkCard(connection, it) }

        Section(stringResource(R.string.server_session)) {
            val state = session
            Text(
                when (state) {
                    is SessionState.Open -> "${state.server.name} ${state.server.version}  ·  ${connection.config.label}"
                    is SessionState.Failed -> state.message
                    SessionState.Opening -> stringResource(R.string.server_connecting)
                    SessionState.Closed -> stringResource(R.string.server_closed)
                },
                color = if (state is SessionState.Failed) Bad else MaterialTheme.colorScheme.onSurface,
            )
            info?.let { Hint(stringResource(R.string.server_expires, it.expiresAt.substringAfter('T').substringBefore('.'))) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state is SessionState.Open) {
                    OutlinedButton(onClick = { scope.launch { connection.keeper.refreshNow(); reload() } }) { Text(stringResource(R.string.server_refresh)) }
                } else {
                    OutlinedButton(onClick = { scope.launch { connection.ensureOpen() } }) { Text(stringResource(R.string.server_reconnect)) }
                }
            }
        }

        Section(stringResource(R.string.server_jobs)) {
            JOB_STATUSES.forEach { status ->
                val ids = jobs[status].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, modifier = Modifier.weight(1f))
                    Text(ids.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ids.takeLast(3).forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(id, Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                message = try {
                                    connection.keeper.call { deleteJob(id) }
                                    null
                                } catch (exception: ApiException) {
                                    exception.userMessage
                                }
                                reload()
                            }
                        }) { Text(stringResource(R.string.results_delete)) }
                    }
                }
            }
            message?.let { Hint(it, color = Bad) }
        }

        Section(stringResource(R.string.server_security)) {
            val kind = classify(connection.config.host)
            Hint(
                when (kind) {
                    AddressKind.Tailnet -> stringResource(R.string.server_sec_tailnet)
                    AddressKind.Lan, AddressKind.Loopback -> stringResource(R.string.server_sec_lan)
                    AddressKind.Name -> stringResource(R.string.server_sec_name)
                    AddressKind.Public -> stringResource(R.string.server_sec_public)
                },
                color = if (kind == AddressKind.Public) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Hint(
                stringResource(if (connection.config.apiKey != null) R.string.server_sec_key else R.string.server_sec_no_key),
                color = if (connection.config.apiKey != null) MaterialTheme.colorScheme.onSurfaceVariant else Warn,
            )
            TextButton(onClick = onForget) { Text(stringResource(R.string.server_forget)) }
        }
        Gap(24.dp)
    }
}
