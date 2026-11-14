package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.Post

/** Page evidence and the account-local cache suffice; opening a menu never discovers MPStorage. */
internal fun canEditPrivateMessage(
    message: Post,
    connectedPseudo: String?,
    editActionsAvailable: Boolean,
    subject: String,
): Boolean = message.isEditable && connectedPseudo != null && editActionsAvailable &&
    subject != MpStorageRepository.STORAGE_SUBJECT_HASH
