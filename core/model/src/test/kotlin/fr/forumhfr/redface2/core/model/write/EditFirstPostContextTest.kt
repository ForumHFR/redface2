package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `EditFirstPostContext.init` subcat contract (#213). UNLIKE
 * `ReplyContext` / `EditPostContext` (relaxed to `subcat >= 0` so a category
 * without sub-category is postable), FP edit keeps `subcat > 0`: the FP
 * recategorise flow is not relaxed for 0-subcat categories (contract not yet
 * captured — #213 follow-up). The FP edit always lives on page 1.
 */
class EditFirstPostContextTest {

    @Test
    fun `positive subcat is accepted`() {
        val context = EditFirstPostContext(cat = 23, subcat = 550, topicId = 35395, page = 1, numreponse = 5)
        assertEquals(550, context.subcat)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subcat zero is rejected — FP edit needs a real sub-category (unlike reply)`() {
        EditFirstPostContext(cat = 32, subcat = 0, topicId = 1, page = 1, numreponse = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subcat sentinel -1 is rejected`() {
        EditFirstPostContext(cat = 23, subcat = -1, topicId = 1, page = 1, numreponse = 5)
    }
}
