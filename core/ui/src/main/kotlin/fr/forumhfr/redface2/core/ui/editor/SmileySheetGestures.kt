package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity

/** Lives on the sheet, which outlives the combinedClickable cell removed by opening the detail. */
internal class SmileySheetGestures {
    var blocked = false
        private set
    var pressed = false
        private set

    /** Installed only by gesture tests; no per-pointer logging in production. */
    var trace: ((String) -> Unit)? = null

    fun openingDetail() {
        blocked = pressed
        trace?.invoke("openingDetail pressed=$pressed blocked=$blocked")
    }

    fun pointerEvent(pressed: Boolean, newDown: Boolean): Boolean {
        // Keep the latch through UP, including the old scrollable's asynchronous post-fling.
        // A new gesture (not an additional finger) releases it before M3 sees the new DOWN.
        if (newDown) blocked = false
        this.pressed = pressed
        return blocked
    }

    fun cancelled() {
        pressed = false
        // A subtree/detector cancellation need not mean that the physical finger is up.
        // Keep an armed latch until a genuine new DOWN, just as after a normal UP.
        trace?.invoke("cancelled pressed=$pressed blocked=$blocked")
    }
}

/**
 * Observe and consume synchronously before M3's Main-pass draggable. Do not toggle
 * sheetGesturesEnabled: that removes M3's nested-scroll node and resets its drag detector
 * in the middle of the very pointer stream we need to retain. Attach INSIDE M3's content:
 * the supplied sheet modifier precedes draggableAnchors and has untranslated hit bounds.
 */
internal fun Modifier.smileySheetGestures(gestures: SmileySheetGestures): Modifier =
    this.then(SmileySheetGesturesElement(gestures))

// Internal so tests can inspect the real guard through LayoutInfo, without extra semantics.
internal data class SmileySheetGesturesElement(val gestures: SmileySheetGestures) :
    ModifierNodeElement<SmileySheetGesturesNode>() {
    override fun create() = SmileySheetGesturesNode(gestures)

    override fun update(node: SmileySheetGesturesNode) { node.gestures = gestures }

    override fun InspectorInfo.inspectableProperties() { name = "smileySheetGestures" }
}

internal class SmileySheetGesturesNode(var gestures: SmileySheetGestures) :
    Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial) consumeResidualGesture(pointerEvent)
        gestures.trace?.invoke(
            "pass=$pass type=${pointerEvent.type} bounds=$bounds pressed=${gestures.pressed} " +
                "blocked=${gestures.blocked} changes=" + pointerEvent.changes.joinToString { change ->
                    "id=${change.id.value} timeMs=${change.uptimeMillis} " +
                        "previousPressed=${change.previousPressed} pressed=${change.pressed} " +
                        "position=${change.position} consumed=${change.isConsumed}"
                },
        )
    }

    private fun consumeResidualGesture(pointerEvent: PointerEvent) {
        val consume = gestures.pointerEvent(
            pressed = pointerEvent.changes.any { it.pressed },
            newDown = pointerEvent.type == PointerEventType.Press &&
                pointerEvent.changes.none { it.previousPressed },
        )
        if (!consume) return
        // Keep hover and wheel input available while the post-fling latch remains armed.
        pointerEvent.changes.forEach { change ->
            if (change.pressed || change.previousPressed) change.consume()
        }
    }

    override fun onCancelPointerInput() { gestures.cancelled() }

    override fun onDetach() { gestures.cancelled() }
}

/**
 * Consume the disappearing grid's leftover deltas/velocity. M3 still receives onPostFling(0),
 * so pointer consumption must prevent displacement too: zero velocity alone cannot prevent settle.
 */
@Composable
internal fun Modifier.smileySheetNestedScroll(gestures: SmileySheetGestures): Modifier {
    val connection = remember(gestures) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                gestures.trace?.invoke(
                    "postScroll consumed=$consumed available=$available source=$source blocked=${gestures.blocked}",
                )
                return if (gestures.blocked) available else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                gestures.trace?.invoke("postFling consumed=$consumed available=$available blocked=${gestures.blocked}")
                return if (gestures.blocked) available else Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}
