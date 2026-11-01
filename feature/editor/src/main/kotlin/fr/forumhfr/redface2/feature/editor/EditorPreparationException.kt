package fr.forumhfr.redface2.feature.editor

/** ED-170: a quote-form GET failed before entering the write repository. */
internal class EditorPreparationException(val error: SubmitError) : Exception()
