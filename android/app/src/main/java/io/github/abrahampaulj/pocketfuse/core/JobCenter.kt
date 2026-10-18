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
data class SwapRequest(val sources: List<LocalMedia>, val target: LocalMedia, val options: SwapOptions)

/** The swap in progress (one at a time, as the server allows one queued job per session) and past results. */
class JobCenter(private val context: Context) {
    private val _progress = MutableStateFlow<SwapProgress?>(null)
    val progress: StateFlow<SwapProgress?> = _progress.asStateFlow()

    private val _results = MutableStateFlow(scanResults())
    val results: StateFlow<List<File>> = _results.asStateFlow()

    @Volatile
    var pending: SwapRequest? = null
        private set

    val outputDirectory: File get() = File(context.cacheDir, "outputs").apply { mkdirs() }

    val isRunning: Boolean
        get() = _progress.value.let { it != null && it !is SwapProgress.Done && it !is SwapProgress.Failed }

    fun start(request: SwapRequest): Boolean {
        if (isRunning) return false
        pending = request
        _progress.value = SwapProgress.Preparing
        ContextCompat.startForegroundService(context, Intent(context, JobService::class.java))
        return true
    }

    internal fun takePending(): SwapRequest? = pending.also { pending = null }

    internal fun report(progress: SwapProgress) {
        _progress.value = progress
        if (progress is SwapProgress.Done) _results.value = scanResults()
    }

    fun dismiss() {
        if (!isRunning) _progress.value = null
    }

    fun delete(file: File) {
        file.delete()
        _results.value = scanResults()
    }

    private fun scanResults(): List<File> =
        File(context.cacheDir, "outputs").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
}
