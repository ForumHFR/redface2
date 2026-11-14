package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread

/** Toolbar evidence or session ownership; page-level guards live in [canEditPrivateMessage]. */
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
    thread: PrivateMessageThread,
): Boolean {
    if (connectedPseudo == null || !editActionsAvailable ||
        thread.subject == MpStorageRepository.STORAGE_SUBJECT_HASH
    ) {
        return false
    }
    // Pages 2+ start with the previous page's recap. Its quoteRef can be absent with the tools,
    // so exclude the first message from the fallback even without a parsed ref=0.
    val isPreviousPageRecap = thread.page > 1 && thread.messages.firstOrNull()?.numreponse == message.numreponse
    return message.isEditable ||
        (thread.canReply && isPrivateMessageEditable(message, connectedPseudo) && !isPreviousPageRecap)
}
