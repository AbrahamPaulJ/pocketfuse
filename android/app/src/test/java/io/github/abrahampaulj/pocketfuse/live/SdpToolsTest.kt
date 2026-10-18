package io.github.abrahampaulj.pocketfuse.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpToolsTest {
    private val offer = listOf(
        "v=0",
        "o=- 1 2 IN IP4 127.0.0.1",
        "s=-",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 35 36",
        "a=mid:0",
        "a=rtpmap:96 VP8/90000",
        "a=rtcp-fb:96 nack",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=rtpmap:98 VP9/90000",
        "a=fmtp:98 profile-id=0",
        "a=rtpmap:99 rtx/90000",
        "a=fmtp:99 apt=98",
        "a=rtpmap:35 AV1/90000",
        "a=rtpmap:36 rtx/90000",
        "a=fmtp:36 apt=35",
        "a=candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host generation 0",
        "a=candidate:2 1 udp 2122194687 100.100.1.3 50001 typ host generation 0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=mid:2",
        "a=rtpmap:111 opus/48000/2",
    ).joinToString("\r\n", postfix = "\r\n")

    @Test
    fun keepsOnlyTheWantedVideoCodecsAndTheirRepairStreams() {
        val trimmed = SdpTools.keepVideoCodecs(offer, setOf("VP8"))
        assertTrue("m=video 9 UDP/TLS/RTP/SAVPF 96 97" in trimmed)
        assertTrue("a=rtpmap:96 VP8/90000" in trimmed)
        assertTrue("a=fmtp:97 apt=96" in trimmed)
        assertFalse("VP9" in trimmed)
        assertFalse("AV1" in trimmed)
        assertFalse("apt=35" in trimmed)
        assertTrue("a=rtpmap:111 opus/48000/2" in trimmed)
    }

    @Test
    fun neverOffersAv1SoTheServerCannotPickASoftwareDecodedCodec() {
        val trimmed = SdpTools.keepVideoCodecs(offer, SdpTools.PREFERRED_VIDEO.toSet())
        assertTrue("VP8" in trimmed && "VP9" in trimmed)
        assertFalse("AV1" in trimmed)
    }

    @Test
    fun ordersCodecsByPreference() {
        assertEquals(listOf("VP8", "VP9"), SdpTools.orderCodecs(listOf("AV1", "VP9", "H264", "vp8")))
        assertEquals(listOf("VP9"), SdpTools.orderCodecs(listOf("VP9", "H264")))
    }

    @Test
    fun countsHostCandidates() {
        assertEquals(2, SdpTools.countHostCandidates(offer))
        assertEquals(0, SdpTools.countHostCandidates("v=0\r\nm=video 9 RTP 96\r\n"))
    }
}
