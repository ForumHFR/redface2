package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PostRendererSmileyCollisionTest {

    @Test
    fun `E8 builtin ignores the measured image sharing its URL in the paragraph`() {
        val url = "https://forum-images.hardware.fr/icones/smilies/jap.gif"
        val smiley = PostInline.Smiley(SmileyKind.Builtin(":jap:"), url)
        val inlines = listOf(smiley, PostInline.Text(" "), PostInline.InlineImage(url, null))
        val measured = (collectMeasurableSmileyUrls(inlines) + collectMeasurableImageUrls(inlines))
            .associateWith { IntSize(80, 60) }

        val box = smileyDisplayBox(smiley, measured, maxWidthSp = 300)

        assertEquals(16.sp, box.placeholderWidth)
        assertEquals(16.sp, box.placeholderHeight)
    }

    @Test
    fun `E8 perso still uses the measured image sharing its URL`() {
        val url = "https://forum-images.hardware.fr/images/perso/test.gif"
        val smiley = PostInline.Smiley(SmileyKind.Perso("test"), url)

        val box = smileyDisplayBox(smiley, mapOf(url to IntSize(40, 30)), maxWidthSp = 300)

        assertEquals(40.sp, box.placeholderWidth)
        assertEquals(30.sp, box.placeholderHeight)
    }
}
