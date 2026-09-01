package fr.forumhfr.redface2.core.ui.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import fr.forumhfr.redface2.core.ui.R

/**
 * Opens [uri] outside Redface 2, preferring the device's default browser unless [alwaysAsk] forces
 * Android's « Ouvrir avec… » chooser.
 *
 * The default-app probe deliberately targets a neutral HTTPS host rather than [uri]. Probing an
 * HFR URL would resolve back to Redface 2 when it is the user's link handler and recreate the loop
 * this launcher prevents. When Android exposes no concrete default browser, the chooser excludes
 * every installable Redface 2 variant.
 */
@Suppress("ReturnCount") // Guard (non-web scheme) + direct default-browser launch + chooser-exclusion tail.
fun openUrlInExternalBrowser(context: Context, uri: Uri, alwaysAsk: Boolean = false): Boolean {
    if (uri.scheme !in WEB_SCHEMES) return false

    val probe = Intent(Intent.ACTION_VIEW, DEFAULT_BROWSER_PROBE.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val resolved = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
    val defaultPackage = resolved?.activityInfo?.packageName
    val redfaceComponents = redfaceMainActivityComponents(context.packageName)
    val hasUsableDefaultBrowser =
        defaultPackage != null &&
            defaultPackage != ANDROID_RESOLVER_PACKAGE &&
            redfaceComponents.none { it.packageName == defaultPackage }

    if (!alwaysAsk && hasUsableDefaultBrowser) {
        val directIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(defaultPackage)
            .addNewTaskFlagIfNeeded(context)
        try {
            context.startActivity(directIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // The browser may have disappeared between resolution and launch; fall back below.
        }
    }

    val viewIntent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val chooser = Intent.createChooser(
        viewIntent,
        context.getString(R.string.browser_chooser_title),
    ).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, redfaceComponents)
    }.addNewTaskFlagIfNeeded(context)

    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun redfaceMainActivityComponents(currentPackageName: String): Array<ComponentName> {
    val basePackage = currentPackageName
        .removeSuffix(DEBUG_SUFFIX)
        .removeSuffix(BETA_SUFFIX)
        .removeSuffix(DEV_SUFFIX)
    return arrayOf(
        basePackage,
        "$basePackage$DEBUG_SUFFIX",
        "$basePackage$BETA_SUFFIX",
        "$basePackage$BETA_SUFFIX$DEBUG_SUFFIX",
        "$basePackage$DEV_SUFFIX",
        "$basePackage$DEV_SUFFIX$DEBUG_SUFFIX",
    ).map { packageName -> ComponentName(packageName, MAIN_ACTIVITY_CLASS) }
        .toTypedArray()
}

private fun Intent.addNewTaskFlagIfNeeded(context: Context): Intent = apply {
    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private val WEB_SCHEMES = setOf("http", "https")
private const val DEFAULT_BROWSER_PROBE = "https://example.com"
private const val ANDROID_RESOLVER_PACKAGE = "android"
private const val MAIN_ACTIVITY_CLASS = "fr.forumhfr.redface2.MainActivity"
private const val DEBUG_SUFFIX = ".debug"
private const val BETA_SUFFIX = ".beta"
private const val DEV_SUFFIX = ".dev"
