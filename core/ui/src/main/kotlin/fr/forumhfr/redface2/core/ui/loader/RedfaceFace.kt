package fr.forumhfr.redface2.core.ui.loader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/**
 * #7 — the « redface » loader face, an ORIGINAL little round grinning face drawn entirely in Compose
 * (NOT the proprietary HFR `redface.gif` — that is a third-party sprite and this app is GPL-3.0-only;
 * an original drawing is licence-clean, crisp at any size and theme-tintable, XaTriX's choice). It is
 * the visual that emerges + rolls in the pull-to-refresh puck ([RedfacePuck]).
 *
 * [rotationDegrees] tumbles the whole face (eyes + grin rotate together) so it reads as « rolling on
 * itself » when driven by the pull distance. [tint] is the face fill — defaults to the redface coral so
 * the loader keeps its identity on every tab/theme, but can be themed/tinted by the caller.
 */
@Composable
fun RedfaceFace(
    modifier: Modifier = Modifier,
    tint: Color = RedfaceCoral,
    rotationDegrees: Float = 0f,
) {
    Canvas(modifier = modifier.graphicsLayer { rotationZ = rotationDegrees }) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)

        // Face disc.
        drawCircle(color = tint, radius = r, center = c)

        // Eyes — two dark dots in the upper third.
        val eyeRadius = r * 0.14f
        val eyeOffsetX = r * 0.40f
        val eyeOffsetY = r * 0.22f
        drawCircle(RedfaceInk, eyeRadius, Offset(c.x - eyeOffsetX, c.y - eyeOffsetY))
        drawCircle(RedfaceInk, eyeRadius, Offset(c.x + eyeOffsetX, c.y - eyeOffsetY))

        // Grin — a lower arc (a smile) stroked across the face.
        val grinInset = r * 0.42f
        val grinRect = Rect(
            offset = Offset(c.x - grinInset, c.y - grinInset * 0.55f),
            size = Size(grinInset * 2f, grinInset * 1.7f),
        )
        drawArc(
            color = RedfaceInk,
            startAngle = GRIN_START_ANGLE,
            sweepAngle = GRIN_SWEEP_ANGLE,
            useCenter = false,
            topLeft = grinRect.topLeft,
            size = grinRect.size,
            style = Stroke(width = r * 0.13f, cap = StrokeCap.Round),
        )
    }
}

// A friendly downward (smiling) arc: from ~25° clockwise through ~130°, i.e. the bottom of the face.
private const val GRIN_START_ANGLE = 25f
private const val GRIN_SWEEP_ANGLE = 130f

/** The redface identity colour — a warm coral red, legible on the light/dark/AMOLED puck surfaces. */
val RedfaceCoral = Color(0xFFE8584E)

/** Eyes + grin ink — a deep warm brown, softer than pure black on the coral face. */
private val RedfaceInk = Color(0xFF3A1410)
