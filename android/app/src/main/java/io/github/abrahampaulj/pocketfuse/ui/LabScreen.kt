package io.github.abrahampaulj.pocketfuse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.asText
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** One axis the lab can compare, and how to pick a handful of values from the server's choices. */
enum class LabAxis(val key: String, val label: Int, val needsEnhancer: Boolean = false, val samples: Int = 8, val downloads: Boolean = false) {
    PixelBoost("face_swapper_pixel_boost", R.string.lab_axis_boost),
    // Every swap model the PC has not used yet is downloaded first (up to 1.6 GB each): keep the row short.
    SwapModel("face_swapper_model", R.string.lab_axis_model, samples = 4, downloads = true),
    SwapWeight("face_swapper_weight", R.string.lab_axis_weight, samples = 5),
    EnhancerModel("face_enhancer_model", R.string.lab_axis_enhancer, needsEnhancer = true, samples = 4, downloads = true),
    EnhancerBlend("face_enhancer_blend", R.string.lab_axis_blend, needsEnhancer = true, samples = 5);

    fun values(capabilities: Capabilities): List<JsonElement> {
        val choices = capabilities[key]?.choices.orEmpty()
        if (choices.size <= samples) return choices
        return (0 until samples).map { choices[it * (choices.size - 1) / (samples - 1)] }
    }
}

private data class LabTile(val value: JsonElement, val result: FrameResult?)

/**
 * The settings lab: one frame of the target, run through the PC once per value of one option, shown side
 * by side. The CLI can only try one setting per run; here the whole row comes back in seconds over the
 * `/stream` socket, and the winner is one tap away from becoming the swap's setting.
 */
@Composable
fun LabScreen(app: PocketfuseApp, connection: Connection, onGoToSwap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capabilities by connection.capabilities.collectAsState()
    val sources by app.workspace.sources.collectAsState()
    val target by app.workspace.target.collectAsState()
    val options by app.workspace.options.collectAsState()
    var axis by remember { mutableStateOf(LabAxis.PixelBoost) }
    val tiles = remember { mutableStateListOf<LabTile>() }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun run() {
        val caps = capabilities ?: return
        val media = target ?: return
        running = true
        tiles.clear()
        val values = axis.values(caps)
        tiles.addAll(values.map { LabTile(it, null) })
        scope.launch {
            val frame = MediaInspector.frameJpeg(context, media, media.durationMs / 2, 512)
            if (frame == null) {
                status = context.getString(R.string.swap_no_frame)
                running = false
                return@launch
            }
            val stream = StreamPreview(context, connection) { status = it }
            stream.prepare(sources)?.let {
                status = it.message
                running = false
                return@launch
            }
            val base: SwapOptions = if (axis.needsEnhancer) options.copy(enhance = true) else options
            values.forEachIndexed { index, value ->
                val state = base.with(axis.key, value).toState(forStream = true)
                tiles[index] = LabTile(value, stream.process(frame, state))
                status = null
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Hint(stringResource(R.string.lab_intro))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabAxis.entries.forEach { entry ->
                FilterChip(selected = axis == entry, onClick = { if (!running) axis = entry }, label = { Text(stringResource(entry.label)) })
            }
        }
        if (axis.downloads) Hint(stringResource(R.string.lab_downloads), color = Warn)
        val ready = capabilities != null && target != null && sources.isNotEmpty() && !running
        if (target == null || sources.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                EmptyState(R.drawable.ic_tab_lab, stringResource(R.string.lab_need_inputs_title), stringResource(R.string.lab_need_inputs), action = stringResource(R.string.go_to_swap), onAction = onGoToSwap)
            }
        } else {
            Button(onClick = ::run, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(if (running) R.string.lab_running else R.string.lab_run))
            }
        }
        status?.let { Hint(it, color = Warn) }

        val current = options.overrides[axis.key] ?: capabilities?.get(axis.key)?.default
        // Tiles take a wide target's shape (no letterbox); portrait targets get square tiles cropped to the centre.
        val tileAspect = target?.takeIf { it.width > 0 && it.height > 0 }?.let { (it.width.toFloat() / it.height).coerceIn(1f, 2f) } ?: 1f
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    val chosen = tile.value == current
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(2.dp, if (chosen) Violet else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable(enabled = tile.result is FrameResult.Ready) { app.workspace.updateOptions { it.with(axis.key, tile.value) } },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(tileAspect)) {
                            when (val result = tile.result) {
                                is FrameResult.Ready -> {
                                    BytesImage(result.jpeg, Modifier.fillMaxSize(), ContentScale.Crop)
                                    AiLabel(Modifier.align(Alignment.TopStart).padding(6.dp))
                                }
                                is FrameResult.Failed -> Hint(result.message, Modifier.align(Alignment.Center).padding(8.dp), color = Bad)
                                null -> Pending(Modifier.fillMaxSize())
                            }
                        }
                        Text(tile.value.asText(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                        (tile.result as? FrameResult.Ready)?.let { Hint(stringResource(R.string.lab_ms, it.millis), Modifier.padding(start = 12.dp)) }
                        Gap(10.dp)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (tiles.isNotEmpty() && !running) Hint(stringResource(R.string.lab_tap_to_use))
        Gap(24.dp)
    }
}
