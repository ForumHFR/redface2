package fr.forumhfr.redface2.core.domain.coroutines

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] (a `SupervisorJob` on the IO
 * dispatcher) used for writes that MUST outlive the component that triggered them — typically a
 * DataStore commit started from a screen whose `viewModelScope` may be cancelled before the write
 * lands (#507). Not for general background work: most coroutines should stay scoped to their owner.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
