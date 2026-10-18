package io.github.abrahampaulj.pocketfuse.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Moves the `moov` atom of an MP4 / MOV / M4A in front of `mdat`, as `ffmpeg -movflags +faststart` does.
 *
 * FaceFusion reads uploads as a stream and rejects files whose `moov` comes last (415), which is how most
 * phone cameras write them. The guide allows clients to fix it "by reordering the bytes themselves": the
 * only edit needed is to add the size of `moov` to every chunk offset in `stco` / `co64`, because the
 * media data moves back by exactly that much.
 */
object Faststart {

    data class Atom(val type: String, val offset: Long, val size: Long)

    sealed interface Result {
        data object AlreadyFaststart : Result
        data class Rewritten(val output: File) : Result
        data class Unsupported(val reason: String) : Result
    }

    fun topLevelAtoms(file: RandomAccessFile): List<Atom> {
        val atoms = mutableListOf<Atom>()
        var offset = 0L
        val length = file.length()
        val header = ByteArray(16)
        while (offset + 8 <= length) {
            file.seek(offset)
            file.readFully(header, 0, 8)
            var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                file.readFully(header, 8, 8)
                size = ByteBuffer.wrap(header, 8, 8).long
            } else if (size == 0L) {
                size = length - offset
            }
            if (size < 8 || offset + size > length) throw IOException("Broken atom '$type' at $offset")
            atoms += Atom(type, offset, size)
            offset += size
        }
        return atoms
    }

    fun needsFaststart(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        val atoms = topLevelAtoms(raf)
        val moov = atoms.indexOfFirst { it.type == "moov" }
        val mdat = atoms.indexOfFirst { it.type == "mdat" }
        moov >= 0 && mdat >= 0 && moov > mdat
    }

    fun rewrite(input: File, output: File): Result = RandomAccessFile(input, "r").use { raf ->
        val atoms = try {
            topLevelAtoms(raf)
        } catch (exception: IOException) {
            return Result.Unsupported(exception.message ?: "unreadable file")
        }
        val moov = atoms.firstOrNull { it.type == "moov" } ?: return Result.Unsupported("no moov atom")
        val firstMdat = atoms.firstOrNull { it.type == "mdat" } ?: return Result.Unsupported("no mdat atom")
        if (moov.offset < firstMdat.offset) return Result.AlreadyFaststart
        if (moov.size > Int.MAX_VALUE) return Result.Unsupported("moov too large")

        raf.seek(moov.offset)
        val moovBytes = ByteArray(moov.size.toInt())
        raf.readFully(moovBytes)
        val shifted = shiftChunkOffsets(ByteBuffer.wrap(moovBytes), moov.size)
            ?: return Result.Unsupported("chunk offsets would overflow 32 bits")
        if (!shifted) return Result.Unsupported("no chunk offset table")

        output.outputStream().buffered(1 shl 16).use { out ->
            val buffer = ByteArray(1 shl 16)
            fun copy(atom: Atom) {
                raf.seek(atom.offset)
                var remaining = atom.size
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Unexpected end of file")
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
            // Atoms before the first mdat (ftyp, free, ...) keep their place; moov goes right after them.
            atoms.filter { it.offset < firstMdat.offset }.forEach(::copy)
            out.write(moovBytes)
            atoms.filter { it.offset >= firstMdat.offset && it.type != "moov" }.forEach(::copy)
        }
        Result.Rewritten(output)
    }

    /**
     * Adds [delta] to every entry of every `stco` and `co64` inside [moov]. Returns false when there was
     * no table at all, null when a 32 bit offset would overflow (a > 4 GB file, far above the server's cap).
     */
    internal fun shiftChunkOffsets(moov: ByteBuffer, delta: Long): Boolean? {
        var found = false
        fun walk(start: Int, end: Int): Boolean {
            var position = start
            while (position + 8 <= end) {
                val size = moov.getInt(position).toLong() and 0xffffffffL
                val type = String(ByteArray(4) { moov.get(position + 4 + it) }, Charsets.ISO_8859_1)
                if (size < 8 || position + size > end) return true
                val body = position + 8
                when (type) {
                    "trak", "mdia", "minf", "stbl", "edts", "dinf" -> if (!walk(body, (position + size).toInt())) return false
                    "stco" -> {
                        found = true
                        val count = moov.getInt(body + 4)
                        for (index in 0 until count) {
                            val at = body + 8 + index * 4
                            val shifted = (moov.getInt(at).toLong() and 0xffffffffL) + delta
                            if (shifted > 0xffffffffL) return false
                            moov.putInt(at, shifted.toInt())
                        }
                    }
                    "co64" -> {
                        found = true
                        val count = moov.getInt(body + 4)
                        for (index in 0 until count) {
                            val at = body + 8 + index * 8
                            moov.putLong(at, moov.getLong(at) + delta)
                        }
                    }
                }
                position += size.toInt()
            }
            return true
        }
        val ok = walk(8, moov.limit())
        return if (!ok) null else found
    }
}
