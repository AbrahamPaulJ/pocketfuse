package io.github.abrahampaulj.pocketfuse.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.FaceScope
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.SwapProgress
import io.github.abrahampaulj.pocketfuse.core.SwapRequest
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun SwapScreen(app: PocketfuseApp, connection: Connection, onOpenOptions: () -> Unit, onOpenResults: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspace = app.workspace
    val sources by workspace.sources.collectAsState()
    val target by workspace.target.collectAsState()
    val options by workspace.options.collectAsState()
    val progress by app.jobs.progress.collectAsState()
    val capabilities by connection.capabilities.collectAsState()

    var previewAtMs by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<FrameResult?>(null) }
    var before by remember { mutableStateOf<ByteArray?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewStatus by remember { mutableStateOf<String?>(null) }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(workspace::addSource) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            val media = workspace.setTarget(it)
            preview = null
            before = null
            previewAtMs = 0f
            val problem = media?.let { m -> MediaInspector.problems(m) { ext -> capabilities?.accepts(ext) ?: true } }
            if (problem != null) Toast.makeText(context, problem.message, Toast.LENGTH_LONG).show()
        }
    }

    fun runPreview(media: LocalMedia) {
        previewing = true
        scope.launch {
            val frame = MediaInspector.frameJpeg(context, media, previewAtMs.toLong(), 720)
            before = frame
            val stream = StreamPreview(context, connection) { previewStatus = it }
            preview = if (frame == null) FrameResult.Failed(context.getString(R.string.swap_no_frame))
            else stream.prepare(sources) ?: stream.process(frame, options.toState(forStream = true))
            previewStatus = null
            previewing = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section(stringResource(R.string.swap_faces)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sources, key = { it.uri.toString() }) { source ->
                    Box {
                        MediaThumb(source, Modifier.size(64.dp), circle = true)
                        Box(
                            Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { workspace.removeSource(source.uri) },
                            contentAlignment = Alignment.Center,
                        ) { Text("x", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                item {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { pickSource.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) { Text("+", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
            Hint(stringResource(if (sources.isEmpty()) R.string.swap_faces_empty else R.string.swap_faces_hint))
        }

        Section(stringResource(R.string.swap_target), trailing = {
            TextButton(onClick = { pickTarget.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                Text(stringResource(if (target == null) R.string.swap_pick else R.string.swap_change))
            }
        }) {
            val current = target
            if (current == null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { pickTarget.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    contentAlignment = Alignment.Center,
                ) { Hint(stringResource(R.string.swap_target_empty)) }
            } else {
                BeforeAfter(before = before, after = (preview as? FrameResult.Ready)?.jpeg, fallback = current)
                Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Hint(describeMedia(current))
                if (current.isVideo && current.durationMs > 0) {
                    Slider(value = previewAtMs, onValueChange = { previewAtMs = it }, valueRange = 0f..current.durationMs.toFloat())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { runPreview(current) }, enabled = !previewing && sources.isNotEmpty()) {
                        Text(stringResource(if (previewing) R.string.swap_previewing else R.string.swap_preview))
                    }
                    when (val result = preview) {
                        is FrameResult.Ready -> Hint(stringResource(R.string.swap_preview_time, result.millis))
                        is FrameResult.Failed -> Hint(result.message, color = Bad)
                        null -> Unit
                    }
                }
                previewStatus?.let { Hint(it) }
            }
        }

        Section(stringResource(R.string.swap_options), trailing = {
            TextButton(onClick = onOpenOptions) { Text(stringResource(R.string.swap_all_options)) }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = options.enhance, onClick = { workspace.updateOptions { it.copy(enhance = !it.enhance) } }, label = { Text(stringResource(R.string.swap_enhance)) })
                FilterChip(selected = options.restoreExpression, onClick = { workspace.updateOptions { it.copy(restoreExpression = !it.restoreExpression) } }, label = { Text(stringResource(R.string.swap_keep_expression)) })
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FaceScope.entries.forEachIndexed { index, scopeOption ->
                    SegmentedButton(
                        selected = options.faceScope == scopeOption,
                        onClick = { workspace.updateOptions { it.copy(faceScope = scopeOption) } },
                        shape = SegmentedButtonDefaults.itemShape(index, FaceScope.entries.size),
                    ) { Text(stringResource(if (scopeOption == FaceScope.MainPerson) R.string.swap_main_person else R.string.swap_everyone)) }
                }
            }
            capabilities?.let { caps ->
                ChoicePicker(
                    label = stringResource(R.string.swap_model),
                    choices = caps.choicesOf("face_swapper_model"),
                    selected = options.overrides["face_swapper_model"]?.let { (it as? JsonPrimitive)?.content } ?: caps["face_swapper_model"]?.default?.let { (it as? JsonPrimitive)?.content },
                    onSelect = { value -> workspace.updateOptions { it.with("face_swapper_model", JsonPrimitive(value)) } },
                )
            }
        }

        JobPanel(progress, onDismiss = app.jobs::dismiss, onOpenResults = onOpenResults)

        val ready = sources.isNotEmpty() && target != null && !app.jobs.isRunning
        Button(
            onClick = {
                val current = target ?: return@Button
                val problem = MediaInspector.problems(current) { ext -> capabilities?.accepts(ext) ?: true }
                if (problem != null) Toast.makeText(context, problem.message, Toast.LENGTH_LONG).show()
                else app.jobs.start(SwapRequest(sources, current, options))
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.swap_run)) }
        Gap(24.dp)
    }
}

@Composable
fun ChoicePicker(label: String, choices: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(selected ?: "-", maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = {
                        open = false
                        onSelect(choice)
                    })
                }
            }
        }
    }
}

/** The target frame with the processed preview over it; drag across to compare. */
@Composable
private fun BeforeAfter(before: ByteArray?, after: ByteArray?, fallback: LocalMedia) {
    var split by remember { mutableFloatStateOf(0.5f) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        if (before == null) MediaThumb(fallback, Modifier.fillMaxSize(), maxSide = 720)
        else BytesImage(before, Modifier.fillMaxSize())
        if (after != null) {
            BytesImage(after, Modifier.fillMaxSize().clip(RevealShape(split)))
            AiLabel(Modifier.align(Alignment.TopStart).padding(8.dp))
            Slider(value = split, onValueChange = { split = it }, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun JobPanel(progress: SwapProgress?, onDismiss: () -> Unit, onOpenResults: () -> Unit) {
    if (progress == null) return
    Section(stringResource(R.string.swap_job)) {
        when (progress) {
            is SwapProgress.Uploading -> {
                Hint(stringResource(R.string.job_uploading, progress.what))
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            SwapProgress.Preparing -> {
                Hint(stringResource(R.string.job_preparing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is SwapProgress.Processing -> {
                Hint(stringResource(R.string.job_processing, progress.elapsedSeconds))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is SwapProgress.Downloading -> {
                Hint(stringResource(R.string.job_downloading))
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            is SwapProgress.Done -> {
                Text(stringResource(R.string.job_done, progress.seconds), color = Ok)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOpenResults) { Text(stringResource(R.string.swap_open_result)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                }
            }
            is SwapProgress.Failed -> {
                Text(progress.message, color = Bad)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

private fun describeMedia(media: LocalMedia): String = buildList {
    if (media.width > 0) add("${media.width}x${media.height}")
    if (media.isVideo) add("%.1f s".format(media.durationMs / 1000.0))
    if (media.size > 0) add("%.1f MB".format(media.size / (1024.0 * 1024.0)))
}.joinToString("  ·  ")
