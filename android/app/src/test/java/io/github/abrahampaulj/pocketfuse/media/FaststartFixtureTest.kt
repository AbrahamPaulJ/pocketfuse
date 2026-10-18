package io.github.abrahampaulj.pocketfuse.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Rewrites a real camera-style file when POCKETFUSE_FASTSTART_FIXTURE names one (moov at the end), writing
 * `<name>.fast.mp4` beside it so the result can be uploaded to a real server. Skipped otherwise.
 */
class FaststartFixtureTest {
    @Test
    fun rewritesARealFile() {
        val path = System.getenv("POCKETFUSE_FASTSTART_FIXTURE")
        assumeTrue(path != null)
        val input = File(path!!)
        assertTrue(Faststart.needsFaststart(input))
        val output = File(input.parentFile, input.nameWithoutExtension + ".fast.mp4")
        assertTrue(Faststart.rewrite(input, output) is Faststart.Result.Rewritten)
        assertFalse(Faststart.needsFaststart(output))
    }
}
