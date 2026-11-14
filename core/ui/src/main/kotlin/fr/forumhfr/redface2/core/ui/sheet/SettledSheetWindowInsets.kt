package fr.forumhfr.redface2.core.ui.sheet

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * Tagbug DEV #2805926 — content insets of a `ModalBottomSheet`, sized by the keyboard's DESTINATION
 * rather than by its live height. Pass as `contentWindowInsets = { settledSheetWindowInsets() }`.
 *
 * M3's default (`BottomSheetDefaults.modalWindowInsets`, i.e. `safeDrawing`) pads the sheet content by
 * the live IME inset. A sheet opened over a visible keyboard is first measured with the whole keyboard;
 * its dialog window then takes focus without an editor, the system retracts the keyboard, and the
 * padding follows that retraction frame by frame. The sheet's height and its `Expanded` anchor
 * (`fullHeight - sheetHeight`) change on every frame of the opening, while M3's own show animation is
 * still chasing that anchor.
 *
 * [WindowInsets.Companion.imeAnimationTarget] is the keyboard's inset once the running animation ends,
 * and the live inset when none runs. The sheet therefore takes its final height in ONE step, as soon
 * as the retraction starts, instead of once per frame; a keyboard requested from inside the sheet
 * (Wiki search) is anticipated the same way; a keyboard that stays up keeps the content above it.
 *
 * The system bars and the cutout stay included on the sides of M3's default, so an inner
 * `navigationBarsPadding()` remains consumed. Never combine with an inner `imePadding()`: the live
 * inset is not consumed here, and that padding would reintroduce the per-frame resize.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun settledSheetWindowInsets(): WindowInsets =
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.imeAnimationTarget)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Top)
