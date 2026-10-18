package io.github.abrahampaulj.pocketfuse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.CaptureSubject
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.SessionState
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.core.FaceCheck
import io.github.abrahampaulj.pocketfuse.core.FrameResult
import io.github.abrahampaulj.pocketfuse.core.ModelWarmup
import io.github.abrahampaulj.pocketfuse.core.StreamPreview
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import io.github.abrahampaulj.pocketfuse.core.SwapProgress
import io.github.abrahampaulj.pocketfuse.core.SwapRunner
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import io.github.abrahampaulj.pocketfuse.pair.Pairing
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import io.github.abrahampaulj.pocketfuse.puppet.EditorPose
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * Runs on the phone against a real FaceFusion v4 server, over the same network path the app uses.
 * Arguments (tools/e2e_phone.ps1 passes them): `pairing` = a pocketfuse://pair link. Skipped without it.
 * Media is downloaded from facefusion-assets at run time, as upstream's own tests do.
 */
@RunWith(AndroidJUnit4::class)
class ServerE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var config: ServerConfig
    private lateinit var scope: CoroutineScope
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        val pairing = InstrumentationRegistry.getArguments().getString("pairing")
        assumeTrue("no pairing argument", pairing != null)
        config = Pairing.parse(pairing!!)!!
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        connection = Connection(context, config, scope)
    }

    @After
    fun tearDown() = runBlocking {
        if (::connection.isInitialized) connection.close()
        if (::scope.isInitialized) scope.cancel()
    }

    private fun example(name: String): File {
        val file = File(context.cacheDir, "e2e/$name")
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            URL("https://github.com/facefusion/facefusion-assets/releases/download/examples-3.0.0/$name").openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file
    }

    private fun media(name: String) = MediaInspector.inspect(context, Uri.fromFile(example(name)))

    @Test
    fun wrongKeyIsRefusedWithAHumanMessage() = runBlocking {
        assumeTrue(config.apiKey != null)
        val client = FaceFusionClient(config.baseUrl)
        try {
            client.openSession(config.apiKey + "x")
            fail("a wrong key opened a session")
        } catch (exception: ApiException) {
            assertEquals(401, exception.status)
            assertTrue(exception.userMessage.contains("API key"))
        }
    }

    /** The UI calls the client from the main thread; Android throws on any socket read there. */
    @Test
    fun clientIsSafeToCallFromTheMainThread() = runBlocking {
        withContext(Dispatchers.Main) {
            assertTrue(connection.connect() is SessionState.Open)
            connection.keeper.call { assets() }
            connection.keeper.call { state() }
            connection.keeper.close()
        }
    }

    @Test
    fun sessionCapabilitiesAndCapture() = runBlocking {
        val state = connection.connect()
        assertTrue("session did not open: $state", state is SessionState.Open)
        val capabilities = connection.capabilities.first { it != null }!!
        assertTrue(capabilities.choicesOf("face_swapper_model").isNotEmpty())
        assertTrue(capabilities.accepts("mp4"))

        val sourceId = connection.upload(AssetType.Source, media("source.jpg"))
        val capture = connection.keeper.call { capture(sourceId, CaptureSubject.Face, 128, 128) }
        val first = capture.parts.first()
        val bitmap = BitmapFactory.decodeByteArray(first, 0, first.size)
        assertEquals(128, bitmap.height)
        assertTrue("no face found in source.jpg", bitmap.width >= 128)
    }

    @Test
    fun faceCheckFindsTheFaceAndFlagsAPhotoWithout() = runBlocking {
        connection.connect()
        assertTrue(connection.detectFace(media("source.jpg")) is FaceCheck.Found)
        val blank = File(context.cacheDir, "e2e/blank.jpg")
        blank.outputStream().use { Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertEquals(FaceCheck.NoFace, connection.detectFace(MediaInspector.inspect(context, Uri.fromFile(blank))))
    }

    @Test
    fun streamPreviewSwapsAFrame() = runBlocking {
        connection.connect()
        val frame = MediaInspector.frameJpeg(context, media("target-240p.mp4"), 2000, 480)!!
        val preview = StreamPreview(context, connection)
        assertEquals(null, preview.prepare(listOf(media("source.jpg"))))
        val result = withTimeout(120_000) { preview.process(frame, SwapOptions().toState(forStream = true)) }
        assertTrue("stream failed: $result", result is FrameResult.Ready)
        assertNotEquals("the stream returned the frame unchanged", frame.size, (result as FrameResult.Ready).jpeg.size)
    }

    @Test
    fun metricsArriveOverTheWebSocket() = runBlocking {
        connection.connect()
        val metrics = withTimeout(10_000) { connection.metrics.latest.first { it != null } }!!
        assertTrue(metrics.processorUtilization != null)
    }

    @Test
    fun fullSwapJobOnAnImage() = runBlocking {
        connection.connect()
        val target = File(context.cacheDir, "e2e/frame.jpg")
        target.writeBytes(MediaInspector.frameJpeg(context, media("target-240p.mp4"), 2000, 480)!!)
        val runner = SwapRunner(connection, File(context.cacheDir, "e2e-out"))
        val result = withTimeout(300_000) {
            runner.run(listOf(media("source.jpg")), MediaInspector.inspect(context, Uri.fromFile(target)), SwapOptions()) { }
        }
        assertTrue("swap failed: $result", result is SwapProgress.Done)
        assertTrue((result as SwapProgress.Done).output.length() > 0)
    }

    @Test
    fun serverAcceptsEverySnappedPuppetValue() = runBlocking {
        connection.connect()
        val odd = EditorPose(0.333, -0.871, 0.0249, 0.9751, -0.45, 0.07, -0.024, 1.3, 0.15000000000000002, -0.0, 0.5, -1.7, 0.66, -0.12)
        connection.applyState(mapOf("processors" to JsonArray(listOf(JsonPrimitive("face_editor")))) + odd.toStateBody())
        val state = connection.keeper.call { state() }
        assertEquals("0.35", state["face_editor_eyebrow_direction"].toString())
        assertEquals("1.0", state["face_editor_mouth_purse"].toString())
    }

    @Test
    fun warmupPreparesModelsOnceThenRemembers() = runBlocking {
        connection.connect()
        val sourceId = connection.upload(AssetType.Source, media("source.jpg"))
        val warmup = ModelWarmup(context, connection)
        val state = SwapOptions(enhance = true).toState(forStream = true)
        assertEquals(null, withTimeout(600_000) { warmup.ensure(state, listOf(sourceId)) })
        assertTrue(warmup.isWarm(warmup.modelKey(state, connection.capabilities.value)))
    }

    @Test
    fun lipSyncToARecordedVoice() = runBlocking {
        connection.connect()
        val runner = SwapRunner(connection, File(context.cacheDir, "e2e-out"))
        val options = SwapOptions(lipSync = true).with("trim_frame_end", JsonPrimitive(25))
        val result = withTimeout(600_000) {
            runner.run(emptyList(), media("target-240p.mp4"), options, media("source.mp3")) { }
        }
        assertTrue("lip sync failed: $result", result is SwapProgress.Done)
    }

    @Test
    fun refreshRotatesAndCloseEnds() = runBlocking {
        connection.connect()
        val before = connection.client.tokens!!.accessToken
        connection.keeper.refreshNow()
        assertNotEquals(before, connection.client.tokens!!.accessToken)
        val stale = FaceFusionClient(config.baseUrl).apply { tokens = connection.client.tokens!!.copy(accessToken = before) }
        try {
            stale.session()
            fail("a rotated token still worked")
        } catch (exception: ApiException) {
            assertTrue(exception.sessionLost)
        }
        connection.keeper.close()
        assertEquals(SessionState.Closed, connection.session.value)
    }
}
