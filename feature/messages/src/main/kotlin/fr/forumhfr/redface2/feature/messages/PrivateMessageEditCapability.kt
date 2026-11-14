package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.Post

/** Like topics, own messages remain editable when affichoutils=0 hides the toolbar edit link. */
internal fun isPrivateMessageEditable(message: Post, connectedPseudo: String?): Boolean {
    val isOwnMessage = !connectedPseudo.isNullOrBlank() &&
        canonicalizePseudo(message.author) == canonicalizePseudo(connectedPseudo)
    return message.isEditable || isOwnMessage
}

/** Page evidence and the account-local cache suffice; opening a menu never discovers MPStorage. */
internal fun canEditPrivateMessage(
    message: Post,
    connectedPseudo: String?,
    editActionsAvailable: Boolean,
    subject: String,
): Boolean = isPrivateMessageEditable(message, connectedPseudo) && connectedPseudo != null && editActionsAvailable &&
    subject != MpStorageRepository.STORAGE_SUBJECT_HASH
