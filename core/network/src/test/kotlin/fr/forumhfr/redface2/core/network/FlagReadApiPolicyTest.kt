package fr.forumhfr.redface2.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FlagReadApiPolicyTest {
    @Test
    fun `only explicit collection intents use the bound and five second TCP policy`() = runTest {
        val limiter = FlagReadLimiter()
        val timeouts = mutableListOf<Int>()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                timeouts += chain.connectTimeoutMillis()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("{}".toResponseBody()).build()
            }.build()
        val api = HfrApiClient(client, client, "https://forum.hardware.fr/".toHttpUrl(),
            UnconfinedTestDispatcher(testScheduler), FlagReadExecutor(limiter, FlagReadJitter()))
        val session = FlagReadSession { testScheduler.currentTime * 1_000_000 }
        withContext(session) {
            api.getCategories()
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED)
            api.getTopicList(23, null, useAuth = true)
            api.getTopicMetadata(23, 35395) // Topic navigation is outside the closed intent list.
        }
        api.getCategories()
        api.getCategoryFlagTopics(23, HfrRestFlagBucket.FAVORITES) // Menu/Forum lookup, no session.
        api.getTopicList(23, null, useAuth = true)
        assertEquals(listOf(5_000, 5_000, 5_000, 15_000, 15_000, 15_000, 15_000), timeouts)
        assertEquals(3L, session.counters().getValue("logicalOperations"))
        assertEquals(3L, session.counters().getValue("applicationCalls"))
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `body read failure closes response before releasing permit and never retries`() = runTest {
        val limiter = FlagReadLimiter()
        var closed = false
        var starts = 0
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength(): Long = -1
            private val input = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    assertEquals(1, limiter.heldPermits)
                    throw IOException("interrupted body")
                }
                override fun close() {
                    assertEquals(1, limiter.heldPermits)
                    closed = true
                    super.close()
                }
            }.buffer()
            override fun source(): BufferedSource = input
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            starts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val api = HfrApiClient(client, client, "https://forum.hardware.fr/".toHttpUrl(),
            UnconfinedTestDispatcher(testScheduler), FlagReadExecutor(limiter, FlagReadJitter()))
        val session = FlagReadSession()
        session.succeeded(retry = false) // Open circuit; body errors must still never replay.
        val error = runCatching { withContext(session) { api.getCategories() } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(closed)
        assertEquals(1, starts)
        assertEquals(0L, session.counters().getValue("retriesAttempted"))
        assertEquals(0, limiter.heldPermits)
    }
}
