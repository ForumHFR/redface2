package fr.forumhfr.redface2.navigation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import fr.forumhfr.redface2.LauncherIconRestartActivity
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_MAIN_PID
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_TARGET_ALIAS_CLASS
import fr.forumhfr.redface2.MainActivity
import fr.forumhfr.redface2.R
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.feature.settings.AppLauncherIconController
import fr.forumhfr.redface2.feature.settings.SettingsAppIconScreen
import fr.forumhfr.redface2.feature.settings.SettingsEffect
import fr.forumhfr.redface2.feature.settings.SettingsIntent
import fr.forumhfr.redface2.feature.settings.SettingsViewModel
import fr.forumhfr.redface2.feature.settings.launcherAliasFor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val EXTRA_RESTORE_ROUTE = "fr.forumhfr.redface2.extra.RESTORE_ROUTE"
internal const val APP_ICON_RESTORE_ROUTE = "settings/app-icon"

@Composable
internal fun SettingsAppIconEntry(
    onBack: () -> Unit,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current.findActivity() as MainActivity
    DisposableEffect(viewModel, activity) {
        val effects = activity.lifecycleScope.launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SettingsEffect.RestartOnLauncherAlias -> activity.restartLauncherIcon(effect.icon) {
                        viewModel.submit(SettingsIntent.AppLauncherIconRestartFailed)
                    }
                }
            }
        }
        onDispose {
            // A pending Apply keeps its consumer until failure or the Activity restart, even on back/tab navigation.
            activity.lifecycleScope.launch {
                viewModel.state.first { !it.isUpdatingAppLauncherIcon }
                effects.cancel()
            }
        }
    }
    SettingsAppIconScreen(
        onBack = onBack,
        iconResource = ::appLauncherIconResource,
        topBarActions = topBarActions,
        viewModel = viewModel,
    )
}

internal fun appLauncherIconResource(icon: AppLauncherIcon): Int = when (icon) {
    AppLauncherIcon.RF1 -> R.mipmap.ic_launcher_rf1
    else -> R.mipmap.ic_launcher
}

internal fun launcherRestartIntent(context: Context, icon: AppLauncherIcon): Intent =
    Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context, launcherAliasFor(icon))
        addCategory(Intent.CATEGORY_LAUNCHER)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
    }

private fun launcherIconProcessRestartIntent(context: Context, icon: AppLauncherIcon): Intent =
    Intent(context, LauncherIconRestartActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra(EXTRA_TARGET_ALIAS_CLASS, launcherAliasFor(icon))
        putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
        putExtra(EXTRA_MAIN_PID, Process.myPid())
    }

internal suspend fun restartOnLauncherAlias(
    activity: Activity,
    icon: AppLauncherIcon,
    controller: AppLauncherIconController,
) {
    val restartIntent = launcherIconProcessRestartIntent(activity, icon)
    // Finish the old task BEFORE handing off to the separate process/task. Otherwise NEW_TASK can
    // reuse its old origActivity, and startup reconciliation closes it when retiring that alias.
    // The restart activity kills this process before relaunching, avoiding singleTop delivery (#326).
    activity.finishAffinity()
    try {
        activity.startActivity(restartIntent)
    } catch (_: RuntimeException) {
        // finishAffinity can cancel the Activity's lifecycleScope while recovery suspends on IO.
        withContext(NonCancellable) {
            controller.recoverClassic()
            activity.startActivity(launcherIconProcessRestartIntent(activity, AppLauncherIcon.CLASSIC))
        }
    }
}

/** Routed through IntentDelivery's existing saved delivery ID, so recreation does not reset navigation. */
internal fun restoreAppIconRoute(
    intent: Intent,
    switchTab: (TopLevelDestination) -> Unit,
    backStacks: Map<TopLevelDestination, NavBackStack<NavKey>>,
): Boolean {
    val restore = intent.action == Intent.ACTION_MAIN &&
        intent.getStringExtra(EXTRA_RESTORE_ROUTE) == APP_ICON_RESTORE_ROUTE
    if (restore) {
        switchTab(TopLevelDestination.Settings)
        backStacks.getValue(TopLevelDestination.Settings).apply {
            clear()
            add(SettingsRoute)
            add(SettingsDisplayRoute)
            add(SettingsAppIconRoute)
        }
    }
    return restore
}
