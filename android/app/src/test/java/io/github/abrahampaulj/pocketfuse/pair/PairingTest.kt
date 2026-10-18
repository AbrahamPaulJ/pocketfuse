package io.github.abrahampaulj.pocketfuse.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun readsTheCompanionsLink() {
        val config = Pairing.parse("pocketfuse://pair?host=100.64.0.2&port=8000&key=abc%2Bdef_-")!!
        assertEquals("100.64.0.2", config.host)
        assertEquals(8000, config.port)
        assertEquals("abc+def_-", config.apiKey)
        assertEquals("http://100.64.0.2:8000", config.baseUrl)
    }

    @Test
    fun aLinkWithoutKeyPairsWithoutKey() {
        assertNull(Pairing.parse("pocketfuse://pair?host=192.168.1.20&port=8000")!!.apiKey)
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(Pairing.parse("pocketfuse://other?host=a&port=1"))
        assertNull(Pairing.parse("pocketfuse://pair?host=a&port=99999"))
        assertNull(Pairing.parse("pocketfuse://pair?port=8000"))
        assertNull(Pairing.parse("https://example.com/pair?host=a&port=1"))
        assertNull(Pairing.parse("not an address"))
    }

    @Test
    fun manualEntryAcceptsCommonShapes() {
        assertEquals(ServerConfig("192.168.1.20", 8000, null), Pairing.parseManual("192.168.1.20", ""))
        assertEquals(ServerConfig("192.168.1.20", 9000, "k"), Pairing.parseManual("http://192.168.1.20:9000/", "k"))
        assertEquals(ServerConfig("pc.tailnet.ts.net", 443, null, secure = true), Pairing.parseManual("https://pc.tailnet.ts.net:443", null))
        assertEquals("http://[fd7a::1]:8000", Pairing.parseManual("[fd7a::1]:8000", null)!!.baseUrl)
    }

    @Test
    fun classifiesWhereTheServerLives() {
        assertEquals(AddressKind.Tailnet, classify("100.100.1.2"))
        assertEquals(AddressKind.Lan, classify("192.168.0.20"))
        assertEquals(AddressKind.Lan, classify("10.0.0.5"))
        assertEquals(AddressKind.Lan, classify("172.20.1.1"))
        assertEquals(AddressKind.Loopback, classify("127.0.0.1"))
        assertEquals(AddressKind.Public, classify("8.8.8.8"))
        assertEquals(AddressKind.Name, classify("pc.local"))
        assertTrue(ServerConfig("8.8.8.8", 8000, null).isPublicAddress)
    }
}
