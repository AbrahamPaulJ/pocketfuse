package io.github.abrahampaulj.pocketfuse.media

import android.content.Context
import android.media.MediaRecorder
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

    fun start(): File {
        stop()
        val output = File(context.cacheDir, "voice/voice-${System.currentTimeMillis()}.m4a").apply { parentFile?.mkdirs() }
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
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

    private companion object {
        const val MIN_MS = 700L
    }
}
