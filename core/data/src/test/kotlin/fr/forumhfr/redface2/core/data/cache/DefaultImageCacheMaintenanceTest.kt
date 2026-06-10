package fr.forumhfr.redface2.core.data.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip test for the « Vider le cache des images » action (#314), mirroring
 * [DefaultTopicCacheMaintenanceTest]:
 *
 * 1. Build a REAL Coil [ImageLoader] with a real in-process [MemoryCache] and a real
 *    on-disk [DiskCache], seed both (an entry in memory, a committed editor on disk),
 *    and install it as the process singleton via [SingletonImageLoader.setUnsafe] —
 *    the same seam `PostRendererSmileyRoborazziTest` uses. The production code resolves
 *    the loader with `SingletonImageLoader.get(context)`, so the test exercises the
 *    exact lookup path used at runtime.
 * 2. Run `DefaultImageCacheMaintenance.clearImageCache()`.
 * 3. Assert both caches are empty afterwards.
 *
 * The cache-less and failure paths use MockK stubs of the [ImageLoader] interface:
 * Coil has no built-in "throwing cache" fixture, and hand-rolling full interface fakes
 * for MemoryCache/DiskCache would couple the test to every member of those interfaces.
 *
 * Robolectric so Coil's Android `ImageLoader.Builder(context)` can build — same pattern
 * as the topic-cache test (Room needed a context there, Coil needs one here).
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultImageCacheMaintenanceTest {

    private lateinit var context: Context
    private var diskCache: DiskCache? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Stop the disk cache's housekeeping and drop the singleton so the next test
        // starts from a clean slate — setUnsafe leaks across tests otherwise.
        diskCache?.shutdown()
        diskCache = null
        SingletonImageLoader.reset()
    }

    @Test
    fun `clearImageCache empties both the memory and the disk cache`() = runTest {
        val loader = newRealLoader()
        seedMemoryCache(loader)
        seedDiskCache(loader)
        SingletonImageLoader.setUnsafe(loader)
        val maintenance = DefaultImageCacheMaintenance(context, Dispatchers.Unconfined)

        maintenance.clearImageCache()

        val memoryCache = requireNotNull(loader.memoryCache)
        assertNull(
            "memory cache must be empty after clearImageCache",
            memoryCache.get(MemoryCache.Key(IMAGE_URL)),
        )
        assertTrue("memory cache keys must be empty", memoryCache.keys.isEmpty())
        assertEquals("memory cache size must be 0", 0L, memoryCache.size)
        val disk = requireNotNull(loader.diskCache)
        assertNull(
            "disk cache must be empty after clearImageCache",
            disk.openSnapshot(IMAGE_URL),
        )
        assertEquals("disk cache size must be 0", 0L, disk.size)
    }

    @Test
    fun `clearImageCache is idempotent — running it on empty caches does not throw`() = runTest {
        // No seed — straight to clear, twice. The UI button can be re-tapped right after a
        // successful clear; the second pass must be a harmless no-op, like the topic mirror.
        val loader = newRealLoader()
        SingletonImageLoader.setUnsafe(loader)
        val maintenance = DefaultImageCacheMaintenance(context, Dispatchers.Unconfined)

        maintenance.clearImageCache()
        maintenance.clearImageCache()

        assertEquals(0L, requireNotNull(loader.memoryCache).size)
        assertEquals(0L, requireNotNull(loader.diskCache).size)
    }

    @Test
    fun `clearImageCache tolerates a loader configured without caches`() = runTest {
        // Both caches are nullable on the ImageLoader contract; the null-safe clears must
        // simply skip them instead of crashing the maintenance action.
        val loader = mockk<ImageLoader> {
            every { memoryCache } returns null
            every { diskCache } returns null
        }
        SingletonImageLoader.setUnsafe(loader)
        val maintenance = DefaultImageCacheMaintenance(context, Dispatchers.Unconfined)

        maintenance.clearImageCache()
    }

    @Test
    fun `clearImageCache propagates a cache failure to the caller`() = runTest {
        // Contract pinned by the interface KDoc: failures must reach the caller so the
        // SettingsViewModel can surface ImageCacheClearResult.Failure instead of lying
        // with a success message.
        val failingMemoryCache = mockk<MemoryCache> {
            every { clear() } throws IllegalStateException("boom")
        }
        val loader = mockk<ImageLoader> {
            every { memoryCache } returns failingMemoryCache
            every { diskCache } returns null
        }
        SingletonImageLoader.setUnsafe(loader)
        val maintenance = DefaultImageCacheMaintenance(context, Dispatchers.Unconfined)

        val thrown = runCatching { maintenance.clearImageCache() }.exceptionOrNull()

        assertTrue(
            "the cache failure must propagate, got: $thrown",
            thrown is IllegalStateException,
        )
    }

    @Test
    fun `clearImageCache runs the clears through the injected IO dispatcher`() = runTest {
        // repos-must-wrap-io: the disk clear is file I/O, so the implementation must hop to
        // the injected dispatcher. A recording dispatcher proves withContext actually
        // dispatched (Unconfined would hide a missing withContext, cf. the project rule).
        val recordingDispatcher = RecordingDispatcher()
        val loader = newRealLoader()
        seedMemoryCache(loader)
        SingletonImageLoader.setUnsafe(loader)
        val maintenance = DefaultImageCacheMaintenance(context, recordingDispatcher)

        maintenance.clearImageCache()

        assertTrue(
            "clearImageCache must go through withContext(ioDispatcher)",
            recordingDispatcher.dispatchCount > 0,
        )
        assertEquals(0L, requireNotNull(loader.memoryCache).size)
    }

    /**
     * A real loader with both caches enabled, sized small but comfortably above the seeded
     * entries. Unique disk directory per loader so a journal left by a previous test can
     * never bleed in; the instance is remembered for [tearDown]'s `shutdown()`.
     */
    private fun newRealLoader(): ImageLoader {
        val memoryCache = MemoryCache.Builder()
            .maxSizeBytes(MEMORY_CACHE_MAX_BYTES)
            .build()
        val disk = DiskCache.Builder()
            .directory(File(context.cacheDir, "coil_test_disk_${System.nanoTime()}").toOkioPath())
            .maxSizeBytes(DISK_CACHE_MAX_BYTES)
            .build()
        diskCache = disk
        return ImageLoader.Builder(context)
            .memoryCache { memoryCache }
            .diskCache { disk }
            .build()
    }

    private fun seedMemoryCache(loader: ImageLoader) {
        val memoryCache = requireNotNull(loader.memoryCache)
        memoryCache.set(
            MemoryCache.Key(IMAGE_URL),
            MemoryCache.Value(ColorImage(SEED_COLOR, width = 16, height = 16, size = SEED_IMAGE_BYTES)),
        )
        // Sanity check: the seed actually landed.
        assertNotNull(memoryCache.get(MemoryCache.Key(IMAGE_URL)))
        assertEquals(SEED_IMAGE_BYTES, memoryCache.size)
    }

    private fun seedDiskCache(loader: ImageLoader) {
        val disk = requireNotNull(loader.diskCache)
        val editor = requireNotNull(disk.openEditor(IMAGE_URL))
        disk.fileSystem.write(editor.metadata) { writeUtf8("meta") }
        disk.fileSystem.write(editor.data) { writeUtf8("gif-bytes") }
        editor.commit()
        // Sanity check: the entry is readable and accounted for.
        requireNotNull(disk.openSnapshot(IMAGE_URL)).close()
        assertTrue("disk cache must account the seeded entry", disk.size > 0L)
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatchCount: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount += 1
            block.run()
        }
    }

    private companion object {
        const val IMAGE_URL = "https://forum.hardware.fr/images/perso/f/franzhermann.gif"
        val SEED_COLOR = 0xFF1565C0.toInt()
        const val SEED_IMAGE_BYTES = 1_024L
        const val MEMORY_CACHE_MAX_BYTES = 1L * 1024 * 1024
        const val DISK_CACHE_MAX_BYTES = 1L * 1024 * 1024
    }
}
