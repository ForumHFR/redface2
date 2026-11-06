package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.upload.UploadProviderId

/** Closed ED-130 catalogue, including both providers and all ED-112 uncertainty details. */
internal val editorErrorSamples: List<EditorError> = listOf(
    EditorErrors.empty,
    EditorErrors.invalidHashCheck,
    EditorErrors.antiFlood,
    EditorErrors.topicLocked,
    EditorErrors.loginRequired,
    EditorErrors.sessionExpired,
    EditorErrors.network,
    EditorErrors.quoteFetchFailed,
    EditorErrors.draftWriteFailed,
    EditorErrors.invalidForm,
    EditorErrors.formNetwork,
    EditorErrors.uncertain,
    EditorErrors.missingSubcat,
    EditorErrors.missingTitle,
    EditorErrors.missingSubcategory,
    EditorErrors.missingRecipients,
    EditorErrors.missingSubject,
    EditorErrors.noImageReceived,
    EditorErrors.tooLarge,
    EditorErrors.unsupportedType,
    EditorErrors.configuration,
    EditorErrors.uploadNetwork,
    EditorErrors.pickerAbsent,
    EditorErrors.imageLimit,
    EditorErrors.unknownRecipient,
    EditorErrors.refused,
) + UploadProviderId.entries.flatMap {
    listOf(EditorErrors.server(999, it), EditorErrors.malformed(it))
} + EditorErrorSurface.entries.map(EditorErrors::uncertain) + listOf(
    EditorErrors.stopped(EditorErrors.server(503, UploadProviderId.DIBERIE), UploadProgress(9, 10)),
)
