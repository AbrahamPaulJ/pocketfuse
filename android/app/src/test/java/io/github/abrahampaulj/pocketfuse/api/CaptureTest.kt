package io.github.abrahampaulj.pocketfuse.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTest {
    private val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x0D, 0x0A, 0x2D, 0x2D, 1, 2, 0xFF.toByte(), 0xD9.toByte())
    private val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 8, 7, 0xFF.toByte(), 0xD9.toByte())

    private fun multipart(boundary: String, vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { part ->
            out.write("--$boundary\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(part)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun aStripIsOnePart() {
        val capture = Capture.from(jpegA, "image/jpeg")
        assertTrue(capture.isStrip)
        assertArrayEquals(jpegA, capture.parts.single())
    }

    @Test
    fun multipartIsSplitByteExact() {
        val body = multipart("251e69075194c1d750525b7283f9f7ef", jpegA, jpegB)
        val capture = Capture.from(body, "multipart/mixed; boundary=251e69075194c1d750525b7283f9f7ef")
        assertEquals(2, capture.parts.size)
        assertArrayEquals(jpegA, capture.parts[0])
        assertArrayEquals(jpegB, capture.parts[1])
    }

    @Test
    fun anEmptyMultipartHasNoParts() {
        assertEquals(0, Capture.from("--b--\r\n".toByteArray(), "multipart/mixed; boundary=\"b\"").parts.size)
    }
}
