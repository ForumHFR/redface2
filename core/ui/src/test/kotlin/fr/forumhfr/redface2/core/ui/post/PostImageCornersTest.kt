package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners
import org.junit.Assert.assertEquals
import org.junit.Test

class PostImageCornersTest {

    @Test
    fun `corner shape follows every content image preference`() {
        assertEquals(RoundedCornerShape(8.dp), cornerShapeFor(PostImageCorners.ROUNDED))
        assertEquals(RoundedCornerShape(4.dp), cornerShapeFor(PostImageCorners.SOFT))
        assertEquals(RectangleShape, cornerShapeFor(PostImageCorners.SQUARE))
    }
}
