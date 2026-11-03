package fr.forumhfr.redface2.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import fr.forumhfr.redface2.BuildConfig
import fr.forumhfr.redface2.feature.settings.ReportBuildInfo
import fr.forumhfr.redface2.feature.settings.ReportContentEffect
import fr.forumhfr.redface2.feature.settings.ReportContentScreen
import kotlinx.serialization.Serializable
import fr.forumhfr.redface2.core.ui.R as CoreUiR
import fr.forumhfr.redface2.feature.settings.R as SettingsR

/** Both entry points open the same form without serializing any report context. */
@Serializable
data object ReportContentRoute : RedfaceNavKey

internal fun openReportFromAccount(backStack: MutableList<NavKey>) {
    backStack.add(ReportContentRoute)
}

internal fun openReportFromSettings(backStack: MutableList<NavKey>) {
    backStack.add(ReportContentRoute)
}

/** Handles either ordering of the login callback and the global auth-transition effect. */
internal fun resetMessagesPreservingReport(backStack: MutableList<NavKey>) {
    val report = when (backStack.lastOrNull()) {
        LoginRoute -> backStack.getOrNull(backStack.lastIndex - 1) as? ReportContentRoute
        ReportContentRoute -> ReportContentRoute
        else -> null
    }
    backStack.clear()
    backStack.add(MessagesRoute)
    report?.let(backStack::add)
}

internal fun finishLogin(backStack: MutableList<NavKey>) {
    if (backStack.lastOrNull() == LoginRoute && backStack.size > 1) backStack.removeAt(backStack.lastIndex)
}

/** The report flushes its draft before this handoff, including when opened in Messages itself. */
internal fun openMessagesForReport(
    messagesBackStack: MutableList<NavKey>,
    requestInboxRefresh: () -> Unit,
    switchTab: (TopLevelDestination) -> Unit,
) {
    messagesBackStack.clear()
    messagesBackStack.add(MessagesRoute)
    requestInboxRefresh()
    switchTab(TopLevelDestination.Messages)
}

@Composable
internal fun ReportContentEntry(
    backStack: MutableList<NavKey>,
    onCheckMessages: () -> Unit,
    onConversationSent: () -> Unit,
) {
    val context = LocalContext.current
    val emailSubject = stringResource(CoreUiR.string.account_menu_report_email_subject)
    val noEmailClient = stringResource(CoreUiR.string.account_menu_no_email_client)
    val sent = stringResource(SettingsR.string.report_sent)
    ReportContentScreen(
        build = ReportBuildInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR),
        onEffect = { effect ->
            when (effect) {
                ReportContentEffect.Login -> backStack.add(LoginRoute)
                ReportContentEffect.Email -> startReportEmail(context, emailSubject, noEmailClient)
                ReportContentEffect.CloseCommitted -> popReport(backStack)
                ReportContentEffect.CheckMessages -> {
                    popReport(backStack)
                    onCheckMessages()
                }
                ReportContentEffect.SubmitSucceeded -> {
                    popReport(backStack)
                    onConversationSent()
                    Toast.makeText(context, sent, Toast.LENGTH_LONG).show()
                }
            }
        },
    )
}

private fun popReport(backStack: MutableList<NavKey>) {
    if (backStack.lastOrNull() == ReportContentRoute && backStack.size > 1) backStack.removeAt(backStack.lastIndex)
}

/** Anonymous fallback only. The in-app form is the main reporting mechanism. */
private fun startReportEmail(context: Context, subject: String, noEmailClientMessage: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = "mailto:$REPORT_EMAIL".toUri()
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, noEmailClientMessage, Toast.LENGTH_LONG).show()
    }
}

private const val REPORT_EMAIL = "xat@azora.fr"
