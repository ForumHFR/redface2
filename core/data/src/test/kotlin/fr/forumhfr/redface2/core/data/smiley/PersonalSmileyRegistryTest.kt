package fr.forumhfr.redface2.core.data.smiley

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalSmileyRegistryTest {

    @Test
    fun `default FIFO bound evicts the oldest entry after 1024 names`() {
        val registry = PersonalSmileyRegistry()
        val stamp = registry.capture()

        repeat(PersonalSmileyRegistry.DEFAULT_MAX_ENTRIES + 1) { index ->
            registry.register(stamp, "name-$index", "https://example.com/$index.gif")
        }

        assertEquals(PersonalSmileyRegistry.DEFAULT_MAX_ENTRIES, registry.size)
        assertNull(registry.resolve("name-0"))
        assertEquals("https://example.com/1.gif", registry.resolve("name-1"))
        assertEquals("https://example.com/1024.gif", registry.resolve("name-1024"))
    }

    @Test
    fun `concurrent readers and writers keep the registry bounded and coherent`() {
        val registry = PersonalSmileyRegistry.createForTest(maxEntries = 64)
        val stamp = registry.capture()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val futures = (0 until 8).map { worker ->
            executor.submit {
                start.await()
                repeat(300) { index ->
                    val name = "$worker-$index"
                    val url = "https://example.com/$name.gif"
                    registry.register(stamp, name, url)
                    registry.resolve(name)
                }
            }
        }

        try {
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(64, registry.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a response captured before purge cannot refill the registry`() {
        val registry = PersonalSmileyRegistry()
        val outgoingSession = registry.capture()

        registry.clearAndAdvanceGeneration()
        registry.register(outgoingSession, "stale", "https://example.com/stale.gif")

        assertNull(registry.resolve("stale"))
        assertEquals(0, registry.size)
    }

    @Test
    fun `base name and numeric variant remain distinct exact keys`() {
        val registry = PersonalSmileyRegistry()
        val stamp = registry.capture()

        registry.register(stamp, "same", "https://example.com/base.gif")
        registry.register(stamp, "same:2", "https://example.com/variant.gif")

        assertEquals("https://example.com/base.gif", registry.resolve("same"))
        assertEquals("https://example.com/variant.gif", registry.resolve("same:2"))
    }
}
