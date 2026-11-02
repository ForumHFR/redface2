package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject

/** ED-164/170/171: session collaborators grouped independently of the surface's rendering. */
class EditorSessionResources @Inject constructor(
    val drafts: EditorDraftStore,
    val preferences: UserPreferencesRepository,
    val auth: AuthRepository,
    val clock: DiagnosticsClock,
    val diagnostics: DiagnosticsLog,
)
