package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope

/** ED-160 à ED-172 — prédictions par test dans le contrat commun, contre e5226a65. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageComposeSubmissionContractTest : EditorSubmissionContract() {
    override fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter = PrivateMessageComposeViewModelTest().submissionAdapter(scope, preferences)
}
