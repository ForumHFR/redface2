package fr.forumhfr.redface2.feature.flags

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue

/** The pending warning is cleared on account change, cancelling its snackbar and its retry action. */
@Composable
internal fun FlagsRefreshWarningEffect(
    warning: FlagsRefreshWarning?,
    authSessionKey: String?,
    host: SnackbarHostState,
    onConsumed: (FlagsRefreshWarning) -> Unit,
    onRetry: (FlagsRefreshWarning) -> Unit,
) {
    val resources = LocalContext.current.resources
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    val currentOnRetry by rememberUpdatedState(onRetry)
    LaunchedEffect(warning, authSessionKey) {
        if (warning != null && warning.pseudo == authSessionKey) {
            val result = host.showRefreshWarning(
                message = refreshWarningMessage(resources, warning.issue),
                retryLabel = resources.getString(R.string.flags_retry),
            )
            currentOnConsumed(warning)
            if (result == SnackbarResult.ActionPerformed) currentOnRetry(warning)
        }
    }
}

internal suspend fun SnackbarHostState.showRefreshWarning(message: String, retryLabel: String): SnackbarResult =
    showSnackbar(message = message, actionLabel = retryLabel, withDismissAction = true, duration = SnackbarDuration.Long)

internal fun FlagRefreshIssue.stickyCategoryLabels(fallback: (Int) -> String): String {
    val labels = stickyCategories.take(MAX_CATEGORY_NAMES).joinToString(", ") { category ->
        category.name?.takeIf { it.isNotBlank() } ?: fallback(category.id)
    }
    val remaining = stickyCategories.size - MAX_CATEGORY_NAMES
    return if (remaining > 0) "$labels +$remaining" else labels
}

private fun refreshWarningMessage(resources: Resources, issue: FlagRefreshIssue): String {
    val details = mutableListOf<String>()
    if (issue.stickyCategories.isNotEmpty()) {
        val categories = issue.stickyCategoryLabels { resources.getString(R.string.flags_category_fallback, it) }
        details += resources.getString(R.string.flags_incomplete_stickies, categories)
    }
    if (issue.paginationTruncated) details += resources.getString(R.string.flags_incomplete_pages)
    return resources.getString(R.string.flags_incomplete, details.joinToString(" ; "))
}

private const val MAX_CATEGORY_NAMES = 2
