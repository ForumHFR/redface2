package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners

internal fun cornerShapeFor(corners: PostImageCorners): Shape = when (corners) {
    PostImageCorners.ROUNDED,
    PostImageCorners.SOFT,
    -> RoundedCornerShape(corners.radiusDp.dp)
    PostImageCorners.SQUARE -> RectangleShape
}
