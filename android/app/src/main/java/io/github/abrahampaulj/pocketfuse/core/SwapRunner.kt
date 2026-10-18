package io.github.abrahampaulj.pocketfuse.core

import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.JobStatus
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import kotlinx.coroutines.delay
import java.io.File

sealed interface SwapProgress {
    data class Uploading(val what: String, val fraction: Float) : SwapProgress
    data object Preparing : SwapProgress
    data class Processing(val elapsedSeconds: Int) : SwapProgress
    data class Downloading(val fraction: Float) : SwapProgress
    data class Done(val output: File, val jobId: String, val seconds: Int) : SwapProgress
    data class Failed(val message: String, val retryable: Boolean) : SwapProgress
}

/**
 * The developer guide's flow, end to end: upload, select, set the options, create a job with one step,
 * submit, run, poll until every step completed or one failed, then fetch the output asset named after
 * the job. Each stage reports through [onProgress]; every failure ends as [SwapProgress.Failed] with a
 * sentence for the user, never an exception.
 */
class SwapRunner(private val connection: Connection, private val outputDirectory: File) {

    suspend fun run(sources: List<LocalMedia>, target: LocalMedia, options: SwapOptions, voice: LocalMedia? = null, onProgress: (SwapProgress) -> Unit): SwapProgress {
        val result = try {
            execute(sources, target, options, voice?.takeIf { options.lipSync }, onProgress)
        } catch (exception: ApiException) {
            SwapProgress.Failed(exception.userMessage, retryable = exception.status == 409 || exception.sessionLost)
        } catch (exception: UnreachableException) {
            SwapProgress.Failed(exception.userMessage, retryable = true)
        }
        onProgress(result)
        return result
    }

    private suspend fun execute(sources: List<LocalMedia>, target: LocalMedia, options: SwapOptions, voice: LocalMedia?, onProgress: (SwapProgress) -> Unit): SwapProgress {
        val session = connection.ensureOpen()
        if (session !is SessionState.Open) return SwapProgress.Failed((session as? SessionState.Failed)?.message ?: "Not connected.", true)

        val sourceIds = sources.mapIndexed { index, source ->
            connection.upload(AssetType.Source, source) { onProgress(SwapProgress.Uploading("face ${index + 1} of ${sources.size}", it)) }
        }
        // The voice is one more source: FaceFusion takes the audio among the source files for lip sync.
        val voiceIds = listOfNotNull(voice).map { connection.upload(AssetType.Source, it) { fraction -> onProgress(SwapProgress.Uploading("voice", fraction)) } }
        val targetId = connection.upload(AssetType.Target, target) { onProgress(SwapProgress.Uploading(target.name, it)) }

        onProgress(SwapProgress.Preparing)
        val keeper = connection.keeper
        keeper.call { selectSources(sourceIds + voiceIds) }
        keeper.call { selectTarget(targetId) }
        val state = options.toState(forStream = false, hasFaces = sourceIds.isNotEmpty(), hasVoice = voiceIds.isNotEmpty())
        connection.applyState(state)

        val jobId = keeper.call { createJob() }
        keeper.call { addStep(jobId, state) }
        keeper.call { submitJob(jobId) }
        keeper.call { runJob(jobId) }

        val started = System.currentTimeMillis()
        while (true) {
            val job = keeper.call { job(jobId) }
            when (job.status) {
                JobStatus.Completed -> break
                JobStatus.Failed -> return SwapProgress.Failed(
                    "The server could not finish this swap. Its console says why; a common cause is no face found in the target.",
                    retryable = true,
                )
                else -> onProgress(SwapProgress.Processing(((System.currentTimeMillis() - started) / 1000).toInt()))
            }
            delay(POLL_MS)
        }
        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()

        // The output asset is registered by a second background task right after the run; give it a moment.
        var outputId: String? = null
        for (attempt in 0 until OUTPUT_WAIT_TRIES) {
            outputId = keeper.call { assets() }.firstOrNull { it.type == "output" && it.name.startsWith(jobId) }?.id
            if (outputId != null) break
            delay(250)
        }
        val assetId = outputId ?: return SwapProgress.Failed("The job finished but the server registered no output file.", retryable = true)
        val asset = keeper.call { asset(assetId) }
        val file = File(outputDirectory, "$jobId.${asset.format}")
        keeper.call { download(assetId, file) { onProgress(SwapProgress.Downloading(it)) } }
        return SwapProgress.Done(file, jobId, seconds)
    }

    private companion object {
        const val POLL_MS = 1000L
        const val OUTPUT_WAIT_TRIES = 40
    }
}
