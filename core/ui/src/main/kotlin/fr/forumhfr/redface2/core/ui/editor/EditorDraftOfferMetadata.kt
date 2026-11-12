package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import fr.forumhfr.redface2.core.domain.editor.editorQuoteBlocks
import fr.forumhfr.redface2.core.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** ED-40: the offered version's timestamp, independent of writes to C and Q. */
internal fun editorDraftOfferDate(
    updatedAt: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): EditorText {
    val date = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val minutes = ((now - updatedAt).coerceAtLeast(0) / 60_000)
    val days = ChronoUnit.DAYS.between(date.toLocalDate(), today)
    return when {
        minutes < 1 -> EditorText(R.string.editor_draft_date_now)
        minutes < 60 -> EditorText(R.string.editor_draft_date_minutes, listOf(minutes))
        days == 0L -> EditorText(R.string.editor_draft_date_hours, listOf(minutes / 60))
        days == 1L -> EditorText(R.string.editor_draft_date_yesterday,
            listOf(date.format(DateTimeFormatter.ofPattern("HH:mm"))))
        days < 7 -> EditorText(R.string.editor_draft_date_days, listOf(days))
        else -> EditorText(R.string.editor_draft_date_absolute,
            listOf(date.format(DateTimeFormatter.ofPattern("dd/MM")),
                date.format(DateTimeFormatter.ofPattern("HH:mm"))))
    }
}

/** Quotes are a label, not an unreadable BBCode prefill or a second excerpt of someone else's text. */
internal fun editorDraftOfferExcerpt(body: String, context: Context): String {
    fun label(author: String?): String = if (author.isNullOrBlank()) context.getString(R.string.editor_draft_quote)
        else context.getString(R.string.editor_draft_quote_author, author.trim())
    var cursor = 0
    val quotes = buildString {
        editorQuoteBlocks(body, outerOnly = true).sortedBy { it.range.first }.forEach { block ->
            append(body.substring(cursor, block.range.first))
            val text = body.substring(block.range)
            val author = quoteAuthor.find(text)?.groupValues?.get(1)
            append(label(author))
            cursor = block.range.last + 1
        }
        append(body.substring(cursor))
    }
    val excerpt = namedQuote.replace(quotes) { label(it.groupValues[1]) }
    return bbcodeTag.replace(excerpt, "").replace(Regex("\\s+"), " ").trim()
}

private val quoteAuthor = Regex("""\[b](.*?)\s+a écrit\s*:?\s*\[/b]""", RegexOption.IGNORE_CASE)
private val namedQuote = Regex("""\[quote(?:=([^\]]*))?](.*?)\[/quote]""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val bbcodeTag = Regex("""\[/?(?:[a-z#*][^\]]*)]""", RegexOption.IGNORE_CASE)
