package io.github.abrahampaulj.pocketfuse.core

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.abrahampaulj.pocketfuse.PocketfuseApp
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchAndPresetsTest {
    private val app: PocketfuseApp get() = ApplicationProvider.getApplicationContext()

    private fun request(name: String) = SwapRequest(
        sources = emptyList(),
        target = LocalMedia(Uri.parse("content://test/$name"), name, "video/mp4", 1, 0, 0, 0),
        options = SwapOptions(),
    )

    @Test
    fun batchRunsTargetsInOrderAndCountsThem() {
        val jobs = JobCenter(app)
        assertTrue(jobs.startAll(listOf(request("a"), request("b"), request("c"))))
        assertEquals(BatchState(0, 3), jobs.batch.value)
        assertFalse("a second batch must wait for the first", jobs.startAll(listOf(request("d"))))
        assertEquals("a", jobs.takePending()?.target?.name)
        jobs.finishedOne()
        assertEquals("b", jobs.takePending()?.target?.name)
        assertEquals(BatchState(1, 3), jobs.batch.value)
    }

    @Test
    fun stopRestDropsWhatHasNotStarted() {
        val jobs = JobCenter(app)
        jobs.startAll(listOf(request("a"), request("b"), request("c")))
        jobs.takePending()
        jobs.stopRest()
        assertNull(jobs.takePending())
        assertEquals(BatchState(0, 1), jobs.batch.value)
    }

    @Test
    fun singleSwapIsNotABatch() {
        val jobs = JobCenter(app)
        jobs.start(request("a"))
        assertNull(jobs.batch.value)
    }

    @Test
    fun presetsSaveApplyDeleteAndSurviveARestart() {
        val workspace = Workspace(app)
        workspace.updateOptions { it.copy(enhance = true) }
        workspace.savePreset("  Final quality  ")
        workspace.updateOptions { it.copy(enhance = false) }
        workspace.applyPreset("Final quality")
        assertTrue(workspace.options.value.enhance)
        assertEquals(setOf("Final quality"), Workspace(app).presets.value.keys)
        workspace.deletePreset("Final quality")
        assertTrue(Workspace(app).presets.value.isEmpty())
    }
}
