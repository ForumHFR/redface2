package fr.forumhfr.redface2

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedAnimatedDecoderFactoryTest {

    private val fetchResult = mockk<SourceFetchResult>()
    private val options = mockk<Options>()
    private val imageLoader = mockk<ImageLoader>()
    private val decodeResult = mockk<DecodeResult>()

    @Test
    fun `nine decoders from one factory share a single permit by default`() = runTest {
        val releases = List(9) { CompletableDeferred<Unit>() }
        var created = 0
        var started = 0
        var active = 0
        var maxActive = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                val index = created++
                Decoder {
                    started++
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        releases[index].await()
                        decodeResult
                    } finally {
                        active--
                    }
                }
            },
        )
        val decoders = List(9) { factory.newDecoder() }
        val jobs = decoders.map { async { it.decode() } }

        runCurrent()

        assertEquals(9, created)
        for (index in jobs.indices) {
            assertEquals(1, active)
            assertEquals(index + 1, started)
            assertFalse(jobs[index].isCompleted)
            releases[index].complete(Unit)
            runCurrent()
            assertSame(decodeResult, jobs[index].await())
        }
        assertEquals(1, maxActive)
        assertEquals(0, active)
    }

    @Test
    fun `the permit capacity can be configured`() = runTest {
        val release = CompletableDeferred<Unit>()
        var started = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                Decoder {
                    started++
                    release.await()
                    decodeResult
                }
            },
            permits = 2,
        )
        val jobs = List(3) { async { factory.newDecoder().decode() } }

        runCurrent()
        assertEquals(2, started)

        release.complete(Unit)
        jobs.forEach { assertSame(decodeResult, it.await()) }
        assertEquals(3, started)
    }

    @Test
    fun `an exception releases the permit and propagates unchanged`() = runTest {
        val failure = IOException("decode failed")
        var calls = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                Decoder {
                    if (calls++ == 0) throw failure
                    decodeResult
                }
            },
        )

        try {
            factory.newDecoder().decode()
            fail("the decoder exception must propagate")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertSame(decodeResult, factory.newDecoder().decode())
        assertEquals(2, calls)
    }

    @Test
    fun `cancelling an active decode releases the permit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                Decoder {
                    if (calls++ == 0) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    decodeResult
                }
            },
        )
        val active = launch { factory.newDecoder().decode() }
        entered.await()

        active.cancelAndJoin()

        assertTrue(active.isCancelled)
        assertSame(decodeResult, factory.newDecoder().decode())
        assertEquals(2, calls)
    }

    @Test
    fun `cancelling a waiting decode neither loses nor releases a permit`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                Decoder {
                    calls++
                    release.await()
                    decodeResult
                }
            },
        )
        val active = async { factory.newDecoder().decode() }
        val waiting = async { factory.newDecoder().decode() }
        runCurrent()
        assertEquals(1, calls)

        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        val next = async { factory.newDecoder().decode() }
        runCurrent()
        assertEquals("the active decode still owns the permit", 1, calls)
        assertFalse(next.isCompleted)

        release.complete(Unit)
        assertSame(decodeResult, active.await())
        assertSame(decodeResult, next.await())
        assertEquals("the cancelled waiter must never decode", 2, calls)
    }

    @Test
    fun `an unrecognized source returns null and forwards the original arguments`() {
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { result, requestOptions, loader ->
                assertSame(fetchResult, result)
                assertSame(options, requestOptions)
                assertSame(imageLoader, loader)
                null
            },
        )

        assertNull(factory.create(fetchResult, options, imageLoader))
    }

    private fun BoundedAnimatedDecoderFactory.newDecoder(): Decoder =
        requireNotNull(create(fetchResult, options, imageLoader))
}
