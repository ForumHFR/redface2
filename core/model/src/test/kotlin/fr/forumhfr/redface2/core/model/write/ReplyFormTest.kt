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
}
