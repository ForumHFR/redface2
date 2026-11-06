package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.ui.R

/**
 * ED-133 option B: 360 - 2×16 gutters - 138.7 submit - 8 gap - 16 icon - 4 gap = 161.3 dp.
 * Reserve 150 dp of TEXT, icon excluded; the remaining 11.3 dp absorb font/device variation.
 */
const val EDITOR_SUMMARY_TEXT_WIDTH_DP = 150

/** ED-130: presentation is independent of the durable send outcome in core:domain. */
data class EditorError internal constructor(
    val cause: EditorCause,
    val errorClass: EditorErrorClass,
    val summary: EditorText,
    val detail: EditorText?,
    val action: EditorErrorAction,
)

enum class EditorErrorClass { Certain, Ambiguous, Upload, Information, Validation }
enum class EditorErrorAction { Details, Complete, Dismiss, Reload }
enum class EditorErrorSurface { Topic, MessageReply, MessageCompose }

val EditorErrorAction.label: EditorText
    get() = EditorText(when (this) {
        EditorErrorAction.Details -> R.string.editor_action_details
        EditorErrorAction.Complete -> R.string.editor_action_complete
        EditorErrorAction.Dismiss -> R.string.editor_action_dismiss
        EditorErrorAction.Reload -> R.string.editor_action_reload
    })

/** No Context, exception, HTML or server-supplied text survives in a UI error. */
data class EditorText(@param:StringRes val resourceId: Int, val arguments: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(
        resourceId,
        *arguments.map { if (it is EditorText) it.resolve(context) else it }.toTypedArray(),
    )
}

@Composable
fun EditorText.resolve(): String = resolve(LocalContext.current)

/** The old chrome always shows the full remedy, without composing the future event bar. */
@Composable
fun EditorError.bannerText(): String = (detail ?: summary).resolve()

/** D44: retain the two existing warning-icon summaries in the old chrome. */
val EditorError.hasSubmissionSummary: Boolean
    get() = cause == EditorCause.Uncertain || cause == EditorCause.DraftWriteFailed

sealed interface EditorCause {
    data object Empty : EditorCause
    data object InvalidHashCheck : EditorCause
    data object AntiFlood : EditorCause
    data object TopicLocked : EditorCause
    data object LoginRequired : EditorCause
    data object SessionExpired : EditorCause
    data object Network : EditorCause
    data object QuoteFetchFailed : EditorCause
    data object DraftWriteFailed : EditorCause
    data object InvalidForm : EditorCause
    data object FormNetwork : EditorCause
    data object Uncertain : EditorCause
    data object MissingSubcat : EditorCause
    data object MissingTitle : EditorCause
    data object MissingSubcategory : EditorCause
    data object MissingRecipients : EditorCause
    data object MissingSubject : EditorCause
    data object NoImageReceived : EditorCause
    data object TooLarge : EditorCause
    data object UnsupportedType : EditorCause
    data object Configuration : EditorCause
    data object UploadNetwork : EditorCause
    data object PickerAbsent : EditorCause
    data object ImageLimit : EditorCause
    data object UnknownRecipient : EditorCause
    data object Refused : EditorCause

    data class Server(val code: Int, val providerId: UploadProviderId) : EditorCause
    data class Malformed(val providerId: UploadProviderId) : EditorCause
    data class UploadStopped(val completed: Int, val total: Int, val failure: EditorCause) : EditorCause {
        init {
            require(total in 2..MAX_IMAGES_PER_UPLOAD && completed in 0 until total)
            require(failure !is UploadStopped)
        }
    }
}
