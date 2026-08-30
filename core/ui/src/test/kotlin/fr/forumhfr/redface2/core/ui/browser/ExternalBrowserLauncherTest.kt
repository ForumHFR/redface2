package fr.forumhfr.redface2.core.ui.browser

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalBrowserLauncherTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `default browser receives the intact URL without a chooser`() {
        registerDefault(PROBE, BROWSER_PACKAGE)
        val context = RecordingContext(application, BASE_PACKAGE)
        val target = Uri.parse("https://forum.hardware.fr/forum2.php?cat=13&post=35395&page=42")

        assertTrue(openUrlInExternalBrowser(context, target))

        val started = context.startedIntents.single()
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(BROWSER_PACKAGE, started.`package`)
        assertEquals(target, started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /**
     * #1032 R1 — an HFR-specific default must never influence browser selection. The neutral probe
     * resolves to the browser while the target itself resolves to RF2; only the browser may launch.
     */
    @Test
    fun `HFR default handler RF2 never wins over the generic browser probe`() {
        val target = Uri.parse("https://forum.hardware.fr/forum2.php?cat=13&post=35395&page=42")
        registerDefault(PROBE, BROWSER_PACKAGE)
        registerDefault(browsableView(target), BASE_PACKAGE)
        assertEquals(
            BASE_PACKAGE,
            application.packageManager
                .resolveActivity(browsableView(target), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName,
        )
        val context = RecordingContext(application, BASE_PACKAGE)

        assertTrue(openUrlInExternalBrowser(context, target))

        val started = context.startedIntents.single()
        val launchedPackage = requireNotNull(started.`package`)
        assertEquals(BROWSER_PACKAGE, launchedPackage)
        assertFalse(launchedPackage in RF2_PACKAGES)
    }

    @Test
    fun `system resolver falls back to a browsable chooser excluding every RF2 variant`() {
        registerDefault(PROBE, "android")
        val context = RecordingContext(application, BASE_PACKAGE)
        val target = Uri.parse("https://forum.hardware.fr/forum2.php?cat=13&post=35395&page=42")

        assertTrue(openUrlInExternalBrowser(context, target))

        val chooser = context.startedIntents.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val view = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), view.categories)
        assertEquals(target, view.data)
        assertArrayEquals(EXPECTED_EXCLUSIONS, excludedComponents(chooser))
    }

    @Test
    fun `every installed RF2 package reconstructs the same six exclusions`() {
        registerDefault(PROBE, "android")

        RF2_PACKAGES.forEach { currentPackage ->
            val context = RecordingContext(application, currentPackage)

            assertTrue(openUrlInExternalBrowser(context, Uri.parse("https://example.org/path")))

            assertArrayEquals(
                "exclusions rebuilt from $currentPackage",
                EXPECTED_EXCLUSIONS,
                excludedComponents(context.startedIntents.single()),
            )
        }
    }

    @Test
    fun `non-web scheme is rejected without starting an activity`() {
        val context = RecordingContext(application, BASE_PACKAGE)

        assertFalse(openUrlInExternalBrowser(context, Uri.parse("ftp://example.org/archive.zip")))

        assertTrue(context.startedIntents.isEmpty())
    }

    @Test
    fun `activity-not-found on direct launch and chooser returns false`() {
        registerDefault(PROBE, BROWSER_PACKAGE)
        val context = RecordingContext(application, BASE_PACKAGE, throwOnStart = true)

        assertFalse(openUrlInExternalBrowser(context, Uri.parse("https://example.org/path")))

        assertEquals(
            listOf(Intent.ACTION_VIEW, Intent.ACTION_CHOOSER),
            context.startedIntents.map { it.action },
        )
    }

    private fun registerDefault(intent: Intent, packageName: String) {
        Shadows.shadowOf(application.packageManager)
            .addResolveInfoForIntent(intent, resolveInfo(packageName))
    }

    private fun resolveInfo(packageName: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.BrowserActivity"
        }
    }

    private fun excludedComponents(chooser: Intent): Array<ComponentName> = requireNotNull(
        chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java),
    )

    private class RecordingContext(
        base: Context,
        private val currentPackageName: String,
        private val throwOnStart: Boolean = false,
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()

        override fun getPackageName(): String = currentPackageName

        override fun startActivity(intent: Intent) {
            startedIntents += intent
            if (throwOnStart) throw ActivityNotFoundException()
        }
    }

    private companion object {
        const val BASE_PACKAGE = "fr.forumhfr.redface2"
        const val BROWSER_PACKAGE = "com.example.browser"
        val PROBE: Intent = browsableView(Uri.parse("https://example.com"))
        val RF2_PACKAGES = listOf(
            BASE_PACKAGE,
            "$BASE_PACKAGE.debug",
            "$BASE_PACKAGE.beta",
            "$BASE_PACKAGE.beta.debug",
            "$BASE_PACKAGE.dev",
            "$BASE_PACKAGE.dev.debug",
        )
        val EXPECTED_EXCLUSIONS = RF2_PACKAGES
            .map { packageName -> ComponentName(packageName, "fr.forumhfr.redface2.MainActivity") }
            .toTypedArray()

        fun browsableView(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
    }
}
