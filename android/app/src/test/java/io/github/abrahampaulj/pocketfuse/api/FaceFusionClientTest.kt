package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FaceFusionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: FaceFusionClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = FaceFusionClient(server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val tokens = """{"access_token":"ACCESS","refresh_token":"REFRESH"}"""

    @Test
    fun tokensTravelOnlyInTheAuthorizationHeader() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"assets":[]}"""))
        client.openSession("KEY")
        client.assets()

        val open = server.takeRequest()
        assertEquals("""{"api_key":"KEY"}""", open.body.readUtf8())
        assertNull(open.getHeader("Authorization"))
        val list = server.takeRequest()
        assertEquals("Bearer ACCESS", list.getHeader("Authorization"))
        assertFalse("token leaked into the URL", "ACCESS" in list.path!!)
    }

    @Test
    fun noKeyMeansAnEmptyBody() = runBlocking {
        server.enqueue(json(tokens, 201))
        client.openSession(null)
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test
    fun refreshRotatesBothTokens() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"access_token":"A2","refresh_token":"R2"}"""))
        client.openSession(null)
        client.refreshSession()
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("PUT", refresh.method)
        assertEquals("""{"refresh_token":"REFRESH"}""", refresh.body.readUtf8())
        assertEquals(Tokens("A2", "R2"), client.tokens)
    }

    @Test
    fun anExpiredSessionIsReportedAsLostWithTheServersMessageKept() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"message":"invalid access token"}""", 426))
        client.openSession(null)
        try {
            client.state()
            fail("426 did not throw")
        } catch (exception: ApiException) {
            assertEquals(426, exception.status)
            assertEquals("invalid access token", exception.serverMessage)
            assertTrue(exception.sessionLost)
            assertTrue(exception.userMessage.contains("expired"))
        }
    }

    @Test
    fun keeperStopsUsingALostSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(json("""{"name":"FaceFusion","version":"v4"}"""))
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"message":"invalid access token"}""", 401))
        val keeper = SessionKeeper(client, null, scope)
        assertTrue(keeper.open() is SessionState.Open)
        try {
            keeper.call { state() }
        } catch (_: ApiException) {
        }
        assertTrue(keeper.state.value is SessionState.Failed)
        assertNull(client.tokens)
        scope.cancel()
    }

    @Test
    fun uploadsUseTheFileFieldTheGuideNames() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"asset_ids":["a1"]}""", 201))
        client.openSession(null)
        val bytes = "jpeg".toByteArray()
        val ids = client.upload(AssetType.Target, listOf(UploadPart("face.jpg", "image/jpeg", bytes.size.toLong()) { ByteArrayInputStream(bytes) }))
        server.takeRequest()
        val upload = server.takeRequest()
        assertEquals(listOf("a1"), ids)
        assertEquals("/assets?type=target", upload.path)
        val body = upload.body.readUtf8()
        assertTrue("name=\"file\"; filename=\"face.jpg\"" in body)
    }

    @Test
    fun anUnreachableServerSaysSoInWords() = runBlocking {
        val dead = FaceFusionClient("http://127.0.0.1:9")
        try {
            dead.root()
            fail("no exception")
        } catch (exception: UnreachableException) {
            assertTrue(exception.userMessage.contains("Could not reach"))
        }
    }

    @Test
    fun websocketsCarryTheTokenAsSubprotocol() {
        runBlocking {
            server.enqueue(json(tokens, 201))
            client.openSession(null)
        }
        server.takeRequest()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val opened = CountDownLatch(1)
        val socket = client.openSocket("/metrics", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
        })
        opened.await(5, TimeUnit.SECONDS)
        val upgrade = server.takeRequest()
        assertEquals("/metrics", upgrade.path)
        assertEquals("access_token.ACCESS", upgrade.getHeader("Sec-WebSocket-Protocol"))
        socket.cancel()
    }
}
