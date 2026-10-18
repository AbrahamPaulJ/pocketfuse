package io.github.abrahampaulj.pocketfuse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        PairScreen(failure = (session as SessionState.Failed).message, onPaired = { app.pair(it) })
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
                    if (overlay != null) TextButton(onClick = { overlay = null }) { Text(stringResource(R.string.back)) }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when (session) {
                                is SessionState.Open -> Ok
                                SessionState.Opening -> Warn
                                else -> Bad
                            },
                        )
                        HGap()
                        Text(connection.config.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (overlay == null) TextButton(onClick = { overlay = Overlay.Results }) { Text(stringResource(R.string.results_title)) }
                    }
                },
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
                    Tab.Live -> LiveScreen(app, connection)
                    Tab.Lab -> LabScreen(app, connection)
                    Tab.Puppet -> PuppetScreen(app, connection)
                    Tab.Server -> ServerScreen(connection, onForget = app::unpair)
                }
            }
        }
    }
}
