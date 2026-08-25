package fr.forumhfr.redface2.core.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Runs [block] as a child of the receiver — the process-lifetime [ApplicationScope] — instead of a
 * child of the caller's job, then awaits its result from the caller.
 *
 * This is the repository-wide idiom of [ApplicationScope] (`externalScope.async { }.await()`, cf.
 * `DataStoreUserPreferencesRepository.persist`), lifted to a shared helper so the four HFR write
 * call sites of #1144 spell it identically instead of each inventing a guard.
 *
 * **Why it exists.** Since #1083 every `HfrClient` call is genuinely cancellable
 * (`invokeOnCancellation { call.cancel() }`), so a `viewModelScope.launch { repository.write() }`
 * has its socket cut the instant the user presses back. For a READ that is exactly right. For a
 * server MUTATION the user explicitly asked for it is data loss: the POST may or may not have
 * reached HFR, and the one-shot effect channel that would have reported it dies with the ViewModel.
 * Wrapping the mutation here re-parents it to the process-lifetime scope: navigating away no longer
 * aborts it, while the caller keeps awaiting it and keeps its normal success/failure handling for as
 * long as it is alive.
 *
 * **Semantics.**
 * - The caller is cancelled (screen popped) → [await] throws `CancellationException` in the caller,
 *   whose continuation (state update, effect emission) is skipped, but [block] runs to completion on
 *   [ApplicationScope]. Report the outcome from INSIDE [block] (or from the repository) if it must
 *   survive the caller — anything after `awaitDetached` returns does not.
 * - [block] fails → the exception is re-thrown by [await] to a live caller, exactly as if the call
 *   had been inline. With no caller left, it stays held in the un-awaited `Deferred`, and the
 *   scope's `SupervisorJob` keeps [ApplicationScope] usable for the next write.
 *
 * **Use for discrete, user-initiated writes only** (a confirmed deletion, a favourite the user
 * tapped). Never for reads, prefetches, polling or write-on-keystroke setters: those legitimately
 * rely on caller cancellation, and detaching them would leak work past the screen that wanted it.
 */
suspend fun <T> CoroutineScope.awaitDetached(block: suspend () -> T): T = async { block() }.await()
