package io.github.abrahampaulj.pocketfuse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.StreamClosedException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.media.toJpeg
import io.github.abrahampaulj.pocketfuse.puppet.EditorPose
import io.github.abrahampaulj.pocketfuse.puppet.FaceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Face puppet: the front camera reads your head pose and expression on the phone (MediaPipe), and those
 * 14 numbers drive FaceFusion's face_editor on a portrait, frame after frame. Only the numbers and the
 * portrait go to the PC; your camera image never leaves the phone.
 */
@Composable
fun PuppetScreen(app: PocketfuseApp, connection: Connection) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tracker = remember { FaceTracker(context.applicationContext) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var portrait by remember { mutableStateOf<Uri?>(null) }
    var portraitJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var output by remember { mutableStateOf<ByteArray?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var fps by remember { mutableFloatStateOf(0f) }
    var mirror by remember { mutableStateOf(tracker.mapper.mirror) }
    var intensity by remember { mutableFloatStateOf(1f) }
    val faceVisible by tracker.faceVisible.collectAsState()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            portrait = uri
            output = null
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) running = true else status = context.getString(R.string.live_camera_denied)
    }

    LaunchedEffect(portrait) {
        portraitJpeg = portrait?.let { uri -> withContext(Dispatchers.IO) { MediaInspector.decodeScaled(context, uri, 512)?.toJpeg() } }
    }

    DisposableEffect(running) {
        if (running) tracker.start(owner, previewView)
        onDispose { tracker.stop() }
    }

    LaunchedEffect(running, portraitJpeg) {
        val frame = portraitJpeg
        if (!running || frame == null) return@LaunchedEffect
        val stream = StreamPreview(context, connection) { status = it }
        val base = mapOf(
            "processors" to JsonArray(listOf(JsonPrimitive("face_editor"))),
            "face_selector_mode" to JsonPrimitive("one"),
        )
        // The editor needs no source face, but warming its models needs an image with a face: the portrait.
        val portraitMedia = portrait?.let { withContext(Dispatchers.IO) { MediaInspector.inspect(context, it) } }
        val failure = stream.prepare(listOfNotNull(portraitMedia)) ?: stream.apply(base + EditorPose().toStateBody())
        if (failure != null) {
            status = failure.message
            running = false
            return@LaunchedEffect
        }
        status = null
        var sent: EditorPose? = EditorPose()
        var fresh = false
        var frames = 0
        var windowStart = System.currentTimeMillis()
        while (isActive && running) {
            val pose = tracker.pose.value
            if (pose == null) {
                delay(50)
                continue
            }
            val changes = pose.changedSince(sent)
            if (changes.isEmpty() && fresh) {
                // The frame on screen already shows this pose (still face, face lost, app in the background):
                // wait for the next real change instead of asking the PC to render the same frame again.
                val shown = sent
                tracker.pose.first { it != null && it.changedSince(shown).isNotEmpty() }
                frames = 0
                windowStart = System.currentTimeMillis()
                continue
            }
            try {
                if (changes.isNotEmpty()) {
                    connection.applyState(changes)
                    sent = pose
                }
                output = connection.frames.process(frame)
                fresh = true
            } catch (exception: ApiException) {
                status = exception.userMessage
                running = false
            } catch (exception: UnreachableException) {
                status = exception.userMessage
                running = false
            } catch (exception: StreamClosedException) {
                status = exception.userMessage
                running = false
            }
            frames += 1
            val elapsed = System.currentTimeMillis() - windowStart
            if (elapsed >= 2000) {
                fps = frames * 1000f / elapsed
                frames = 0
                windowStart = System.currentTimeMillis()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
            .background(if (portraitJpeg == null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Black)
            .clickable(enabled = !running) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            when {
                output != null -> BytesImage(output, Modifier.fillMaxSize())
                portraitJpeg != null -> BytesImage(portraitJpeg, Modifier.fillMaxSize())
                else -> EmptyState(R.drawable.ic_tab_puppet, stringResource(R.string.puppet_pick_title), stringResource(R.string.puppet_pick), Modifier.align(Alignment.Center))
            }
            if (output != null) AiLabel(Modifier.align(Alignment.TopStart).padding(12.dp))
            if (running) {
                AndroidView({ previewView }, Modifier.align(Alignment.BottomEnd).padding(12.dp).width(96.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)))
                Badge(
                    if (faceVisible) stringResource(R.string.puppet_fps, fps) else stringResource(R.string.puppet_no_face),
                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                OutlinedButton(onClick = { running = false }) { Text(stringResource(R.string.live_stop)) }
                OutlinedButton(onClick = tracker::calibrateNow) { Text(stringResource(R.string.puppet_calibrate)) }
            } else {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) running = true
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    enabled = portraitJpeg != null,
                ) { Text(stringResource(R.string.puppet_start)) }
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.puppet_change))
                }
            }
        }
        FilterChip(selected = mirror, onClick = {
            mirror = !mirror
            tracker.mapper.mirror = mirror
        }, label = { Text(stringResource(R.string.puppet_mirror)) })
        Column {
            Hint(stringResource(R.string.puppet_intensity, intensity))
            Slider(value = intensity, onValueChange = {
                intensity = it
                tracker.mapper.intensity = it.toDouble()
            }, valueRange = 0.25f..2f)
        }
        status?.let { Hint(it, color = if (running) Warn else Bad) }
        Hint(stringResource(R.string.puppet_note))
    }
}
