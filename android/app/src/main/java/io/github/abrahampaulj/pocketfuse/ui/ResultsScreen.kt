package io.github.abrahampaulj.pocketfuse.ui

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
        Hint(stringResource(R.string.results_empty), Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Gap(4.dp) }
        items(results, key = { it.absolutePath }) { file ->
            Section(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
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
                    OutlinedButton(onClick = { context.startActivity(Gallery.shareIntent(context, file)) }) { Text(stringResource(R.string.results_share)) }
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

@Composable
private fun ImageFile(file: File) {
    val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath) }
    BitmapImage(bitmap, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
}
