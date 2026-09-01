// File name is intentional: it groups the HfrLinkHandlingStatus enum with its Android link-handling
// helpers (read + settings shortcut), rather than being named after the enum alone.
@file:Suppress("MatchingDeclarationName")

package fr.forumhfr.redface2.core.ui.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri

/**
 * Global #1207 preference consumed by the three explicit external-link menus. `RedfaceTheme`
 * provides the persisted value at the app root; `false` keeps previews and isolated hosts on the
 * historical direct-default-browser behaviour.
 */
val LocalAlwaysAskLinkApp = staticCompositionLocalOf { false }

/**
 * Whether Redface 2 is the effective default app for HFR links (#1032).
 *
 * [UNKNOWN] is a real, distinct state (not merely "not default"): below API 31 Android exposes no
 * per-domain approval, so the answer is genuinely unreadable rather than negative.
 */
enum class HfrLinkHandlingStatus { DEFAULT_HANDLER, NOT_DEFAULT, UNKNOWN }

/**
 * Pure R3 decision, isolated from Android so the default-handler contract is unit-tested without a
 * device. [hostState] is the raw value read from `DomainVerificationUserState.hostToStateMap`: a
 * third-party host without `autoVerify` moves NONE -> SELECTED on manual activation, so SELECTED is
 * the real "is default" state; VERIFIED never happens for such a host but is accepted for robustness.
 *
 * [DOMAIN_STATE_SELECTED] / [DOMAIN_STATE_VERIFIED] mirror the frozen public values of
 * `android.content.pm.verify.domain.DomainVerificationUserState` (API 31). They are kept local so
 * this function carries no API-31 import and stays callable on any JVM.
 */
internal fun hfrLinkHandlingStatusOf(
    isLinkHandlingAllowed: Boolean,
    hostState: Int?,
): HfrLinkHandlingStatus {
    val isDefault = isLinkHandlingAllowed &&
        (hostState == DOMAIN_STATE_SELECTED || hostState == DOMAIN_STATE_VERIFIED)
    return if (isDefault) HfrLinkHandlingStatus.DEFAULT_HANDLER else HfrLinkHandlingStatus.NOT_DEFAULT
}

/**
 * Reads whether Redface 2 is the default handler for [host]. Android exposes the per-domain approval
 * state only from API 31 ([DomainVerificationManager]); below that the status is unreadable, so the
 * result is [HfrLinkHandlingStatus.UNKNOWN]. A missing service or user state is also UNKNOWN.
 *
 * The API-31 branch stays inline behind the `SDK_INT` guard (same pattern as the rest of `:core:ui`,
 * e.g. `PostImageMenuSheet`) so Android lint sees the version check and no `androidx.annotation`
 * dependency is pulled for a single call site.
 */
@Suppress("ReturnCount") // Version guard + null-safe service/user-state reads, each an early return.
fun hfrLinkHandlingStatus(context: Context, host: String = HFR_HOST): HfrLinkHandlingStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return HfrLinkHandlingStatus.UNKNOWN
    val userState = context.getSystemService(DomainVerificationManager::class.java)
        ?.getDomainVerificationUserState(context.packageName)
        ?: return HfrLinkHandlingStatus.UNKNOWN
    return hfrLinkHandlingStatusOf(
        isLinkHandlingAllowed = userState.isLinkHandlingAllowed,
        hostState = userState.hostToStateMap[host],
    )
}

/**
 * Opens the precise Android screen to set Redface 2 as the default handler. API 31+ has a dedicated
 * "open by default" screen ([Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS]); older versions, or a
 * device missing that screen, fall back to the app details page. Returns whether an activity started.
 */
@Suppress("ReturnCount") // Primary open-by-default launch + app-details fallback, each an early return.
fun openAppDefaultLinkSettings(context: Context): Boolean {
    val packageUri = "package:${context.packageName}".toUri()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val openByDefault = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
            .addNewTaskFlagIfNeeded(context)
        try {
            context.startActivity(openByDefault)
            return true
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds lack the open-by-default screen; fall back to app details below.
        }
    }
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        .addNewTaskFlagIfNeeded(context)
    return try {
        context.startActivity(details)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun Intent.addNewTaskFlagIfNeeded(context: Context): Intent = apply {
    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Single source of the HFR host string for `:core:ui` (`HfrDeepLinkResolution` lives in `:app`). */
private const val HFR_HOST = "forum.hardware.fr"

// Frozen public values of DomainVerificationUserState (API 31), kept local to keep the pure fn JVM-only.
internal const val DOMAIN_STATE_SELECTED = 1
internal const val DOMAIN_STATE_VERIFIED = 2
