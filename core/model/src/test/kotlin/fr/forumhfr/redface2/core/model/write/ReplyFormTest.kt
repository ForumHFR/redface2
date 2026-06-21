package fr.forumhfr.redface2.core.model.write

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
