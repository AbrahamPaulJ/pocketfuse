package io.github.abrahampaulj.pocketfuse.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A saved or shared image carries the AI label in its pixels, not only on screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LabelBurnTest {
    private fun grey(): Bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    @Test
    fun labelIsDrawnTopLeftAndTheRestIsUntouched() {
        val source = grey()
        val labelled = Gallery.burnLabel(source)
        assertEquals(400, labelled.width)
        assertEquals(300, labelled.height)
        assertNotEquals(Color.GRAY, labelled.getPixel(20, 15))
        assertEquals(Color.GRAY, labelled.getPixel(399, 299))
        // The clean result stays clean for the before/after divider and the Lab tiles.
        assertEquals(Color.GRAY, source.getPixel(20, 15))
    }

    @Test
    fun formatsAndroidCannotWriteBecomePng() {
        assertEquals(Bitmap.CompressFormat.JPEG to "jpg", Gallery.encodingOf("JPG"))
        assertEquals(Bitmap.CompressFormat.JPEG to "jpeg", Gallery.encodingOf("jpeg"))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSY to "webp", Gallery.encodingOf("webp"))
        assertEquals(Bitmap.CompressFormat.PNG to "png", Gallery.encodingOf("bmp"))
        assertEquals(Bitmap.CompressFormat.PNG to "png", Gallery.encodingOf("png"))
    }
}
