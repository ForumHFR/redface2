package fr.forumhfr.redface2.core.ui.loader

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import fr.forumhfr.redface2.core.ui.R

/**
 * #728 — the « redface » loader face: the **real HFR redface** rendered from a licence-clean vector
 * asset (`ic_redface`, the SVG provided by XaTriX) instead of the earlier hand-drawn approximation
 * (rejected at dogfood — « on en est loin »). It is the visual that emerges + rolls in the
 * pull-to-refresh puck ([RedfacePullPuck]).
 *
 * [rotationDegrees] tumbles the whole face (disc + eyes + mouth rotate together) so it reads as
 * « rolling on itself » when driven by the pull distance — read inside the [graphicsLayer] lambda so
 * the spin stays a draw-phase transform (no relayout). The vector carries its own colours (red disc,
 * dark outline), so it is theme-agnostic and stays recognisably « redface » on light / dark / AMOLED
 * puck surfaces — no tint needed.
 */
@Composable
fun RedfaceFace(
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
) {
    Image(
        painter = painterResource(R.drawable.ic_redface),
        contentDescription = null,
        modifier = modifier.graphicsLayer { rotationZ = rotationDegrees },
    )
}
