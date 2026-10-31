package io.github.abrahampaulj.pocketfuse.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.live.RtcSession
import io.github.abrahampaulj.pocketfuse.live.RtcState
import io.github.abrahampaulj.pocketfuse.live.VideoInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The phone's camera, swapped on the PC in real time, over WebRTC. The PC's GPU does the work; the phone
 * only encodes its camera and decodes what comes back. Video never goes frame by frame over the `/stream`
 * WebSocket: the developer guide reserves that socket for single images.
 */
@Composable
fun LiveScreen(app: PocketfuseApp, connection: Connection, onGoToSwap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by app.workspace.sources.collectAsState()
    val options by app.workspace.options.collectAsState()
    var front by remember { mutableStateOf(true) }
    var microphone by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }

    val session = remember(connection) {
        RtcSession(
            context.applicationContext,
            postOffer = { offer -> connection.keeper.call { postStreamOffer(offer) } },
            deleteStream = { connection.keeper.call { deleteStream() } },
        )
    }
    DisposableEffect(session) {
        onDispose {
            app.scope.launch {
                session.stop()
                session.release()
            }
        }
    }
    val state by session.state.collectAsState()
    val fps by session.receivedFps.collectAsState()

    // Connected but nothing comes back: the media path (UDP) is blocked even though signalling worked.
    var noVideo by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        noVideo = false
        if (state == RtcState.Connected) {
            delay(NO_VIDEO_AFTER_MS)
            noVideo = session.receivedFps.value == 0f && session.state.value == RtcState.Connected
        }
    }

    fun start() {
        scope.launch {
            setupError = null
            preparing = true
            // The stream reads the selected sources and the processors from the session state.
            val stream = StreamPreview(context, connection) { setupError = it }
            val failure = stream.prepare(sources) ?: stream.apply(options.toState(forStream = true))
            preparing = false
            setupError = failure?.message
            if (failure == null) session.start(VideoInput.Camera(front), microphone)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) start() else setupError = context.getString(R.string.live_camera_denied)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val live = state == RtcState.Connected || state == RtcState.Connecting
        if (live) {
            Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                Renderer(session, local = false, mirror = false, Modifier.fillMaxSize())
                Renderer(session, local = true, mirror = front, Modifier.align(Alignment.BottomEnd).padding(12.dp).width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)))
                AiLabel(Modifier.align(Alignment.TopStart).padding(12.dp))
                Badge(
                    if (state == RtcState.Connected) stringResource(R.string.live_fps, fps) else stringResource(R.string.live_connecting),
                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (sources.isEmpty()) {
                    EmptyState(R.drawable.ic_tab_live, stringResource(R.string.live_need_face_title), stringResource(R.string.live_need_face), action = stringResource(R.string.go_to_swap), onAction = onGoToSwap)
                } else {
                    EmptyState(R.drawable.ic_tab_live, stringResource(R.string.live_idle_title), stringResource(R.string.live_idle))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = front, onClick = {
                front = !front
                if (state == RtcState.Connected) session.switchCamera()
            }, label = { Text(stringResource(R.string.live_front)) })
            FilterChip(selected = microphone, enabled = !live, onClick = { microphone = !microphone }, label = { Text(stringResource(R.string.live_microphone)) })
        }

        if (live) {
            OutlinedButton(onClick = { scope.launch { session.stop() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.live_stop)) }
        } else {
            Button(
                onClick = {
                    val needed = buildList {
                        add(Manifest.permission.CAMERA)
                        if (microphone) add(Manifest.permission.RECORD_AUDIO)
                    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (needed.isEmpty()) start() else permissions.launch(needed.toTypedArray())
                },
                enabled = sources.isNotEmpty() && !preparing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(if (preparing) R.string.live_connecting else R.string.live_start)) }
        }
        (state as? RtcState.Failed)?.let { Hint(it.message, color = Bad) }
        setupError?.let { Hint(it, color = if (preparing) Warn else Bad) }
        if (noVideo) Hint(stringResource(R.string.live_no_video), color = Warn)
        Hint(stringResource(R.string.live_note))
    }
}

private const val NO_VIDEO_AFTER_MS = 10_000L

@Composable
private fun Renderer(session: RtcSession, local: Boolean, mirror: Boolean, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(session.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (local) {
                    setZOrderMediaOverlay(true)
                    session.attachLocal(this)
                } else {
                    session.attachRemote(this)
                }
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { renderer ->
            session.detach(renderer)
            renderer.release()
        },
        modifier = modifier,
    )
}
