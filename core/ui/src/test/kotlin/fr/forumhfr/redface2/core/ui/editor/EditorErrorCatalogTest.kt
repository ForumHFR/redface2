package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
class EditorErrorCatalogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each cause keeps its expected class summary detail and remedy`() {
        data class Row(
            val error: EditorError,
            val cause: EditorCause,
            val errorClass: EditorErrorClass,
            val summary: Int,
            val detail: Int?,
            val action: EditorErrorAction,
        )
        val rows = listOf(
            Row(EditorErrors.empty, EditorCause.Empty, EditorErrorClass.Certain,
                R.string.editor_summary_empty, R.string.editor_error_empty, EditorErrorAction.Dismiss),
            Row(EditorErrors.invalidHashCheck, EditorCause.InvalidHashCheck, EditorErrorClass.Certain,
                R.string.editor_summary_hash, R.string.editor_error_invalid_hash, EditorErrorAction.Details),
            Row(EditorErrors.antiFlood, EditorCause.AntiFlood, EditorErrorClass.Certain,
                R.string.editor_summary_flood, R.string.editor_error_anti_flood, EditorErrorAction.Details),
            Row(EditorErrors.topicLocked, EditorCause.TopicLocked, EditorErrorClass.Certain,
                R.string.editor_summary_locked, R.string.editor_error_topic_locked, EditorErrorAction.Details),
            Row(EditorErrors.loginRequired, EditorCause.LoginRequired, EditorErrorClass.Certain,
                R.string.editor_summary_login, R.string.editor_error_login_required, EditorErrorAction.Details),
            Row(EditorErrors.sessionExpired, EditorCause.SessionExpired, EditorErrorClass.Certain,
                R.string.editor_summary_session, R.string.editor_error_session_expired, EditorErrorAction.Details),
            Row(EditorErrors.network, EditorCause.Network, EditorErrorClass.Certain,
                R.string.editor_summary_network, R.string.editor_error_network, EditorErrorAction.Details),
            Row(EditorErrors.quoteFetchFailed, EditorCause.QuoteFetchFailed, EditorErrorClass.Certain,
                R.string.editor_summary_quote, R.string.editor_error_quote_fetch, EditorErrorAction.Reload),
            Row(EditorErrors.draftWriteFailed, EditorCause.DraftWriteFailed, EditorErrorClass.Certain,
                R.string.editor_submission_draft_write_failed, R.string.editor_detail_draft_write_failed,
                EditorErrorAction.Details),
            Row(EditorErrors.invalidForm, EditorCause.InvalidForm, EditorErrorClass.Certain,
                R.string.editor_summary_form, R.string.editor_error_unknown, EditorErrorAction.Reload),
            Row(EditorErrors.formNetwork, EditorCause.FormNetwork, EditorErrorClass.Certain,
                R.string.editor_summary_network, R.string.editor_detail_form_network, EditorErrorAction.Reload),
            Row(EditorErrors.uncertain, EditorCause.Uncertain, EditorErrorClass.Ambiguous,
                R.string.editor_submission_uncertain, R.string.quick_reply_error_uncertain, EditorErrorAction.Details),
            Row(EditorErrors.missingSubcat, EditorCause.MissingSubcat, EditorErrorClass.Certain,
                R.string.editor_summary_stale_topic, R.string.editor_error_missing_subcat, EditorErrorAction.Details),
            Row(EditorErrors.missingTitle, EditorCause.MissingTitle, EditorErrorClass.Validation,
                R.string.editor_summary_title, null, EditorErrorAction.Complete),
            Row(EditorErrors.missingSubcategory, EditorCause.MissingSubcategory, EditorErrorClass.Validation,
                R.string.editor_summary_subcategory, null, EditorErrorAction.Complete),
            Row(EditorErrors.missingRecipients, EditorCause.MissingRecipients, EditorErrorClass.Validation,
                R.string.editor_summary_recipients, null, EditorErrorAction.Complete),
            Row(EditorErrors.missingSubject, EditorCause.MissingSubject, EditorErrorClass.Validation,
                R.string.editor_summary_subject, null, EditorErrorAction.Complete),
            Row(EditorErrors.noImageReceived, EditorCause.NoImageReceived, EditorErrorClass.Information,
                R.string.editor_summary_no_image, R.string.editor_upload_error_no_image_received,
                EditorErrorAction.Dismiss),
            Row(EditorErrors.tooLarge, EditorCause.TooLarge, EditorErrorClass.Upload,
                R.string.editor_summary_large, R.string.editor_upload_error_too_large, EditorErrorAction.Details),
            Row(EditorErrors.unsupportedType, EditorCause.UnsupportedType, EditorErrorClass.Upload,
                R.string.editor_summary_format, R.string.editor_upload_error_unsupported_type,
                EditorErrorAction.Details),
            Row(EditorErrors.configuration, EditorCause.Configuration, EditorErrorClass.Upload,
                R.string.editor_summary_configuration, R.string.editor_upload_error_configuration,
                EditorErrorAction.Details),
            Row(EditorErrors.uploadNetwork, EditorCause.UploadNetwork, EditorErrorClass.Upload,
                R.string.editor_summary_network, R.string.editor_upload_error_network, EditorErrorAction.Details),
            Row(EditorErrors.pickerAbsent, EditorCause.PickerAbsent, EditorErrorClass.Upload,
                R.string.editor_summary_picker, R.string.editor_detail_picker_absent, EditorErrorAction.Details),
            Row(EditorErrors.imageLimit, EditorCause.ImageLimit, EditorErrorClass.Information,
                R.string.editor_summary_limit, R.string.editor_detail_image_limit, EditorErrorAction.Dismiss),
            Row(EditorErrors.unknownRecipient, EditorCause.UnknownRecipient, EditorErrorClass.Certain,
                R.string.editor_summary_recipient_unknown, R.string.messages_compose_error_unknown_recipient,
                EditorErrorAction.Complete),
            Row(EditorErrors.refused, EditorCause.Refused, EditorErrorClass.Certain,
                R.string.editor_summary_refused, R.string.messages_reply_error_refused, EditorErrorAction.Details),
        )
        rows.forEach { row ->
            assertEquals(row.cause, row.error.cause)
            assertEquals(row.cause.toString(), row.errorClass, row.error.errorClass)
            assertEquals(row.cause.toString(), row.summary, row.error.summary.resourceId)
            assertEquals(row.cause.toString(), row.detail, row.error.detail?.resourceId)
            assertEquals(row.cause.toString(), row.action, row.error.action)
        }
    }

    @Test
    fun `every catalogue value has a complete resource contract`() {
        EditorErrorAction.entries.forEach { assertTrue(it.label.resolve(context).isNotBlank()) }
        editorErrorSamples.forEach { error ->
            assertTrue(error.cause.toString(), error.summary.resolve(context).isNotBlank())
            error.detail?.let { assertTrue(it.resolve(context).isNotBlank()) }
            if (error.errorClass == EditorErrorClass.Ambiguous) {
                val text = checkNotNull(error.detail).resolve(context)
                assertFalse(text, text.contains("réessay", ignoreCase = true))
                assertTrue(text, text.contains("peut-être"))
                assertEquals(EditorErrorAction.Details, error.action)
            }
        }
        assertEquals(EditorErrorClass.Validation, EditorErrors.missingSubcategory.errorClass)
        assertEquals(EditorErrorAction.Complete, EditorErrors.missingSubcategory.action)
        assertEquals(EditorErrorAction.Reload, EditorErrors.quoteFetchFailed.action)
        assertEquals(EditorErrorAction.Reload, EditorErrors.invalidForm.action)
        assertEquals(EditorErrorAction.Reload, EditorErrors.formNetwork.action)
        assertEquals(EditorErrorClass.Information, EditorErrors.imageLimit.errorClass)
        assertEquals(EditorErrorAction.Dismiss, EditorErrors.imageLimit.action)
        assertEquals(EditorErrorClass.Upload, EditorErrors.pickerAbsent.errorClass)
    }

    @Test
    fun `POST unknown stays ambiguous while unreadable preparation is certain on every surface`() {
        EditorErrorSurface.entries.forEach { surface ->
            val unknown = ReplyFailureReason.Unknown.toEditorError(surface)
            assertEquals(EditorCause.Uncertain, unknown.cause)
            assertEquals(EditorErrorClass.Ambiguous, unknown.errorClass)
            assertEquals(EditorErrorClass.Certain, EditorErrors.invalidForm.errorClass)
        }
        assertEquals(EditorErrors.empty, ReplyFailureReason.EmptyMessage.toEditorError())
        assertEquals(EditorErrors.invalidHashCheck, ReplyFailureReason.InvalidHashCheck.toEditorError())
        assertEquals(EditorErrors.antiFlood, ReplyFailureReason.AntiFlood.toEditorError())
        assertEquals(EditorErrors.topicLocked, ReplyFailureReason.TopicLocked.toEditorError())
        assertEquals(EditorErrors.loginRequired, ReplyFailureReason.LoginRequired.toEditorError())
        assertEquals(EditorErrors.unknownRecipient, ReplyFailureReason.UnknownRecipient.toEditorError())
    }

    @Test
    fun `D44 and the three verification details stay verbatim`() {
        assertEquals("Envoi incertain", EditorErrors.uncertain.summary.resolve(context))
        assertEquals("Brouillon non écrit", EditorErrors.draftWriteFailed.summary.resolve(context))
        assertEquals("Votre message a peut-être été envoyé : vérifiez le sujet.",
            EditorErrors.uncertain.detail?.resolve(context))
        assertEquals("Réponse inattendue de HFR. Votre message a peut-être été envoyé : vérifiez la conversation.",
            EditorErrors.uncertain(EditorErrorSurface.MessageReply).detail?.resolve(context))
        assertEquals(
            "Réponse inattendue de HFR. Votre message a peut-être été envoyé : " +
                "vérifiez votre liste de messages privés avant de renvoyer.",
            EditorErrors.uncertain(EditorErrorSurface.MessageCompose).detail?.resolve(context),
        )
    }

    @Test
    fun `stopped upload agrees with inserted count and keeps the nested failure detail`() {
        listOf(0, 1, 2, 9).forEach { completed ->
            val error = EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(completed, 10))
            val ending = if (completed <= 1) "insérée" else "insérées"
            val noun = if (completed <= 1) "image" else "images"
            assertEquals("$completed/10 $ending", error.summary.resolve(context))
            assertEquals("$completed/10 $noun $ending. ${EditorErrors.tooLarge.detail?.resolve(context)}",
                error.detail?.resolve(context))
        }
    }

    @Test
    fun `upload mapping preserves host status and partial insertion count`() {
        assertEquals(EditorErrors.tooLarge, UploadException.TooLarge(1).toEditorUploadError())
        assertEquals(EditorErrors.unsupportedType, UploadException.UnsupportedType(null).toEditorUploadError())
        assertEquals(EditorErrors.configuration, UploadException.Configuration("missing").toEditorUploadError())
        assertEquals(EditorErrors.uploadNetwork, IOException().toEditorUploadError())
        UploadProviderId.entries.forEach { host ->
            val failure = UploadException.Server(503, host).toEditorUploadError()
            assertEquals(EditorCause.Server(503, host), failure.cause)
            val stopped = EditorErrors.stopped(failure, UploadProgress(1, 10))
            assertEquals(EditorCause.UploadStopped(1, 10, failure.cause), stopped.cause)
            assertEquals(EditorErrorClass.Upload, stopped.errorClass)
            assertEquals("1/10 insérée", stopped.summary.resolve(context))
            val detail = checkNotNull(stopped.detail).resolve(context)
            assertTrue(detail, detail.startsWith("1/10 image insérée."))
            assertTrue(detail, detail.contains("503"))
            assertTrue(detail, detail.contains(context.getString(if (host == UploadProviderId.DIBERIE) {
                R.string.editor_upload_provider_diberie
            } else {
                R.string.editor_upload_provider_imgur
            })))
            assertEquals(EditorCause.Malformed(host), UploadException.Malformed(host).toEditorUploadError().cause)
        }
    }
}
