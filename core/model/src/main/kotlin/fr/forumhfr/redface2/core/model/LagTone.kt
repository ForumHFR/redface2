package fr.forumhfr.redface2.core.model

/**
 * #814 — how far behind the reader is on a topic, as a 3-step severity of [pagesToRead]. Drives the
 * colour of the « pages à lire » pill INDEPENDENTLY of the flag colour (thibw, fil DEV) : a cyan and a
 * red flag with the same backlog get the same pill.
 *
 * - [LOW] — 1-2 pages (and the never-rendered 0 / negative) : neutral, discreet.
 * - [MEDIUM] — 3-9 pages : accentuated.
 * - [HIGH] — 10 pages or more : alert.
 *
 * Thresholds live in [LAG_TONE_MEDIUM_MIN_PAGES] / [LAG_TONE_HIGH_MIN_PAGES] and are resolved by
 * [lagTone] (`FlagDerivations.kt`). The tone → colour mapping is a `:core:ui` concern (`lagToneColors`).
 * Ordinal order is the severity order — callers may compare tones.
 */
enum class LagTone { LOW, MEDIUM, HIGH }
