package fr.forumhfr.redface2.core.ui.editor

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #447 — the field classifier override must preserve its visual Activity context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BbcodeTextFieldContextTest {

    @Test
    fun fieldContextOverridesOnlyTextClassificationService() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            // Older Robolectric SDK jars may not expose this service on configuration contexts.
            assumeNotNull(
                activity
                    .createConfigurationContext(Configuration(activity.resources.configuration))
                    .getSystemService(TextClassificationManager::class.java),
            )
            val activityManager = requireNotNull(
                activity.getSystemService(TextClassificationManager::class.java),
            )
            val activityClassifier = activityManager.textClassifier

            val fieldContext = createBbcodeTextFieldContext(activity)
            val fieldManager = requireNotNull(
                fieldContext.getSystemService(TextClassificationManager::class.java),
            )

            assertSame(activity, (fieldContext as ContextWrapper).baseContext)
            assertSame(activity.resources, fieldContext.resources)
            assertSame(activity.theme, fieldContext.theme)
            assertNotSame(activityManager, fieldManager)
            assertSame(TextClassifier.NO_OP, fieldManager.textClassifier)
            assertSame(activityClassifier, activityManager.textClassifier)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
