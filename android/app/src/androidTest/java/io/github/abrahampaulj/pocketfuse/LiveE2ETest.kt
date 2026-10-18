package io.github.abrahampaulj.pocketfuse

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import io.github.abrahampaulj.pocketfuse.live.RtcSession
import io.github.abrahampaulj.pocketfuse.live.RtcState
import io.github.abrahampaulj.pocketfuse.live.VideoInput
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.pair.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * The live stream end to end without a camera: a still is pushed as video over WebRTC, and processed
 * frames must come back from the PC. Same `pairing` argument as [ServerE2ETest].
 */
@RunWith(AndroidJUnit4::class)
class LiveE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun example(name: String): File = File(context.cacheDir, "e2e/$name").also { file ->
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            URL("https://github.com/facefusion/facefusion-assets/releases/download/examples-3.0.0/$name").openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    @Test
    fun stillStreamComesBackProcessed() = runBlocking {
        val pairing = InstrumentationRegistry.getArguments().getString("pairing")
        assumeTrue(pairing != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = Connection(context, Pairing.parse(pairing!!)!!, scope)
        connection.connect()
        val source = MediaInspector.inspect(context, Uri.fromFile(example("source.jpg")))
        assertEquals(null, StreamPreview(connection).prepare(listOf(source)))
        connection.applyState(SwapOptions().toState(forStream = true))

        val target = MediaInspector.inspect(context, Uri.fromFile(example("target-240p.mp4")))
        val frame = MediaInspector.frameJpeg(context, target, 2000, 640)!!
        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)

        val session = RtcSession(
            context,
            postOffer = { offer -> connection.keeper.call { postStreamOffer(offer) } },
            deleteStream = { connection.keeper.call { deleteStream() } },
        )
        try {
            session.start(VideoInput.Still(bitmap, fps = 10), sendAudio = false)
            assertEquals("live stream failed: ${session.state.value}", RtcState.Connected, session.state.value)
            val fps = withTimeout(30_000) { session.receivedFps.first { it > 0f } }
            assertTrue(fps > 0f)
        } finally {
            session.stop()
            session.release()
            connection.close()
            scope.cancel()
        }
    }
}
