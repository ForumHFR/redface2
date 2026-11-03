package fr.forumhfr.redface2.feature.editor

/** ED-161/170: all surfaces use the same binding; errors/effects remain feature-local. */
internal typealias EditorSubmissionBinding<E> =
    fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding<E, SubmitError>
internal typealias EditorSubmissionCallbacks<E> =
    fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks<E, SubmitError>
internal typealias EditorDelivery<E> = fr.forumhfr.redface2.core.domain.editor.EditorDelivery<E, SubmitError>
internal typealias EditorSubmissionEnvironment = fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
