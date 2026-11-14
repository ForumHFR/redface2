package fr.forumhfr.redface2.navigation

import androidx.navigation3.runtime.NavKey

/** Keep the editor below the settings entry and retain the selected tab and its other entries. */
internal fun openEditorSettings(backStack: MutableList<NavKey>) {
    backStack.add(SettingsCategoryRoute("editing"))
}
