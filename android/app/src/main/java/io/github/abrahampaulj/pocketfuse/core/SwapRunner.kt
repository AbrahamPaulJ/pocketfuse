package io.github.abrahampaulj.pocketfuse.core

import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.JobStatus
import io.github.abrahampaulj.pocketfuse.api.SessionKeeper
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * What the user is told when the phone itself fails, not the server: a picked file that was moved,
 * deleted or offloaded, a read grant that is gone, or storage that ran out.
 */
fun phoneFileMessage(exception: Exception): String = when (exception) {
    is SecurityException -> "Pocketfuse is no longer allowed to read that file. Pick it again."
    else -> "Pocketfuse could not read or save a file on the phone. Pick the photo or video again, and check the phone has free space."
}

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
        } catch (exception: IOException) {
            SwapProgress.Failed(phoneFileMessage(exception), retryable = false)
        } catch (exception: SecurityException) {
            SwapProgress.Failed(phoneFileMessage(exception), retryable = false)
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
        // What a preview, the Lab or the puppet left on the session goes back to the default, in the step too:
        // a job takes any option its step leaves out from the session as it is when the job starts.
        val state = connection.withDefaults(options.toState(forStream = false, hasFaces = sourceIds.isNotEmpty(), hasVoice = voiceIds.isNotEmpty()))
        connection.applyState(state)

        val jobId = keeper.call { createJob() }
        keeper.call { addStep(jobId, state) }
        keeper.call { submitJob(jobId) }
        keeper.call { runJob(jobId) }

        val started = System.currentTimeMillis()
        while (true) {
            // A Wi-Fi roam or a short drop must not abandon a job the PC is still running.
            val job = keeper.untilReachable { job(jobId) }
            when (job.status) {
                JobStatus.Completed -> break
                JobStatus.Failed -> return SwapProgress.Failed(
                    "Your PC could not finish this swap. The two usual reasons: no face was found in the target, or " +
                        "FaceFusion's content check flagged it (Pocketfuse never bypasses that check). The PC's console says which.",
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
            outputId = keeper.untilReachable { assets() }.firstOrNull { it.type == "output" && it.name.startsWith(jobId) }?.id
            if (outputId != null) break
            delay(250)
        }
        val assetId = outputId ?: return SwapProgress.Failed("The job finished but the server registered no output file.", retryable = true)
        val asset = keeper.call { asset(assetId) }
        val file = File(outputDirectory, "$jobId.${asset.format}")
        keeper.call { download(assetId, file) { onProgress(SwapProgress.Downloading(it)) } }
        return SwapProgress.Done(file, jobId, seconds)
    }

    /**
     * Runs [block] like [SessionKeeper.call], but asks again with a growing pause while the server cannot
     * be reached, for up to [UNREACHABLE_MS] from the first failure; only then does the network error reach
     * the user. The clock starts at the first failure, not the first call: a request that hung until its
     * read timeout still gets asked again. Every other error, a lost session among them, ends the wait at once.
     */
    private suspend fun <T> SessionKeeper.untilReachable(block: suspend FaceFusionClient.() -> T): T {
        var since = 0L
        var pause = RETRY_FIRST_MS
        while (true) {
            val failure = try {
                return call(block)
            } catch (exception: UnreachableException) {
                exception
            } catch (exception: IOException) {
                UnreachableException(client.host, exception)
            }
            val now = System.currentTimeMillis()
            if (since == 0L) since = now
            if (now - since + pause > UNREACHABLE_MS) throw failure
            delay(pause)
            pause = (pause * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    private companion object {
        const val POLL_MS = 500L
        const val OUTPUT_WAIT_TRIES = 40
        const val UNREACHABLE_MS = 60_000L
        const val RETRY_FIRST_MS = 1000L
        const val RETRY_MAX_MS = 5000L
    }
}
