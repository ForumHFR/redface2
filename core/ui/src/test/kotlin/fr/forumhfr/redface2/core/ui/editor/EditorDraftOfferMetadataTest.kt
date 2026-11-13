package fr.forumhfr.redface2.core.ui.editor

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v0.7.8 ED-40: metadata belongs to each offered version, never to Q's enqueue date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
class EditorDraftOfferMetadataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-12T17:42:00Z").toEpochMilli()

    @Test fun `date uses minutes hours yesterday days then calendar date`() {
        val cases = listOf(
            5 * 60_000L to "il y a 5 min",
            3 * 3_600_000L to "il y a 3 h",
            86_400_000L to "hier à 18:42",
            3 * 86_400_000L to "il y a 3 j",
            8 * 86_400_000L to "le 04/11 à 18:42",
        )
        cases.forEach { (age, expected) ->
            assertEquals(expected, editorDraftOfferDate(now - age, now, zone).resolve(context))
        }
    }

    @Test fun `excerpt hides BBCode and replaces a complete nested quote with its author`() {
        val body = "[b]Bonjour[/b] [quotemsg=101,1,9][b]Alice a écrit :[/b]" +
            "[quotemsg=202]nested[/quotemsg]quoted secret[/quotemsg] [i]suite[/i]"
        assertEquals("Bonjour Citation de Alice suite", editorDraftOfferExcerpt(body, context))
        assertEquals("Citation de Bob", editorDraftOfferExcerpt("[quote=Bob]secret[/quote]", context))
    }
}
