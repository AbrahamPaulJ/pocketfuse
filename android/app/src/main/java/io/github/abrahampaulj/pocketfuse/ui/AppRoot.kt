package io.github.abrahampaulj.pocketfuse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.core.Connection

enum class Tab(val label: Int, val icon: Int) {
    Swap(R.string.tab_swap, R.drawable.ic_tab_swap),
    Live(R.string.tab_live, R.drawable.ic_tab_live),
    Lab(R.string.tab_lab, R.drawable.ic_tab_lab),
    Puppet(R.string.tab_puppet, R.drawable.ic_tab_puppet),
    Server(R.string.tab_server, R.drawable.ic_tab_server),
}

enum class Overlay { Options, Results }

@Composable
fun AppRoot(app: PocketfuseApp, initialTab: Tab = Tab.Swap) {
    val connection by app.connection.collectAsState()
    val current = connection
    if (current == null) {
        PairScreen(failure = null, onPaired = { app.pair(it) })
        return
    }
    val session by current.session.collectAsState()
    if (session is SessionState.Failed && current.capabilities.value == null) {
        PairScreen(
            failure = (session as SessionState.Failed).message,
            onPaired = { app.pair(it) },
            saved = current.config,
            onRetry = { app.pair(current.config, remember = false) },
        )
        return
    }
    Connected(app, current, session, initialTab)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Connected(app: PocketfuseApp, connection: Connection, session: SessionState, initialTab: Tab) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    val capabilities by connection.capabilities.collectAsState()
    // Saved options this server would refuse with a 400 (another server, or an older app) are dropped once it answers.
    LaunchedEffect(capabilities) { capabilities?.let { caps -> app.workspace.updateOptions { it.validFor(caps) } } }
    BackHandler(enabled = overlay != null) { overlay = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (overlay) {
                            Overlay.Options -> stringResource(R.string.swap_all_options)
                            Overlay.Results -> stringResource(R.string.results_title)
                            null -> stringResource(tab.label)
                        },
                    )
                },
                navigationIcon = {
                    if (overlay != null) IconButton(onClick = { overlay = null }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                },
                actions = {
                    ConnectionPill(session, onClick = {
                        overlay = null
                        tab = Tab.Server
                    })
                    if (overlay == null) IconButton(onClick = { overlay = Overlay.Results }) {
                        Icon(painterResource(R.drawable.ic_results), stringResource(R.string.results_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (overlay == null) NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(painterResource(entry.icon), null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (overlay) {
                Overlay.Options -> OptionsScreen(capabilities, app.workspace)
                Overlay.Results -> ResultsScreen(app.jobs)
                null -> when (tab) {
                    Tab.Swap -> SwapScreen(app, connection, onOpenOptions = { overlay = Overlay.Options }, onOpenResults = { overlay = Overlay.Results })
                    Tab.Live -> LiveScreen(app, connection, onGoToSwap = { tab = Tab.Swap })
                    Tab.Lab -> LabScreen(app, connection, onGoToSwap = { tab = Tab.Swap })
                    Tab.Puppet -> PuppetScreen(app, connection)
                    Tab.Server -> {
                        // Read so the PC tab recomposes when a swap starts or ends.
                        val progress by app.jobs.progress.collectAsState()
                        ServerScreen(connection, onForget = app::unpair, workspace = app.workspace, jobsRunning = progress != null && app.jobs.isRunning)
                    }
                }
            }
        }
    }
}

/** Connection state at a glance; tapping it opens the PC tab with the details. */
@Composable
private fun ConnectionPill(session: SessionState, onClick: () -> Unit) {
    val (color, label) = when (session) {
        is SessionState.Open -> Ok to R.string.connection_ok
        SessionState.Opening -> Warn to R.string.connection_opening
        else -> Bad to R.string.connection_lost
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        HGap(8.dp)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}
