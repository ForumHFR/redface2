package fr.forumhfr.redface2.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon

/** Returns the manifest-qualified launcher alias for [icon]. */
internal fun launcherAliasFor(icon: AppLauncherIcon): String = when (icon) {
    AppLauncherIcon.CLASSIC -> LAUNCHER_CLASSIC_ALIAS
    AppLauncherIcon.DARK -> LAUNCHER_DARK_ALIAS
    AppLauncherIcon.ROSE -> LAUNCHER_ROSE_ALIAS
    AppLauncherIcon.RED -> LAUNCHER_RED_ALIAS
}

/** Returns all launcher aliases with exactly the selected [icon] enabled. */
internal fun componentStatesFor(icon: AppLauncherIcon): Map<String, Boolean> =
    AppLauncherIcon.entries.associate { candidate ->
        launcherAliasFor(candidate) to (candidate == icon)
    }

/**
 * Applies [icon] without ever leaving the application with no enabled launcher entry point.
 *
 * Alias class names use the manifest namespace, while [ComponentName] obtains the possibly suffixed
 * application id from [context] for beta/dev/debug variants.
 */
internal fun applyLauncherIcon(context: Context, icon: AppLauncherIcon) {
    val packageManager = context.packageManager
    val selectedAlias = launcherAliasFor(icon)
    val states = componentStatesFor(icon)

    packageManager.setComponentEnabledSetting(
        ComponentName(context, selectedAlias),
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
    states.keys
        .filter { it != selectedAlias }
        .forEach { alias ->
            packageManager.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
}

private const val MANIFEST_PACKAGE = "fr.forumhfr.redface2"
internal const val LAUNCHER_CLASSIC_ALIAS = "$MANIFEST_PACKAGE.LauncherClassic"
internal const val LAUNCHER_DARK_ALIAS = "$MANIFEST_PACKAGE.LauncherDark"
internal const val LAUNCHER_ROSE_ALIAS = "$MANIFEST_PACKAGE.LauncherRose"
internal const val LAUNCHER_RED_ALIAS = "$MANIFEST_PACKAGE.LauncherRed"
