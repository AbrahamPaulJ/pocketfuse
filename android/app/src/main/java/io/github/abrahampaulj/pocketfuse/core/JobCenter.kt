package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.abrahampaulj.pocketfuse.jobs.JobService
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A swap the user asked for, as handed to [JobService]. */
data class SwapRequest(val sources: List<LocalMedia>, val target: LocalMedia, val options: SwapOptions, val voice: LocalMedia? = null)

/** How far a batch (several targets shared at once) has got. */
data class BatchState(val done: Int, val total: Int)

/**
 * The swap in progress and past results. Several targets run one after another (the server runs one job
 * per session at a time); a failed target does not stop the rest.
 */
class JobCenter(private val context: Context) {
    private val _progress = MutableStateFlow<SwapProgress?>(null)
    val progress: StateFlow<SwapProgress?> = _progress.asStateFlow()

    private val _results = MutableStateFlow(scanResults())
    val results: StateFlow<List<File>> = _results.asStateFlow()

    private val queue = ArrayDeque<SwapRequest>()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    val outputDirectory: File get() = File(context.cacheDir, "outputs").apply { mkdirs() }

    val isRunning: Boolean
        get() = _progress.value.let { it != null && it !is SwapProgress.Done && it !is SwapProgress.Failed }

    fun start(request: SwapRequest): Boolean = startAll(listOf(request))

    fun startAll(requests: List<SwapRequest>): Boolean {
        if (isRunning || requests.isEmpty()) return false
        synchronized(queue) {
            queue.clear()
            queue.addAll(requests)
        }
        _batch.value = if (requests.size > 1) BatchState(0, requests.size) else null
        _progress.value = SwapProgress.Preparing
        ContextCompat.startForegroundService(context, Intent(context, JobService::class.java))
        return true
    }

    internal fun takePending(): SwapRequest? = synchronized(queue) { queue.removeFirstOrNull() }

    internal fun finishedOne() {
        _batch.value = _batch.value?.let { it.copy(done = it.done + 1) }
    }

    /** Drops the targets of a batch that have not started; the one running finishes. */
    fun stopRest() {
        synchronized(queue) { queue.clear() }
        _batch.value = _batch.value?.let { it.copy(total = (it.done + 1).coerceAtMost(it.total)) }
    }

    internal fun report(progress: SwapProgress) {
        _progress.value = progress
        if (progress is SwapProgress.Done) _results.value = scanResults()
    }

    fun dismiss() {
        if (isRunning) return
        _progress.value = null
        _batch.value = null
    }

    fun delete(file: File) {
        file.delete()
        // The labelled copy Gallery made for saving or sharing goes with its result.
        File(file.parentFile, "labelled").listFiles()?.filter { it.nameWithoutExtension == file.nameWithoutExtension }?.forEach { it.delete() }
        _results.value = scanResults()
    }

    private fun scanResults(): List<File> =
        File(context.cacheDir, "outputs").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
}
