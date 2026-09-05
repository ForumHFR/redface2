package fr.forumhfr.redface2.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon

/** Returns the manifest-qualified launcher alias for [icon]. */
@Suppress("DEPRECATION") // Historical aliases must remain addressable during migration.
fun launcherAliasFor(icon: AppLauncherIcon): String = when (icon) {
    AppLauncherIcon.CLASSIC -> LAUNCHER_CLASSIC_ALIAS
    AppLauncherIcon.RF1 -> LAUNCHER_RF1_ALIAS
    AppLauncherIcon.MONOGRAM -> LAUNCHER_MONOGRAM_ALIAS
    AppLauncherIcon.BUBBLES -> LAUNCHER_BUBBLES_ALIAS
    AppLauncherIcon.CHIP -> LAUNCHER_CHIP_ALIAS
    AppLauncherIcon.DARK -> LAUNCHER_DARK_ALIAS
    AppLauncherIcon.ROSE -> LAUNCHER_ROSE_ALIAS
    AppLauncherIcon.RED -> LAUNCHER_RED_ALIAS
}

/** True when [className] is a declared manifest launcher alias (untrusted-input guard). */
fun isKnownLauncherAlias(className: String): Boolean =
    AppLauncherIcon.entries.any { launcherAliasFor(it) == className }

/** Returns all launcher aliases with exactly the selected [icon] enabled. */
internal fun componentStatesFor(icon: AppLauncherIcon): Map<String, Boolean> =
    AppLauncherIcon.entries.associate { candidate ->
        launcherAliasFor(candidate) to (candidate == icon)
    }

/**
 * Enables the alias for [icon] without touching the others (#326 restart step 1).
 *
 * Apply deliberately leaves the previous alias enabled: the foreground task's `origActivity` is that
 * alias, and disabling it while the task lives makes the system force-remove the task — taking the
 * freshly started activity with it. The new process's startup reconciliation disables it instead.
 *
 * Alias class names use the manifest namespace, while [ComponentName] obtains the possibly suffixed
 * application id from [context] for beta/dev/debug variants.
 */
internal fun enableLauncherAlias(context: Context, icon: AppLauncherIcon) {
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, launcherAliasFor(icon)),
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

/** Disables every alias but [icon]'s, leaving the target's own state untouched. */
internal fun disableOtherLauncherAliases(context: Context, icon: AppLauncherIcon) {
    val selectedAlias = launcherAliasFor(icon)
    componentStatesFor(icon).keys
        .filter { it != selectedAlias }
        .forEach { alias ->
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
}

/** Applies [icon] without ever leaving the application with no enabled launcher entry point. */
internal fun applyLauncherIcon(context: Context, icon: AppLauncherIcon) {
    enableLauncherAlias(context, icon)
    disableOtherLauncherAliases(context, icon)
}

/**
 * Repairs actual PackageManager state, including DEFAULT (only Classic is enabled in the manifest).
 * Returns a replacement preference to persist when recovering a retired alias or a missing icon.
 * A normal preference/alias mismatch does not require another DataStore write.
 */
internal fun reconcileLauncherIcon(context: Context, persisted: AppLauncherIcon): AppLauncherIcon? {
    val active = AppLauncherIcon.entries.filter { icon ->
        when (context.packageManager.getComponentEnabledSetting(ComponentName(context, launcherAliasFor(icon)))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppLauncherIcon.CLASSIC
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }
    val needsMigration = active.isEmpty() || active.any { !it.selectable } || !persisted.selectable
    val target = if (needsMigration) AppLauncherIcon.CLASSIC else persisted
    if (active != listOf(target)) {
        // #326 restart step 4: when the target is already live (Apply enabled it before relaunching
        // into a new task), only retire the leftovers — re-enabling the target would churn its
        // component state and close the task the new activity runs in.
        if (target in active) disableOtherLauncherAliases(context, target) else applyLauncherIcon(context, target)
    }
    return target.takeIf { needsMigration }
}

private const val MANIFEST_PACKAGE = "fr.forumhfr.redface2"
internal const val LAUNCHER_CLASSIC_ALIAS = "$MANIFEST_PACKAGE.LauncherClassic"
internal const val LAUNCHER_RF1_ALIAS = "$MANIFEST_PACKAGE.LauncherRf1"
internal const val LAUNCHER_MONOGRAM_ALIAS = "$MANIFEST_PACKAGE.LauncherMonogram"
internal const val LAUNCHER_BUBBLES_ALIAS = "$MANIFEST_PACKAGE.LauncherBubbles"
internal const val LAUNCHER_CHIP_ALIAS = "$MANIFEST_PACKAGE.LauncherChip"
internal const val LAUNCHER_DARK_ALIAS = "$MANIFEST_PACKAGE.LauncherDark"
internal const val LAUNCHER_ROSE_ALIAS = "$MANIFEST_PACKAGE.LauncherRose"
internal const val LAUNCHER_RED_ALIAS = "$MANIFEST_PACKAGE.LauncherRed"
