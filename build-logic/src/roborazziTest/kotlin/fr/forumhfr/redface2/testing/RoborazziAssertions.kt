package fr.forumhfr.redface2.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage as captureRoborazziImage

/** Shared test source wired by redface.android.roborazzi. No pixel or colour-distance tolerance. */
fun SemanticsNodeInteraction.captureRoboImage(filePath: String) {
    captureRoborazziImage(
        filePath = filePath,
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0f),
            ),
        ),
    )
    // Upstream compares a missing reference with an empty canvas. Even a blank capture must fail.
    check(System.getProperty("roborazzi.test.verify") != "true" || File(filePath).isFile) {
        "Missing Roborazzi reference: $filePath. Record it explicitly with -Proborazzi.mode=record."
    }
}
