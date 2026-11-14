package fr.forumhfr.redface2.core.model.write

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #606 — pins the owner-detection contract on [ReplyForm]. HFR serves `<input name="newdest">`
 * only to the owner of a DT/MultiMP, so [ReplyForm.manageableRecipients] / [canManageRecipients]
 * read straight off the parsed hidden fields : present ⇒ owner can manage members, absent ⇒ a
 * one-to-one MP / topic reply / simple participant.
 */
class ReplyFormTest {

    @Test
    fun `toString exposes only sizes and flags without private form values`() {
        val token = "private-csrf-token-1625"
        val subject = "private-subject-1625"
        val pseudo = "private-pseudo-1625"
        val body = "private-body-1625"
        val hiddenFields = mapOf(
            "hash_check" to token,
            "sujet" to subject,
            "pseudo" to pseudo,
            "content_form" to body,
            "newdest" to "private-recipient-1625",
            "extra" to "private-hidden-value-1625",
        )
        val icon = "private-icon-1625"
        val roster = "private-roster-1625"
        val userId = 7654321
        val smiley = EditorSmiley(
            token = ":jap:",
            imageUrl = "https://example.test/private-favorite-1625.gif",
            source = EditorSmileySource.HFR_FAVORITE,
        )
        val replyForm = ReplyForm(
            hashCheck = token,
            sujet = subject,
            hiddenFields = hiddenFields,
            isAnonymous = false,
            initialContent = body,
            options = ReplyFormOptions(
                signatureEnabled = true,
                smileyDisabled = true,
                emailNotificationEnabled = true,
            ),
            msgIcon = icon,
            userId = userId,
            recipientsRoster = roster,
            favoriteSmileys = listOf(smiley),
            isSubjectEditable = true,
            submitFieldOrder = hiddenFields.keys.toList(),
        )

        val rendered = replyForm.toString()

        val secrets = hiddenFields.values + listOf(icon, roster, userId.toString(), smiley.token, smiley.imageUrl)
        secrets.forEach { secret ->
            assertFalse("toString must not expose private form values", rendered.contains(secret))
        }
        assertEquals(
            "ReplyForm(hiddenFieldCount=6, isAnonymous=false, initialContentLength=${body.length}, " +
                "isSubjectEditable=true)",
            rendered,
        )
    }

    private fun form(hiddenFields: Map<String, String>): ReplyForm = ReplyForm(
        hashCheck = "h",
        sujet = "s",
        hiddenFields = hiddenFields,
        isAnonymous = false,
    )

    @Test
    fun `a form without newdest exposes no manageable recipients`() {
        val replyForm = form(mapOf("cat" to "prive", "post" to "4242424"))
        assertNull(replyForm.manageableRecipients)
        assertFalse(replyForm.canManageRecipients)
    }

    @Test
    fun `a form with newdest surfaces the prefilled CSV verbatim`() {
        val replyForm = form(
            mapOf(
                "cat" to "prive",
                "post" to "4242424",
                "newdest" to "alice, bob, Bébé Yoda, stitch+, Administration",
            ),
        )
        assertTrue(replyForm.canManageRecipients)
        assertEquals("alice, bob, Bébé Yoda, stitch+, Administration", replyForm.manageableRecipients)
    }

    @Test
    fun `recipientsRoster defaults to null and is independent of canManageRecipients`() {
        // #618 — recipientsRoster is the parser-supplied read-only roster; on a hand-built form it
        // defaults to null and does NOT affect the owner-detection contract.
        val replyForm = form(mapOf("newdest" to "alice, bob"))
        assertNull(replyForm.recipientsRoster)
        assertTrue("newdest still drives manageability", replyForm.canManageRecipients)
    }

    @Test
    fun `a non-owner form can carry a roster without being manageable`() {
        // #618 — the parser sets recipientsRoster from a read-only span for a participant: roster
        // present, but no newdest → not manageable.
        val replyForm = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf("cat" to "prive", "post" to "4242424"),
            isAnonymous = false,
            recipientsRoster = "TestOwner, alice, bob",
        )
        assertEquals("TestOwner, alice, bob", replyForm.recipientsRoster)
        assertFalse("no newdest input → cannot manage", replyForm.canManageRecipients)
        assertNull(replyForm.manageableRecipients)
    }
}
