package io.github.abrahampaulj.pocketfuse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.core.content.ContextCompat
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.core.BatchState
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.FaceCheck
import io.github.abrahampaulj.pocketfuse.core.FaceScope
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.SwapProgress
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import io.github.abrahampaulj.pocketfuse.core.SwapRequest
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.media.VoiceRecorder
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
    val voice by workspace.voice.collectAsState()
    val batch by workspace.batch.collectAsState()
    val batchState by app.jobs.batch.collectAsState()
    val presets by workspace.presets.collectAsState()
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start()
            recording = true
        }
    }

    var previewAtMs by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<FrameResult?>(null) }
    var before by remember { mutableStateOf<ByteArray?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewStatus by remember { mutableStateOf<String?>(null) }
    val faceChecks = remember { mutableStateMapOf<String, FaceCheck>() }
    LaunchedEffect(sources, connection) {
        sources.filter { it.uri.toString() !in faceChecks }.forEach { source ->
            faceChecks[source.uri.toString()] = connection.detectFace(source)
        }
    }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { workspace.addSource(uri) }
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val media = workspace.setTarget(uri)
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
                        when (val check = faceChecks[source.uri.toString()]) {
                            is FaceCheck.Found -> BytesImage(check.crop, Modifier.size(64.dp).clip(CircleShape), androidx.compose.ui.layout.ContentScale.Crop)
                            else -> MediaThumb(source, Modifier.size(64.dp), circle = true)
                        }
                        if (faceChecks[source.uri.toString()] == FaceCheck.NoFace) {
                            Text(
                                stringResource(R.string.swap_no_face_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomCenter).background(Bad, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
                            )
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { workspace.removeSource(source.uri) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.swap_remove_face), Modifier.size(14.dp)) }
                    }
                }
                item {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.5.dp, Violet.copy(alpha = 0.6f), CircleShape)
                            .clickable { pickSource.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.swap_add_face), tint = Violet, modifier = Modifier.size(28.dp)) }
                }
            }
            if (sources.isEmpty()) {
                Text(stringResource(R.string.swap_faces_empty_title), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.swap_faces_empty))
            } else {
                Hint(stringResource(R.string.swap_faces_hint))
            }
            if (faceChecks.values.any { it == FaceCheck.NoFace }) Hint(stringResource(R.string.swap_no_face), color = Bad)
        }

        Section(stringResource(R.string.swap_target), trailing = {
            TextButton(onClick = { pickTarget.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                Text(stringResource(if (target == null) R.string.swap_pick else R.string.swap_change))
            }
        }) {
            val current = target
            if (current == null) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { pickTarget.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    contentAlignment = Alignment.Center,
                ) { EmptyState(R.drawable.ic_image, stringResource(R.string.swap_target_empty_title), stringResource(R.string.swap_target_empty)) }
            } else {
                if (batch.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.batch_title, batch.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = workspace::clearBatch) { Text(stringResource(R.string.batch_clear)) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(batch, key = { it.uri.toString() }) { media -> MediaThumb(media, Modifier.size(width = 72.dp, height = 72.dp)) }
                    }
                    Hint(stringResource(R.string.batch_hint))
                }
                BeforeAfter(before = before, after = (preview as? FrameResult.Ready)?.jpeg, fallback = current)
                Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Hint(describeMedia(current))
                if (current.isVideo && current.durationMs > 0) {
                    Column {
                        Hint(stringResource(R.string.swap_frame_at, previewAtMs / 1000f))
                        Slider(value = previewAtMs, onValueChange = { previewAtMs = it }, valueRange = 0f..current.durationMs.toFloat())
                    }
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
            Presets(presets, current = options, onApply = workspace::applyPreset, onDelete = workspace::deletePreset, onSave = workspace::savePreset)
        }

        Section(stringResource(R.string.voice_title)) {
            Hint(stringResource(R.string.voice_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Button(onClick = {
                        val file = recorder.stop()
                        recording = false
                        scope.launch {
                            workspace.setVoice(file?.let { android.net.Uri.fromFile(it) })
                            if (file != null) workspace.updateOptions { it.copy(lipSync = true) }
                        }
                    }) { Text(stringResource(R.string.voice_stop)) }
                    Text(stringResource(R.string.voice_recording), color = Bad)
                } else {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            recorder.start()
                            recording = true
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) { Text(stringResource(if (voice == null) R.string.voice_record else R.string.voice_record_again)) }
                    if (voice != null) {
                        FilterChip(selected = options.lipSync, onClick = { workspace.updateOptions { it.copy(lipSync = !it.lipSync) } }, label = { Text(stringResource(R.string.voice_use)) })
                    }
                }
            }
            voice?.let { Hint(stringResource(R.string.voice_ready, it.durationMs / 1000.0)) }
        }

        JobPanel(progress, batchState, onDismiss = app.jobs::dismiss, onOpenResults = onOpenResults, onStopRest = app.jobs::stopRest)

        val hasVoice = options.lipSync && voice != null
        val ready = (sources.isNotEmpty() || hasVoice) && target != null && !app.jobs.isRunning
        if ((sources.isEmpty() && !hasVoice) || target == null) Hint(stringResource(R.string.swap_not_ready), Modifier.fillMaxWidth())
        Button(
            onClick = {
                val current = target ?: return@Button
                val targets = batch.ifEmpty { listOf(current) }
                val problems = targets.mapNotNull { media -> MediaInspector.problems(media) { ext -> capabilities?.accepts(ext) ?: true } }
                problems.firstOrNull()?.let { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                val runnable = targets.filter { media -> MediaInspector.problems(media) { ext -> capabilities?.accepts(ext) ?: true } == null }
                app.jobs.startAll(runnable.map { SwapRequest(sources, it, options, voice.takeIf { hasVoice }) })
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                if (batch.size > 1) stringResource(R.string.swap_run_batch, batch.size) else stringResource(R.string.swap_run),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Gap(24.dp)
    }
}

@Composable
fun ChoicePicker(label: String?, choices: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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

/** The target frame with the processed preview over it; drag the divider to compare. */
@Composable
private fun BeforeAfter(before: ByteArray?, after: ByteArray?, fallback: LocalMedia) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(after != null) {
                if (after == null) return@pointerInput
                detectHorizontalDragGestures { change, _ -> split = (change.position.x / width).coerceIn(0f, 1f) }
            },
    ) {
        if (before == null) MediaThumb(fallback, Modifier.fillMaxSize(), maxSide = 720)
        else BytesImage(before, Modifier.fillMaxSize())
        if (after != null) {
            BytesImage(after, Modifier.fillMaxSize().clip(RevealShape(split)))
            val density = LocalDensity.current
            val x = with(density) { (width * split).toDp() }
            Box(Modifier.fillMaxHeight().width(2.dp).offset(x = x - 1.dp).background(Color.White))
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = x - 16.dp).size(32.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_tab_swap), null, tint = Color.Black, modifier = Modifier.size(18.dp)) }
            AiLabel(Modifier.align(Alignment.TopStart).padding(8.dp))
            Badge(stringResource(R.string.swap_after), Modifier.align(Alignment.BottomStart).padding(8.dp))
            Badge(stringResource(R.string.swap_before), Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

/** Saved option sets: tap to apply, x to delete, and one chip to save the current options. */
@Composable
private fun Presets(presets: Map<String, SwapOptions>, current: SwapOptions, onApply: (String) -> Unit, onDelete: (String) -> Unit, onSave: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    Text(stringResource(R.string.presets_title), style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets.keys.sorted(), key = { it }) { name ->
            InputChip(
                selected = presets[name] == current,
                onClick = { onApply(name) },
                label = { Text(name) },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        stringResource(R.string.presets_delete, name),
                        Modifier.size(16.dp).clickable { onDelete(name) },
                    )
                },
            )
        }
        item {
            AssistChip(
                onClick = { naming = true },
                label = { Text(stringResource(R.string.presets_save)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(16.dp)) },
            )
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.presets_save)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.presets_name)) },
                    placeholder = { Text(stringResource(R.string.presets_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onSave(name)
                    naming = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun JobPanel(progress: SwapProgress?, batch: BatchState?, onDismiss: () -> Unit, onOpenResults: () -> Unit, onStopRest: () -> Unit) {
    if (progress == null) return
    val finished = progress is SwapProgress.Done || progress is SwapProgress.Failed
    Section(stringResource(R.string.swap_job)) {
        if (batch != null) {
            if (finished) {
                Text(stringResource(R.string.batch_done, batch.done, batch.total), style = MaterialTheme.typography.titleSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.batch_progress, batch.done + 1, batch.total), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (batch.done + 1 < batch.total) TextButton(onClick = onStopRest) { Text(stringResource(R.string.batch_stop_rest)) }
                }
            }
        }
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
