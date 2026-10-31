package io.github.abrahampaulj.pocketfuse.media

import android.net.Uri
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the phone lets through to the server, against the real `/capabilities` of `v4-challenge`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRulesTest {
    private val capabilities = Capabilities.parse(
        FaceFusionClient.json.parseToJsonElement(javaClass.classLoader!!.getResource("capabilities.json")!!.readText()).jsonObject,
    )

    private fun photo(name: String, mime: String = "image/jpeg") = LocalMedia(Uri.parse("content://test/$name"), name, mime, 1000, 1024, 1024, 0)

    @Test
    fun phoneExtensionsMatchWhatTheServerLists() {
        // The server lists jpeg and tiff; cameras write jpg and tif. Refusing them blocked every photo target.
        assertTrue(capabilities.accepts("jpg"))
        assertTrue(capabilities.accepts("JPG"))
        assertTrue(capabilities.accepts("jpeg"))
        assertTrue(capabilities.accepts("tif"))
        assertTrue(capabilities.accepts("mp4"))
        assertFalse(capabilities.accepts("heic"))
    }

    @Test
    fun jpgPhotoIsNotAProblem() {
        assertNull(MediaInspector.problems(photo("IMG_0001.jpg"), capabilities::accepts))
    }

    @Test
    fun heicAndAvifPhotosAreConvertedNotRefused() {
        val heic = photo("IMG_0002.heic", "image/heic")
        assertTrue(MediaInspector.needsJpeg(heic))
        assertNull(MediaInspector.problems(heic, capabilities::accepts))
        assertNull(MediaInspector.problems(photo("pic.avif", "image/avif"), capabilities::accepts))
    }

    @Test
    fun unknownFormatsAreStillRefusedWithAReason() {
        val problem = MediaInspector.problems(photo("notes.xyz", "application/octet-stream"), capabilities::accepts)
        assertTrue(problem is MediaProblem.Unsupported)
    }
}
