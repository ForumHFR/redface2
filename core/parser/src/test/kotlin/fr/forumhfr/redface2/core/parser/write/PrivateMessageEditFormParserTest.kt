package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageEditFormParserTest {
    private val parser = ReplyFormParser()
    private val context = PrivateMessageEditContext(3000003, 1, 1980000302)

    @Test
    fun `one to one edit preserves quoted parent and raw BBCode without recipients`() {
        val form = parser.parseEditPost(fixture("private_message_edit_form.html"), context).getOrThrow()
        assertEquals("TESTHASH", form.hashCheck)
        assertEquals("Sujet prive de test", form.sujet)
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("3000003", form.hiddenFields["post"])
        assertEquals("1980000302", form.hiddenFields["numreponse"])
        assertEquals("1980000301", form.hiddenFields["parents"])
        assertEquals("", form.hiddenFields["numrep"])
        assertEquals("0", form.hiddenFields["subcat"])
        assertEquals("", form.hiddenFields["stickold"])
        assertEquals("", form.hiddenFields["cache"])
        assertEquals("1100", form.hiddenFields["verifrequet"])
        assertEquals("1", form.msgIcon)
        assertEquals(990002, form.userId)
        assertTrue(form.initialContent.startsWith("[quotemsg=1980000301,1,990002][b]"))
        assertTrue(form.initialContent.endsWith("[:testsmiley:2][/quotemsg]"))
        assertEquals(14, form.initialContent.count { it == '\n' })
        assertFalse(form.isAnonymous)
        assertFalse(form.isSubjectEditable)
        assertFalse(form.options.signatureEnabled)
        assertFalse(form.options.emailNotificationEnabled)
        assertFalse(form.hiddenFields.containsKey("password"))
        assertFalse(form.hiddenFields.containsKey("delete"))
        assertFalse(form.canManageRecipients)
        assertNull(form.recipientsRoster)
        assertEquals(
            listOf(
                "hash_check", "subcat", "parents", "post", "stickold", "new", "cat", "numreponse",
                "numrep", "page", "verifrequet", "p", "sondage", "sond", "cache", "owntopic", "config",
                "sujet", "pseudo", "password", "MsgIcon", "search_smilies", "ColorUsedMem", "content_form",
                "wysiwyg", "submit", "signature", "smiley", "emaill", "delete",
            ),
            form.submitFieldOrder,
        )
    }

    @Test
    fun `group edit preserves subscription without a recipient field`() {
        val form = parser.parseEditPost(
            fixture("private_message_dt_edit_form.html"), PrivateMessageEditContext(3000004, 1, 1980000402),
        ).getOrThrow()
        assertEquals("Sujet prive collectif fictif", form.sujet)
        assertEquals("Message prive collectif fictif (contenu remplace).", form.initialContent)
        assertEquals("", form.hiddenFields["parents"])
        assertTrue(form.options.emailNotificationEnabled)
        assertFalse(form.options.signatureEnabled)
        assertFalse(form.isSubjectEditable)
        assertNull(form.recipientsRoster)
        assertFalse(form.hiddenFields.containsKey("dest"))
        assertFalse(form.hiddenFields.containsKey("newdest"))
    }

    @Test
    fun `first message exposes editable subject and no subcategory`() {
        val form = parser.parseEditPost(
            fixture("private_message_edit_first_post_form.html"), context.copy(numreponse = 1980000301),
        ).getOrThrow()
        assertEquals("Sujet prive de test", form.sujet)
        assertTrue(form.isSubjectEditable)
        assertTrue(form.options.signatureEnabled)
        assertFalse(form.options.emailNotificationEnabled)
        assertFalse(form.hiddenFields.containsKey("subcat"))
        assertFalse(form.submitFieldOrder.contains("subcat"))
        assertEquals("", form.hiddenFields["parents"])
        assertTrue(form.initialContent.startsWith("[b]"))
        assertTrue(form.submitFieldOrder.indexOf("sujet") > form.submitFieldOrder.indexOf("password"))
    }

    @Test
    fun `public category wrong thread and wrong message are rejected`() {
        val html = fixture("private_message_edit_form.html")
        assertTrue(parser.parseEditPost(fixture("write_edit_form_test_post.html"), context).isFailure)
        assertTrue(parser.parseEditPost(html, context.copy(threadId = 3000004)).isFailure)
        assertTrue(parser.parseEditPost(html, context.copy(numreponse = 1980000301)).isFailure)
    }

    @Test
    fun `private reply composer is not an edit form`() {
        assertTrue(parser.parseEditPost(fixture("private_message_reply_form.html"), context).isFailure)
    }

    @Test
    fun `nonempty quote reference is rejected even with matching edit coordinates`() {
        val html = fixture("private_message_edit_form.html")
        val changed = html.replace("name=\"numrep\" value=\"\"", "name=\"numrep\" value=\"1980000301\"")
        assertTrue(changed != html)
        assertTrue(parser.parseEditPost(changed, context).isFailure)
    }

    @Test
    fun `missing textarea fails while a present empty textarea is accepted`() {
        val html = fixture("private_message_edit_form.html")
        val textarea = Regex("""(?s)(<textarea[^>]*name="content_form"[^>]*>).*?(</textarea>)""")
        assertTrue(textarea.containsMatchIn(html))
        val missing = parser.parseEditPost(html.replace(textarea, ""), context)
        assertTrue(missing.isFailure)
        assertEquals("content_form textarea missing from edit form", missing.exceptionOrNull()?.message)
        assertEquals("", parser.parseEditPost(html.replace(textarea, "$1$2"), context).getOrThrow().initialContent)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$name"),
    ).bufferedReader().use { it.readText() }
}
