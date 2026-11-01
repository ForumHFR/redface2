package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** ED-160 à ED-172 — nouveau sujet et premier post, mêmes assertions et prédictions à e5226a65. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TopicFormSubmissionContractTest(private val mode: TopicFormMode) : EditorSubmissionContract() {
    override fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter = TopicFormViewModelTest().submissionAdapter(
        mode == TopicFormMode.New, scope, preferences,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<TopicFormMode>> = listOf(
            arrayOf(TopicFormMode.New), arrayOf(TopicFormMode.EditFirstPost),
        )
    }
}
