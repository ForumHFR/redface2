package fr.forumhfr.redface2.feature.settings

const val REPORT_RECIPIENT = "XaTelitte"
internal const val REPORT_SUBJECT = "Redface 2 — Signalement"

/** Supplied by :app; never guess the channel from a version suffix. */
data class ReportBuildInfo(val versionName: String, val versionCode: Int, val channel: String)
