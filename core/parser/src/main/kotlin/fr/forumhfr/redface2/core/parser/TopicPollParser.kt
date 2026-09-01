package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import fr.forumhfr.redface2.core.parser.common.PollChoiceCaption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Parses the read-side poll model from one `div.sondage` subtree. */
internal class TopicPollParser {

    fun parse(pollElement: Element?, pollVoteForm: PollVoteForm?): Poll? {
        pollElement ?: return null

        val question = pollElement
            .selectFirst(HfrSelectors.POLL_QUESTION)
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val closed = pollElement.parent()
            ?.selectFirst(HfrSelectors.POLL_CLOSED_MARKER)
            ?.takeIf { marker -> marker.previousElementSibling() === pollElement }
            ?.text()
            ?.contains("Ce sondage est clos") == true
        val canClose = !closed && parseCanClose(pollElement)
        val expiresAt = parseExpiresAt(pollElement.text())
        val blankVotes = parseBlankVotes(totalBlockText(pollElement))

        val optionBars = pollElement.select(HfrSelectors.POLL_OPTION_BAR)
        val optionLabels = pollElement.select(HfrSelectors.POLL_OPTION_LABEL)
        // #697 — HFR serves TWO poll shapes. The RESULTS shape (.sondageLeft bars, below) only
        // exists once the reader voted or clicked « voir les résultats » ; every other fetch —
        // including ALL anonymous reads, i.e. what this app receives — gets the FORM shape
        // (radio/checkbox inputs), which this parser used to drop silently (optionBars empty →
        // null → « aucun sondage ne s'affiche », CharLee's report).
        return if (question != null && optionBars.isEmpty()) {
            parseFormPoll(question, pollVoteForm, closed, canClose, expiresAt, blankVotes)
        } else if (question == null || optionBars.isEmpty() || optionBars.size != optionLabels.size) {
            null
        } else {
            val options = optionBars.mapIndexed { index, optionBar ->
                val percentText = optionBar
                    .select(HfrSelectors.POLL_OPTION_PERCENT)
                    .firstOrNull()
                    ?.text()
                    .orEmpty()
                val votesText = optionBar
                    .select(HfrSelectors.POLL_OPTION_PERCENT)
                    .lastOrNull()
                    ?.text()
                    .orEmpty()

                PollOption(
                    text = optionLabels[index].text().trim(),
                    votes = firstInt(votesText),
                    percentage = firstFloat(percentText),
                )
            }

            val trailingText = pollElement.childNodes()
                .filterIsInstance<TextNode>()
                .joinToString(" ") { it.text() }
            val summaryText = buildString {
                append(pollElement.text())
                append(' ')
                append(trailingText)
            }

            // #779 (PR 1) — the vote cap comes from « Sondage à N choix possibles » on the results
            // card. A mono results poll carries no caption : it allows exactly one pick, so `1` is
            // factual, not invented. `multipleChoice` is derived from the same figure to stay in
            // lockstep with the persisted `maxSelections`.
            val maxSelections = PollChoiceCaption.maxSelections(summaryText) ?: 1
            Poll(
                question = question,
                options = options,
                multipleChoice = maxSelections > 1,
                totalVotes = firstInt(
                    Regex("""Total\s*[:\s]\s*(\d+)\s+votes?""", RegexOption.IGNORE_CASE)
                        .find(summaryText)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty(),
                ),
                hasVoted = false,
                maxSelections = maxSelections,
                closed = closed,
                canClose = canClose,
                expiresAt = expiresAt,
                blankVotes = blankVotes,
            )
        }
    }

    /**
     * #697 — builds a read-only [Poll] from the already parsed [PollVoteForm].
     * No votes/percentages exist in this shape (fields are 0, [Poll.resultsAvailable] = false).
     * Options, input type and maximum selection count all come from that single transformer so the
     * read model and submit contract cannot diverge on the same DOM.
     */
    @Suppress("LongParameterList") // FORM transformer: each parsed field maps to the Poll read model.
    private fun parseFormPoll(
        question: String,
        pollVoteForm: PollVoteForm?,
        closed: Boolean,
        canClose: Boolean,
        expiresAt: LocalDateTime?,
        blankVotes: Int?,
    ): Poll? {
        val form = pollVoteForm ?: return null
        return Poll(
            question = question,
            options = form.choices.map { PollOption(text = it.label, votes = 0, percentage = 0f) },
            multipleChoice = form.multipleChoice,
            totalVotes = 0,
            hasVoted = false,
            resultsAvailable = false,
            maxSelections = form.maxSelections,
            closed = closed,
            canClose = canClose,
            expiresAt = expiresAt,
            blankVotes = blankVotes,
        )
    }

    /**
     * #1206 — HFR renders this adjacent link only for the owner of an open poll. FORM-shaped
     * polls wrap `div.sondage` in the vote form, while RESULTS-shaped polls do not, so adjacency is
     * checked against that form when present and against the poll div otherwise. Scoping the CSS
     * match to the sibling parent prevents an unrelated `close_sondage.php` link elsewhere in the
     * page from granting the capability.
     */
    private fun parseCanClose(pollElement: Element): Boolean {
        val pollContainer = pollElement.parent()?.takeIf { it.tagName() == "form" } ?: pollElement
        return pollContainer.parent()
            ?.select(HfrSelectors.POLL_CLOSE_LINK)
            ?.any { link ->
                link.previousElementSibling() === pollContainer &&
                    link.selectFirst("b.s1Ext")
                        ?.text()
                        ?.trim()
                        ?.equals(CLOSE_POLL_LABEL, ignoreCase = true) == true
            } == true
    }

    internal fun parseExpiresAt(text: String): LocalDateTime? {
        val normalizedText = text
            .replace('\u00A0', ' ')
            .replace(WHITESPACE_REGEX, " ")
        val match = EXPIRY_REGEX.find(normalizedText) ?: return null
        val value = "${match.groupValues[1]} ${match.groupValues[2]}"
        return runCatching { LocalDateTime.parse(value, EXPIRY_FORMATTER) }.getOrNull()
    }

    internal fun parseBlankVotes(text: String?): Int? = text
        ?.let(BLANK_VOTES_REGEX::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun totalBlockText(pollElement: Element): String? = pollElement
        .getElementsByTag("div")
        .firstOrNull { element ->
            element.children().any { child ->
                child.tagName() == "b" && child.text().trim().startsWith("Total")
            }
        }
        ?.text()

    private fun firstInt(text: String): Int =
        Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun firstFloat(text: String): Float =
        Regex("""(\d+(?:[.,]\d+)?)""").find(text)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: 0f

    private companion object {
        val WHITESPACE_REGEX = Regex("""\s+""")
        val EXPIRY_REGEX = Regex(
            """Ce\s+sondage\s+expirera\s+le\s+(\d{2}-\d{2}-\d{4})\s+à\s+(\d{2}:\d{2})""",
        )
        const val CLOSE_POLL_LABEL = "Clore la partie sondage"
        val BLANK_VOTES_REGEX = Regex("""\((\d+)\s+votes?\s+blancs?\)""")
        val EXPIRY_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("dd-MM-uuuu HH:mm")
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
