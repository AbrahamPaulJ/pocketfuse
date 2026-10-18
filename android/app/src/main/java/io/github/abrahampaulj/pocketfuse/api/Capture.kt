package io.github.abrahampaulj.pocketfuse.api

/**
 * What `capture` returned. `v4-challenge` answers one JPEG strip with the tiles laid left to right
 * ([isStrip]); the `v4` branch answers `multipart/mixed` with one JPEG per tile. Both are accepted.
 */
class Capture(val parts: List<ByteArray>, val isStrip: Boolean) {
    companion object {
        fun from(body: ByteArray, contentType: String?): Capture {
            val boundary = contentType
                ?.takeIf { it.startsWith("multipart/", ignoreCase = true) }
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"')
            return if (boundary == null) Capture(listOf(body), isStrip = true) else Capture(splitMultipart(body, boundary), isStrip = false)
        }

        /** Bodies of a multipart payload, in order. Headers are skipped; binary content is kept byte-exact. */
        internal fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
            val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
            val headerEnd = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            val parts = mutableListOf<ByteArray>()
            var start = indexOf(body, delimiter, 0)
            while (start >= 0) {
                val afterDelimiter = start + delimiter.size
                if (afterDelimiter + 1 < body.size && body[afterDelimiter] == '-'.code.toByte() && body[afterDelimiter + 1] == '-'.code.toByte()) break
                val next = indexOf(body, delimiter, afterDelimiter)
                if (next < 0) break
                val headersEnd = indexOf(body, headerEnd, afterDelimiter)
                if (headersEnd in 0 until next) {
                    var contentEnd = next
                    if (contentEnd >= 2 && body[contentEnd - 2] == '\r'.code.toByte() && body[contentEnd - 1] == '\n'.code.toByte()) contentEnd -= 2
                    parts += body.copyOfRange(headersEnd + headerEnd.size, contentEnd)
                }
                start = next
            }
            return parts
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
