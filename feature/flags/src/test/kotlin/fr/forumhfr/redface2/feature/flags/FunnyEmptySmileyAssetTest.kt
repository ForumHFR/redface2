package fr.forumhfr.redface2.feature.flags

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #740 — the « états vides humoristiques » smiley is a LOCAL asset, never a network fetch. The
 * `AsyncImage` model is [FUNNY_EMPTY_SMILEY_RES], a packaged `res/raw` resource ; these tests pin
 * that contract (resource type, intact GIF payload, decodable bounds) so a regression back to a
 * remote URL — or a corrupted / replaced binary — fails in CI instead of on a device offline.
 *
 * Expected payload = the HFR perso smiley `images/perso/eric le looser.gif` fetched unmodified
 * (GIF89a, 47 × 50 px, 2 288 bytes — provenance in the KDoc of [FUNNY_EMPTY_SMILEY_RES]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FunnyEmptySmileyAssetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the funny empty-state smiley is a packaged raw resource, not a URL`() {
        val resources = context.resources
        assertEquals("raw", resources.getResourceTypeName(FUNNY_EMPTY_SMILEY_RES))
        assertEquals("smiley_eric_le_looser", resources.getResourceEntryName(FUNNY_EMPTY_SMILEY_RES))
    }

    @Test
    fun `the packaged asset is the intact HFR GIF`() {
        val bytes = context.resources.openRawResource(FUNNY_EMPTY_SMILEY_RES).use { it.readBytes() }
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        // Logical screen descriptor: little-endian u16 width @6, height @8.
        assertEquals(47, bytes.u16le(6))
        assertEquals(50, bytes.u16le(8))
        assertEquals(EXPECTED_SIZE_BYTES, bytes.size)
    }

    @Test
    fun `the packaged asset decodes to the expected bounds`() {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.resources.openRawResource(FUNNY_EMPTY_SMILEY_RES).use {
            BitmapFactory.decodeStream(it, null, options)
        }
        assertEquals(47, options.outWidth)
        assertEquals(50, options.outHeight)
    }

    private fun ByteArray.u16le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val EXPECTED_SIZE_BYTES = 2288
    }
}
