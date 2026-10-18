package io.github.abrahampaulj.pocketfuse.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FaststartTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun atom(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + body.size)
            writeBytes(type)
            write(body)
        }
        return out.toByteArray()
    }

    private fun stco(offsets: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0)
            writeInt(offsets.size)
            offsets.forEach { writeInt(it) }
        }
        return atom("stco", out.toByteArray())
    }

    private fun moov(offsets: List<Int>) =
        atom("moov", atom("trak", atom("mdia", atom("minf", atom("stbl", stco(offsets))))))

    /** ftyp, mdat holding two samples, moov last pointing at them: what a phone camera writes. */
    private fun cameraStyleFile(): Pair<File, ByteArray> {
        val ftyp = atom("ftyp", "isom0000".toByteArray())
        val samples = "SAMPLE-ONE".toByteArray() + "SAMPLE-TWO".toByteArray()
        val mdat = atom("mdat", samples)
        val firstSample = ftyp.size + 8
        val moov = moov(listOf(firstSample, firstSample + 10))
        val file = temp.newFile("camera.mp4")
        file.writeBytes(ftyp + mdat + moov)
        return file to samples
    }

    private fun readOffsets(file: File): List<Long> = RandomAccessFile(file, "r").use { raf ->
        val moov = Faststart.topLevelAtoms(raf).first { it.type == "moov" }
        val bytes = ByteArray(moov.size.toInt())
        raf.seek(moov.offset)
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes)
        val stcoAt = String(bytes, Charsets.ISO_8859_1).indexOf("stco") + 4
        val count = buffer.getInt(stcoAt + 4)
        List(count) { buffer.getInt(stcoAt + 8 + it * 4).toLong() }
    }

    @Test
    fun movesMoovInFrontAndKeepsSamplesAddressable() {
        val (input, samples) = cameraStyleFile()
        assertTrue(Faststart.needsFaststart(input))

        val output = temp.newFile("fast.mp4")
        val result = Faststart.rewrite(input, output)

        assertTrue(result is Faststart.Result.Rewritten)
        assertFalse(Faststart.needsFaststart(output))
        assertEquals(input.length(), output.length())
        val types = RandomAccessFile(output, "r").use { Faststart.topLevelAtoms(it).map { a -> a.type } }
        assertEquals(listOf("ftyp", "moov", "mdat"), types)

        val bytes = output.readBytes()
        val offsets = readOffsets(output)
        assertArrayEquals("SAMPLE-ONE".toByteArray(), bytes.copyOfRange(offsets[0].toInt(), offsets[0].toInt() + 10))
        assertArrayEquals("SAMPLE-TWO".toByteArray(), bytes.copyOfRange(offsets[1].toInt(), offsets[1].toInt() + 10))
        assertEquals(samples.size, 20)
    }

    @Test
    fun leavesFaststartFilesAlone() {
        val ftyp = atom("ftyp", "isom0000".toByteArray())
        val file = temp.newFile("fast.mp4")
        file.writeBytes(ftyp + moov(listOf(100)) + atom("mdat", ByteArray(10)))

        assertFalse(Faststart.needsFaststart(file))
        assertEquals(Faststart.Result.AlreadyFaststart, Faststart.rewrite(file, temp.newFile("out.mp4")))
    }

    @Test
    fun refusesWhenAThirtyTwoBitOffsetWouldOverflow() {
        val buffer = ByteBuffer.wrap(moov(listOf(-16)))
        assertEquals(null, Faststart.shiftChunkOffsets(buffer, 64))
    }

    @Test
    fun reportsBrokenFiles() {
        val file = temp.newFile("broken.mp4")
        file.writeBytes(byteArrayOf(0, 0, 0, 99, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        assertTrue(Faststart.rewrite(file, temp.newFile("out.mp4")) is Faststart.Result.Unsupported)
    }
}
