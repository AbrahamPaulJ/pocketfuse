package io.github.abrahampaulj.pocketfuse.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.StreamClosedException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.Workspace
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** What one benchmark run measured. */
data class BenchmarkResult(val frames: Int, val medianMs: Long, val width: Int, val model: String, val device: String) {
    val framesPerSecond: Double get() = if (medianMs > 0) 1000.0 / medianMs else 0.0
}

/**
 * Times your own swap on your own PC: one frame of the target, run [FRAMES] times through the `/stream`
 * socket with the current options after one warm-up frame. The median is what one frame costs on this
 * GPU with these settings, network included, which is what a preview or the Lab will feel like.
 */
@Composable
fun BenchmarkCard(connection: Connection, workspace: Workspace) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by workspace.sources.collectAsState()
    val target by workspace.target.collectAsState()
    val options by workspace.options.collectAsState()
    val latest by connection.metrics.latest.collectAsState()
    val capabilities by connection.capabilities.collectAsState()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun run() {
        val media = target ?: return
        running = true
        failure = null
        progress = 0f
        scope.launch {
            try {
                val frame = MediaInspector.frameJpeg(context, media, media.durationMs / 2, FRAME_SIDE)
                val stream = StreamPreview(context, connection) { failure = it }
                val warm = if (frame == null) FrameResult.Failed(context.getString(R.string.swap_no_frame))
                else stream.prepare(sources) ?: stream.process(frame, options.toState(forStream = true))
                if (warm is FrameResult.Failed || frame == null) {
                    failure = (warm as? FrameResult.Failed)?.message
                    return@launch
                }
                failure = null
                val times = (1..FRAMES).map { index ->
                    val started = System.nanoTime()
                    connection.frames.process(frame)
                    progress = index / FRAMES.toFloat()
                    (System.nanoTime() - started) / 1_000_000
                }
                val model = (options.overrides["face_swapper_model"] as? JsonPrimitive)?.content
                    ?: (capabilities?.get("face_swapper_model")?.default as? JsonPrimitive)?.content ?: "-"
                result = BenchmarkResult(FRAMES, times.sorted()[FRAMES / 2], FRAME_SIDE, model, latest?.gpus?.firstOrNull()?.name ?: "your PC")
            } catch (exception: ApiException) {
                failure = exception.userMessage
            } catch (exception: UnreachableException) {
                failure = exception.userMessage
            } catch (exception: StreamClosedException) {
                failure = exception.userMessage
            } finally {
                running = false
            }
        }
    }

    Section(stringResource(R.string.bench_title)) {
        Hint(stringResource(R.string.bench_intro, FRAMES))
        val ready = target != null && sources.isNotEmpty() && !running
        if (target == null || sources.isEmpty()) Hint(stringResource(R.string.bench_need_inputs))
        if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        result?.let { measured ->
            Text(stringResource(R.string.bench_fps, measured.framesPerSecond), style = MaterialTheme.typography.headlineSmall)
            Hint(stringResource(R.string.bench_detail, measured.medianMs, measured.frames, measured.width, measured.model, measured.device))
        }
        failure?.let { Hint(it, color = Bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = ::run, enabled = ready) {
                Text(stringResource(if (running) R.string.bench_running else R.string.bench_run))
            }
            result?.let { measured ->
                OutlinedButton(onClick = {
                    val text = context.getString(R.string.bench_share_text, measured.framesPerSecond, measured.medianMs, measured.width, measured.model, measured.device)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.results_share)) }
            }
        }
    }
}

private const val FRAMES = 12
private const val FRAME_SIDE = 720
