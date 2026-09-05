package fr.forumhfr.redface2

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Process
import fr.forumhfr.redface2.feature.settings.isKnownLauncherAlias
import fr.forumhfr.redface2.navigation.EXTRA_RESTORE_ROUTE

/** Survives the main process in its own process/task, then cold-starts the selected launcher alias. */
class LauncherIconRestartActivity : Activity() {
    internal var processTerminator: ProcessTerminator = AndroidProcessTerminator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a validated alias class name crosses the process boundary; the Intent is rebuilt here
        // rather than relaunched from an extra (UnsafeIntentLaunch).
        val aliasClass = intent.getStringExtra(EXTRA_TARGET_ALIAS_CLASS)?.takeIf(::isKnownLauncherAlias)
        val route = intent.getStringExtra(EXTRA_RESTORE_ROUTE)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (aliasClass == null || route == null || mainPid <= 0) {
            finish()
            return
        }

        // The caller has already finished the old affinity. Killing its process prevents singleTop
        // from delivering to the old MainActivity; the new task's origActivity is now the target alias.
        processTerminator.killProcess(mainPid)
        startActivity(targetIntent(aliasClass, route))
        finish()
        processTerminator.exit()
    }

    private fun targetIntent(aliasClass: String, route: String): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(packageName, aliasClass)
        addCategory(Intent.CATEGORY_LAUNCHER)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(EXTRA_RESTORE_ROUTE, route)
    }

    companion object {
        internal const val EXTRA_TARGET_ALIAS_CLASS = "fr.forumhfr.redface2.extra.TARGET_ALIAS_CLASS"
        internal const val EXTRA_MAIN_PID = "fr.forumhfr.redface2.extra.MAIN_PID"
    }
}

/** Replaced on the activity instance before onCreate in JVM tests. */
internal interface ProcessTerminator {
    fun killProcess(pid: Int)
    fun exit()
}

private object AndroidProcessTerminator : ProcessTerminator {
    override fun killProcess(pid: Int) {
        Process.killProcess(pid)
    }

    override fun exit() {
        Runtime.getRuntime().exit(0)
    }
}
