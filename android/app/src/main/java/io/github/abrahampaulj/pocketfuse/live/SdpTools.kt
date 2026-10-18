package io.github.abrahampaulj.pocketfuse.live

/** Pure SDP helpers for the live stream, kept free of Android types so they run as JVM unit tests. */
object SdpTools {

    /**
     * Codec order to offer. The server picks AV1 if offered, then VP9, then VP8; phones decode VP8 in
     * hardware everywhere while AV1 is usually software, so the offer carries VP8 first and never AV1.
     */
    val PREFERRED_VIDEO = listOf("VP8", "VP9")

    fun orderCodecs(available: List<String>, preferred: List<String> = PREFERRED_VIDEO): List<String> =
        preferred.filter { wanted -> available.any { it.equals(wanted, ignoreCase = true) } }

    /** Host candidates in an SDP. Without STUN or TURN these are the only ones, and zero means no network. */
    fun countHostCandidates(sdp: String): Int =
        sdp.lineSequence().count { it.startsWith("a=candidate:") && " typ host" in it }

    /**
     * Removes every video payload type whose codec is not in [keep] (plus their rtx / fmtp / rtcp-fb lines),
     * so the server can only choose among them. Audio sections are untouched.
     */
    fun keepVideoCodecs(sdp: String, keep: Set<String>): String {
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var section = mutableListOf<String>()

        fun flush() {
            if (section.isEmpty()) return
            out += if (section.first().startsWith("m=video")) filterVideoSection(section, keep.map { it.uppercase() }.toSet()) else section
            section = mutableListOf()
        }

        for (line in lines) {
            if (line.startsWith("m=")) flush()
            section += line
        }
        flush()
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    private fun filterVideoSection(section: List<String>, keep: Set<String>): List<String> {
        val codecOf = mutableMapOf<String, String>()
        section.forEach { line ->
            if (line.startsWith("a=rtpmap:")) {
                val payload = line.substringAfter("a=rtpmap:").substringBefore(' ')
                codecOf[payload] = line.substringAfter(' ').substringBefore('/').uppercase()
            }
        }
        val kept = codecOf.filterValues { it in keep }.keys.toMutableSet()
        // An rtx payload survives only when it repairs a kept codec (a=fmtp:<rtx> apt=<payload>).
        section.forEach { line ->
            if (line.startsWith("a=fmtp:")) {
                val payload = line.substringAfter("a=fmtp:").substringBefore(' ')
                val apt = Regex("apt=(\\d+)").find(line)?.groupValues?.get(1)
                if (codecOf[payload] == "RTX" && apt in kept) kept += payload
            }
        }
        return section.mapNotNull { line ->
            when {
                line.startsWith("m=video") -> {
                    val parts = line.split(' ')
                    (parts.take(3) + parts.drop(3).filter { it in kept }).joinToString(" ")
                }
                line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:") || line.startsWith("a=rtcp-fb:") -> {
                    val payload = line.substringAfter(':').substringBefore(' ')
                    line.takeIf { payload in kept || payload == "*" }
                }
                else -> line
            }
        }
    }
}
