package io.github.abrahampaulj.pocketfuse.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records the microphone to an AAC `.m4a` for lip sync. MediaRecorder writes the `moov` atom last, which
 * the upload path fixes with [Faststart] like any phone video.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts recording, or returns null when the microphone cannot be opened (busy in a call, say). */
    fun start(): File? {
        stop()
        val output = File(context.cacheDir, "voice/voice-${System.currentTimeMillis()}.m4a").apply { parentFile?.mkdirs() }
        val created = newRecorder()
        val started = runCatching {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(128_000)
            created.setOutputFile(output.absolutePath)
            created.prepare()
            created.start()
        }.isSuccess
        if (!started) {
            created.release()
            output.delete()
            return null
        }
        recorder = created
        file = output
        startedAt = System.currentTimeMillis()
        return output
    }

    /** Returns the recording, or null when it was too short to be useful (or failed). */
    fun stop(): File? {
        val active = recorder ?: return null
        recorder = null
        val stopped = runCatching { active.stop() }.isSuccess
        active.release()
        val output = file
        return if (stopped && output != null && System.currentTimeMillis() - startedAt >= MIN_MS) output else null.also { output?.delete() }
    }

    /** MediaRecorder(Context) arrived in Android 12; Android 10 and 11 only have the old constructor. */
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val MIN_MS = 700L
    }
}
