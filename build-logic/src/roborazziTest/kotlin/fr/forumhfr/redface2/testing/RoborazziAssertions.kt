package fr.forumhfr.redface2.testing

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage as captureRoborazziImage

/** Shared test source wired by redface.android.roborazzi. No pixel or colour-distance tolerance. */
fun SemanticsNodeInteraction.captureRoboImage(filePath: String) {
    captureRoborazziImage(
        filePath = filePath,
        roborazziOptions = strictRoborazziOptions(),
    )
    requireReference(filePath)
}

/** Explicitly cropped dialog captures use the same comparison and missing-reference guards. */
fun Bitmap.captureRoboImage(filePath: String) {
    captureRoborazziImage(filePath = filePath, roborazziOptions = strictRoborazziOptions())
    requireReference(filePath)
}

private fun strictRoborazziOptions() = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        changeThreshold = 0f,
        imageComparator = SimpleImageComparator(maxDistance = 0f),
    ),
)

private fun requireReference(filePath: String) {
    // Upstream compares a missing reference with an empty canvas. Even a blank capture must fail.
    check(System.getProperty("roborazzi.test.verify") != "true" || File(filePath).isFile) {
        "Missing Roborazzi reference: $filePath. Record it explicitly with -Proborazzi.mode=record."
    }
}
