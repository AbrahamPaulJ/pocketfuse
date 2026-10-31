package io.github.abrahampaulj.pocketfuse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.core.JobCenter
import io.github.abrahampaulj.pocketfuse.media.Gallery
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ResultsScreen(jobs: JobCenter) {
    val results by jobs.results.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(R.drawable.ic_results, stringResource(R.string.results_empty_title), stringResource(R.string.results_empty))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Gap(4.dp) }
        items(results, key = { it.absolutePath }) { file ->
            Section(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))) {
                Box(Modifier.fillMaxWidth().aspectRatio(rememberAspect(file)).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                    if (Gallery.isVideo(file)) VideoPlayer(file) else ImageFile(file)
                    AiLabel(Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { Gallery.save(context, file) }.getOrNull() }
                            Toast.makeText(context, if (saved != null) R.string.results_saved else R.string.results_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.results_save)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val labelled = withContext(Dispatchers.IO) { runCatching { Gallery.labelled(file) }.getOrNull() }
                            if (labelled != null) {
                                context.startActivity(Gallery.shareIntent(context, labelled))
                            } else {
                                Toast.makeText(context, R.string.results_share_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text(stringResource(R.string.results_share)) }
                    TextButton(onClick = { jobs.delete(file) }) { Text(stringResource(R.string.results_delete)) }
                }
            }
        }
        item { Gap(24.dp) }
    }
}

@Composable
private fun VideoPlayer(file: File) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.stopPlayback() },
    )
}

/** The result scaled to screen size, decoded off the main thread; the black box shows until it lands. */
@Composable
private fun ImageFile(file: File) {
    val context = LocalContext.current
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) { runCatching { MediaInspector.decodeScaled(context, Uri.fromFile(file), 1440) }.getOrNull() }
    }
    BitmapImage(bitmap, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
}

/** The result's own shape, kept between 3:4 and 16:9, read off the main thread. */
@Composable
private fun rememberAspect(file: File): Float {
    var aspect by remember(file) { mutableStateOf(16f / 9f) }
    LaunchedEffect(file) {
        aspect = withContext(Dispatchers.IO) {
            runCatching {
                if (Gallery.isVideo(file)) {
                    android.media.MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(file.absolutePath)
                        val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                        val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rotation % 180 == 0) width / height else height / width
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    options.outWidth.toFloat() / options.outHeight
                }
            }.getOrDefault(16f / 9f).coerceIn(0.75f, 16f / 9f)
        }
    }
    return aspect
}
