package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.EditorError

/** ED-161/170: all surfaces use the same binding; errors share the core:ui catalogue, effects remain feature-local. */
internal typealias EditorSubmissionBinding<E> =
    fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding<E, EditorError>
internal typealias EditorSubmissionCallbacks<E> =
    fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks<E, EditorError>
internal typealias EditorDelivery<E> = fr.forumhfr.redface2.core.domain.editor.EditorDelivery<E, EditorError>
internal typealias EditorSubmissionEnvironment = fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
