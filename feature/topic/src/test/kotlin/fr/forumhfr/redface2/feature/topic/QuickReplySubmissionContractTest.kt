package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope

/** ED-160 à ED-172 — la feuille conserve sa session pendant tous les gestes après le succès. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplySubmissionContractTest : EditorSubmissionContract() {
    override fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter = QuickReplyViewModelTest().submissionAdapter(scope, preferences, submissionScenario)
}
