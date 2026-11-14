package fr.forumhfr.redface2.core.ui.editor

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A long press on editable text arms the platform selection magnifier. Under Robolectric its popup never gets a
 * Surface and the first dismiss() throws a NullPointerException (Magnifier.java:1280). Not a production defect:
 * the platform widget does not exist in this environment. Same remedy as `feature/messages` tests.
 */
@Implements(Magnifier::class)
class NoopShadowMagnifier {

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
