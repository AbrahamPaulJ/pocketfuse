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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.live.RtcSession
import io.github.abrahampaulj.pocketfuse.live.RtcState
import io.github.abrahampaulj.pocketfuse.live.VideoInput
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The phone's camera, swapped on the PC in real time, over WebRTC. The PC's GPU does the work; the phone
 * only encodes its camera and decodes what comes back.
 */
@Composable
fun LiveScreen(app: PocketfuseApp, connection: Connection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by app.workspace.sources.collectAsState()
    val options by app.workspace.options.collectAsState()
    var front by remember { mutableStateOf(true) }
    var microphone by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }

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

    fun start() {
        scope.launch {
            setupError = null
            // The stream reads the selected sources and the processors from the session state.
            val prepared = StreamPreview(connection).prepare(sources)
            if (prepared is FrameResult.Failed) {
                setupError = prepared.message
                return@launch
            }
            runCatching { connection.applyState(options.toState(forStream = true)) }.onFailure { setupError = it.message }
            if (setupError == null) session.start(VideoInput.Camera(front), microphone)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) start() else setupError = context.getString(R.string.live_camera_denied)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            if (state == RtcState.Connected || state == RtcState.Connecting) {
                Renderer(session, local = false, mirror = false, Modifier.fillMaxSize())
                Renderer(session, local = true, mirror = front, Modifier.align(Alignment.BottomEnd).padding(12.dp).width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)))
                AiLabel(Modifier.align(Alignment.TopStart).padding(12.dp))
                Text(
                    if (state == RtcState.Connected) stringResource(R.string.live_fps, fps) else stringResource(R.string.live_connecting),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Hint(stringResource(if (sources.isEmpty()) R.string.live_need_face else R.string.live_idle), Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = front, onClick = {
                front = !front
                if (state == RtcState.Connected) session.switchCamera()
            }, label = { Text(stringResource(R.string.live_front)) })
            FilterChip(selected = microphone, enabled = state == RtcState.Idle || state is RtcState.Failed, onClick = { microphone = !microphone }, label = { Text(stringResource(R.string.live_microphone)) })
        }

        if (state == RtcState.Connected || state == RtcState.Connecting) {
            OutlinedButton(onClick = { scope.launch { session.stop() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.live_stop)) }
        } else {
            Button(
                onClick = {
                    val needed = buildList {
                        add(Manifest.permission.CAMERA)
                        if (microphone) add(Manifest.permission.RECORD_AUDIO)
                    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (needed.isEmpty()) start() else permissions.launch(needed.toTypedArray())
                },
                enabled = sources.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.live_start)) }
        }
        (state as? RtcState.Failed)?.let { Hint(it.message, color = Bad) }
        setupError?.let { Hint(it, color = Bad) }
        Hint(stringResource(R.string.live_note))
    }
}

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
