package io.github.abrahampaulj.pocketfuse.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FaceFusionClientTest {
    @get:Rule
    val temp = TemporaryFolder()

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
        // v4-challenge: the refresh token expired with the session.
        server.enqueue(json("""{"message":"something went wrong"}""", 401))
        val keeper = SessionKeeper(client, null, scope)
        assertTrue(keeper.open() is SessionState.Open)
        try {
            keeper.call { state() }
        } catch (_: ApiException) {
        }
        assertTrue(keeper.state.value is SessionState.Failed)
        assertNull(client.tokens)
        assertEquals(4, server.requestCount)
        scope.cancel()
    }

    @Test
    fun aCallTurnedAwayIsSentAgainAfterARefresh() = runBlocking {
        // v4 since 647be8b: the access token expires at half time, the refresh token still renews the session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(json("""{"name":"FaceFusion","version":"v4"}"""))
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"message":"invalid access token"}""", 401))
        server.enqueue(json("""{"access_token":"A2","refresh_token":"R2"}"""))
        server.enqueue(json("{}"))
        val keeper = SessionKeeper(client, null, scope)
        assertTrue(keeper.open() is SessionState.Open)
        keeper.call { state() }
        assertTrue(keeper.state.value is SessionState.Open)
        assertEquals(Tokens("A2", "R2"), client.tokens)
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer ACCESS", server.takeRequest().getHeader("Authorization"))
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("Bearer A2", server.takeRequest().getHeader("Authorization"))
        scope.cancel()
    }

    @Test
    fun aCallCaughtByATokenRotationIsSentAgainWithTheNewToken() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(json("""{"name":"FaceFusion","version":"v4"}"""))
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"message":"invalid access token"}""", 401))
        server.enqueue(json("{}"))
        val keeper = SessionKeeper(client, null, scope)
        assertTrue(keeper.open() is SessionState.Open)
        var attempts = 0
        keeper.call {
            attempts += 1
            try {
                state()
            } finally {
                // The refresh lands while the first request, still carrying the old token, is in flight.
                if (attempts == 1) client.tokens = Tokens("A2", "R2")
            }
        }
        assertEquals(2, attempts)
        assertTrue(keeper.state.value is SessionState.Open)
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer ACCESS", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer A2", server.takeRequest().getHeader("Authorization"))
        scope.cancel()
    }

    @Test
    fun aSecondRefreshJoinsTheOneInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(json("""{"name":"FaceFusion","version":"v4"}"""))
        server.enqueue(json(tokens, 201))
        server.enqueue(json("""{"access_token":"A2","refresh_token":"R2"}"""))
        val keeper = SessionKeeper(client, null, scope)
        assertTrue(keeper.open() is SessionState.Open)
        val first = launch { keeper.refreshNow() }
        val second = launch { keeper.refreshNow() }
        first.join()
        second.join()
        assertEquals(3, server.requestCount)
        assertEquals(Tokens("A2", "R2"), client.tokens)
        assertTrue(keeper.state.value is SessionState.Open)
        scope.cancel()
    }

    @Test
    fun deletingEveryAssetIsOneDeleteOnAssets() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(MockResponse().setResponseCode(200))
        client.openSession(null)
        client.deleteAssets()
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/assets", delete.path)
        assertEquals("Bearer ACCESS", delete.getHeader("Authorization"))
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
    fun aDownloadCutShortLeavesNoFileBehind() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(MockResponse().setBody("x".repeat(64 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        client.openSession(null)
        val into = File(temp.root, "job.mp4")
        try {
            client.download("a1", into)
            fail("a cut download did not throw")
        } catch (_: UnreachableException) {
        }
        assertFalse("truncated result kept", into.exists())
        assertFalse("partial file kept", File(into.path + FaceFusionClient.PARTIAL_SUFFIX).exists())
    }

    @Test
    fun aCompleteDownloadLandsUnderItsFinalName() = runBlocking {
        server.enqueue(json(tokens, 201))
        server.enqueue(MockResponse().setBody("video bytes"))
        client.openSession(null)
        val into = File(temp.root, "outputs/job.mp4")
        client.download("a1", into)
        assertEquals("video bytes", into.readText())
        assertFalse(File(into.path + FaceFusionClient.PARTIAL_SUFFIX).exists())
    }

    @Test
    fun progressIsPassedOnOncePerWholePercent() {
        // okio moves 8 KiB per read: a 100 MiB upload is 12,800 reads, and each used to post a notification.
        val reported = mutableListOf<Float>()
        val progress = ProgressThrottle { reported += it }
        val total = 100L * 1024 * 1024
        var done = 0L
        while (done < total) {
            done = minOf(done + 8192, total)
            progress.report(done, total)
        }
        assertEquals(101, reported.size)
        assertEquals(1f, reported.last(), 0f)
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
