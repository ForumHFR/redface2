package fr.forumhfr.redface2.core.ui.post

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #960 (Lot 4, contrat v1.5 §6, cadrage Sol r3 GO) — the per-URL attempt ledger, the single
 * source of truth for media load FAILURES and RETRY generations:
 *
 *  - ONE probe attempt and ONE painter attempt per (URL, generation) — atomic reservation,
 *    concurrent occurrences are denied (they observe the in-flight/settled state instead);
 *  - settlements (success AND failure) carry the generation captured at reservation and are
 *    DISCARDED when stale (lock #5);
 *  - a usable success is TERMINAL for its axis: it neutralises the negative and can never be
 *    re-attempted nor TTL-advanced (lock #4);
 *  - the negative TTL (60 s) NEVER reopens the current generation — consulting an EXPIRED
 *    failure atomically opens a NEW generation, once (C1);
 *  - `retryUrl` (manual retry) atomically clears both axes' negatives and bumps the
 *    generation; `retryFailedUrls` only touches the provided URLs that are actually failed
 *    (lock #1 — strict scope, a healthy URL is never bumped);
 *  - state lives in a snapshot-observable map so `key(generation)` re-evaluates mechanically
 *    (lock #3 — pinned here through the read API).
 */
class MediaAttemptLedgerTest {

    private val url = "https://images.example.org/photo.jpg"
    private val other = "https://images.example.org/other.jpg"

    private fun ledger() = MediaAttemptLedger()

    // ---------- réservation atomique (verrou #2) ----------

    @Test
    fun `only one caller wins the reservation for a given url generation and kind`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        assertFalse("a concurrent occurrence must be denied", l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `probe and painter reservations are independent axes`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PROBE))
        assertTrue("the painter axis is distinct", l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `a stale-generation reservation is denied`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.retryUrl(url) // generation moves on
        assertFalse(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
    }

    // ---------- règlements gen-gardés (verrou #5) ----------

    @Test
    fun `a settlement from a stale generation is discarded`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.retryUrl(url) // invalidates the in-flight attempt
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 1_000L)
        // The stale failure must NOT block the fresh generation's attempt.
        val fresh = l.generationOf(url)
        assertTrue(l.tryReserve(url, fresh, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `a fresh failure blocks further attempts within the same generation - ttl or not`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 1_000L)
        assertFalse("no second attempt in the SAME generation", l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        assertTrue(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 2_000L))
    }

    // ---------- C1 : le TTL ouvre une nouvelle génération (jamais la courante) ----------

    @Test
    fun `consulting an expired failure atomically opens a new generation - once`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 0L)
        // Within TTL: same generation, still failed.
        assertEquals(gen, l.consultGeneration(url, nowMillis = 30_000L))
        assertTrue(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 30_000L))
        // Past TTL: consulting advances the generation exactly once.
        val advanced = l.consultGeneration(url, nowMillis = 61_000L)
        assertEquals(gen + 1, advanced)
        assertEquals("a second consult must not advance again", advanced, l.consultGeneration(url, nowMillis = 62_000L))
        // The fresh generation accepts one new attempt.
        assertTrue(l.tryReserve(url, advanced, MediaAttemptKind.PAINTER))
    }

    // ---------- verrou #4 : succès terminal par axe ----------

    @Test
    fun `a success is terminal for its axis - never re-attempted nor ttl-advanced`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        assertFalse("a settled success is terminal", l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        // No TTL advancement can ever come from a succeeded axis.
        assertEquals(gen, l.consultGeneration(url, nowMillis = 120_000L))
    }

    @Test
    fun `probe KO painter OK stays stable - the G2 protocol settles the probe axis too`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PROBE))
        l.settleFailure(url, gen, MediaAttemptKind.PROBE, nowMillis = 0L)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        // G2 protocol (P2): when the painter provides a usable geometry, the MEASUREMENT need
        // is met — the integration settles the probe axis as a success too. From then on the
        // URL is stable forever: no TTL advancement, no replayed probe, no redecoded painter.
        l.settleSuccess(url, gen, MediaAttemptKind.PROBE)
        assertEquals(gen, l.consultGeneration(url, nowMillis = 120_000L))
        assertFalse(l.tryReserve(url, gen, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a painter success WITHOUT usable geometry leaves the probe axis retryable - never the painter`() {
        // §6 "aucune dimension exploitable → boîte cold conservée": the probe failure stays,
        // expires, and C1 reopens ONLY the probe — the succeeded painter is never replayed.
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleFailure(url, gen, MediaAttemptKind.PROBE, nowMillis = 0L)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)

        val advanced = l.consultGeneration(url, nowMillis = 61_000L)
        assertEquals(gen + 1, advanced)
        assertTrue("the probe may retry in the new generation", l.tryReserve(url, advanced, MediaAttemptKind.PROBE))
        assertFalse("the succeeded painter is terminal", l.tryReserve(url, advanced, MediaAttemptKind.PAINTER))
    }

    // ---------- blockers Sol P1 : InFlight périmé libéré par C1, monotonie des règlements ----------

    @Test
    fun `advancing the generation releases the OTHER axis's stale in-flight reservation`() {
        // Sol P1 blocker 1: probe failed+expired while a slow painter is still in flight —
        // consulting advances the generation; the stale painter reservation must NOT survive it
        // (its settlement is discarded by V5, so nobody would ever free the axis again).
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleFailure(url, gen, MediaAttemptKind.PROBE, nowMillis = 0L)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER) // slow attempt, still in flight

        val advanced = l.consultGeneration(url, nowMillis = 61_000L)
        assertEquals(gen + 1, advanced)
        assertTrue("the expired probe reopens", l.tryReserve(url, advanced, MediaAttemptKind.PROBE))
        assertTrue(
            "the stale in-flight painter must reopen too — a stale reservation can never be settled",
            l.tryReserve(url, advanced, MediaAttemptKind.PAINTER),
        )
    }

    @Test
    fun `advancing the generation releases a stale in-flight probe as well`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 0L)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE) // slow probe, still in flight

        val advanced = l.consultGeneration(url, nowMillis = 61_000L)
        assertEquals(gen + 1, advanced)
        assertTrue(l.tryReserve(url, advanced, MediaAttemptKind.PAINTER))
        assertTrue(l.tryReserve(url, advanced, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a failure settlement never demotes a terminal success`() {
        // Sol P1 blocker 2: settlements must be monotonic — with concurrent render-time writers
        // (smiley occurrences) a late failure could otherwise destroy a terminal success (V4).
        val l = ledger()
        val gen = l.generationOf(url)
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 1_000L)
        assertTrue("the success is terminal", l.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertFalse(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 2_000L))
        // And the axis stays terminal for reservations too.
        assertFalse(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
    }

    // ---------- lectures d'état pour le gate painter (P1b) ----------

    @Test
    fun `hasSucceeded reads the terminal axis state`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertFalse(l.hasSucceeded(url, MediaAttemptKind.PAINTER))
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        assertFalse("in-flight is not succeeded", l.hasSucceeded(url, MediaAttemptKind.PAINTER))
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        assertTrue(l.hasSucceeded(url, MediaAttemptKind.PAINTER))
        assertFalse("axes are independent", l.hasSucceeded(url, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a render-time settlement without a reservation creates the entry`() {
        // The smiley error slot settles at render time (its painter attempt is always current,
        // never reserved) — the settlement must not be silently dropped for an unknown URL.
        val l = ledger()
        l.settleFailure(url, l.generationOf(url), MediaAttemptKind.PAINTER, nowMillis = 1_000L)
        assertTrue(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 2_000L))
    }

    // ---------- réparation d'éviction (Sol P2 : le slot §6 survit au FIFO du cache) ----------

    @Test
    fun `reopenForLostGeometry returns a terminal probe to untried - painter untouched`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleSuccess(url, gen, MediaAttemptKind.PROBE)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleSuccess(url, gen, MediaAttemptKind.PAINTER)
        // The measurement cache evicted the url's geometry (FIFO): the probe's terminal success
        // no longer has a backing truth — the measurer may reopen exactly that axis.
        l.reopenForLostGeometry(url)
        assertTrue("the probe may re-measure", l.tryReserve(url, gen, MediaAttemptKind.PROBE))
        assertFalse("the painter stays terminal", l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        assertEquals("no generation bump — this is a repair, not a retry", gen, l.generationOf(url))
    }

    @Test
    fun `reopenForLostGeometry never touches a non-succeeded probe`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleFailure(url, gen, MediaAttemptKind.PROBE, nowMillis = 0L)
        l.reopenForLostGeometry(url)
        assertFalse("a fresh failure keeps blocking", l.tryReserve(url, gen, MediaAttemptKind.PROBE))
        assertTrue(l.isFailedFresh(url, MediaAttemptKind.PROBE, nowMillis = 1_000L))
    }

    // ---------- rollback d'annulation (verrou #5 : personne ne reste suspendu) ----------

    @Test
    fun `a cancelled attempt rolls back its reservation - a cancelled try is not a try`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PROBE))
        // The winning coroutine is cancelled before settling (screen left, effect disposed):
        // its finally MUST roll the axis back so the URL is not in-flight forever.
        l.rollbackReservation(url, gen, MediaAttemptKind.PROBE)
        assertTrue("the next occurrence may attempt again", l.tryReserve(url, gen, MediaAttemptKind.PROBE))
    }

    @Test
    fun `a stale rollback never clobbers a fresh generation's state`() {
        val l = ledger()
        val gen = l.generationOf(url)
        assertTrue(l.tryReserve(url, gen, MediaAttemptKind.PAINTER))
        l.retryUrl(url) // fresh generation reopened the axis already
        val fresh = l.generationOf(url)
        assertTrue(l.tryReserve(url, fresh, MediaAttemptKind.PAINTER))
        l.settleSuccess(url, fresh, MediaAttemptKind.PAINTER)
        // The old generation's late rollback must be discarded (the axis is succeeded now).
        l.rollbackReservation(url, gen, MediaAttemptKind.PAINTER)
        assertFalse(l.tryReserve(url, fresh, MediaAttemptKind.PAINTER))
    }

    // ---------- retry manuel + refresh scopé (verrou #1) ----------

    @Test
    fun `retryUrl clears both negatives and bumps the generation`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleFailure(url, gen, MediaAttemptKind.PROBE, nowMillis = 0L)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 0L)

        l.retryUrl(url)

        val fresh = l.generationOf(url)
        assertEquals(gen + 1, fresh)
        assertFalse(l.isFailedFresh(url, MediaAttemptKind.PROBE, nowMillis = 1_000L))
        assertFalse(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 1_000L))
        assertTrue(l.tryReserve(url, fresh, MediaAttemptKind.PROBE))
        assertTrue(l.tryReserve(url, fresh, MediaAttemptKind.PAINTER))
    }

    @Test
    fun `retryUrl preserves a settled success - the geometry lock shape`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PROBE)
        l.settleSuccess(url, gen, MediaAttemptKind.PROBE)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 0L)

        l.retryUrl(url)

        val fresh = l.generationOf(url)
        // The failed painter axis reopens; the succeeded probe axis stays terminal.
        assertTrue(l.tryReserve(url, fresh, MediaAttemptKind.PAINTER))
        assertFalse("a succeeded axis is never reopened", l.tryReserve(url, fresh, MediaAttemptKind.PROBE))
    }

    @Test
    fun `retryFailedUrls only bumps the provided urls that are actually failed`() {
        val l = ledger()
        val genUrl = l.generationOf(url)
        val genOther = l.generationOf(other)
        // url fails; other succeeds.
        l.tryReserve(url, genUrl, MediaAttemptKind.PAINTER)
        l.settleFailure(url, genUrl, MediaAttemptKind.PAINTER, nowMillis = 0L)
        l.tryReserve(other, genOther, MediaAttemptKind.PAINTER)
        l.settleSuccess(other, genOther, MediaAttemptKind.PAINTER)

        l.retryFailedUrls(setOf(url, other))

        assertEquals("the failed url is bumped", genUrl + 1, l.generationOf(url))
        assertEquals("the healthy url is untouched", genOther, l.generationOf(other))
    }

    @Test
    fun `retryFailedUrls never touches urls outside the provided scope`() {
        val l = ledger()
        val gen = l.generationOf(url)
        l.tryReserve(url, gen, MediaAttemptKind.PAINTER)
        l.settleFailure(url, gen, MediaAttemptKind.PAINTER, nowMillis = 0L)

        l.retryFailedUrls(setOf(other)) // a DIFFERENT screen's refresh

        assertEquals("out-of-scope url must keep its generation", gen, l.generationOf(url))
        assertTrue(l.isFailedFresh(url, MediaAttemptKind.PAINTER, nowMillis = 1_000L))
    }
}
