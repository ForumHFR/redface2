package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Poll
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class PollResultBars(
    val options: List<PollOptionResultBar>,
    val blankVote: PollBlankVoteResultBar?,
)

internal data class PollOptionResultBar(
    val text: String,
    val votes: Int,
    val percentage: Int,
    val widthFraction: Float,
    val isLeading: Boolean,
)

internal data class PollBlankVoteResultBar(
    val votes: Int,
    val percentage: Int,
    val widthFraction: Float,
)

@Composable
internal fun TopicPollResultBars(resultBars: PollResultBars) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        resultBars.options.forEach { option ->
            TopicPollResultBarRow(
                label = option.text,
                votes = option.votes,
                percentage = option.percentage,
                widthFraction = option.widthFraction,
                role = if (option.isLeading) PollResultRowRole.LEADING else PollResultRowRole.REGULAR,
            )
        }
        resultBars.blankVote?.let { blankVote ->
            TopicPollResultBarRow(
                label = stringResource(R.string.topic_poll_blank_label),
                votes = blankVote.votes,
                percentage = blankVote.percentage,
                widthFraction = blankVote.widthFraction,
                role = PollResultRowRole.BLANK,
            )
        }
    }
}

/** Role used to select Material color roles for a poll result row. */
private enum class PollResultRowRole { LEADING, REGULAR, BLANK }

@Composable
private fun TopicPollResultBarRow(
    label: String,
    votes: Int,
    percentage: Int,
    widthFraction: Float,
    role: PollResultRowRole,
) {
    val votesLabel = pluralStringResource(R.plurals.topic_poll_result_votes, votes, votes)
    val percentageLabel = stringResource(R.string.topic_poll_result_percentage, percentage)
    val rowDescription = stringResource(
        R.string.topic_poll_result_content_description,
        label,
        votesLabel,
        percentageLabel,
    )
    val rowColors = pollResultRowColors(role)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = rowColors.contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = votesLabel,
                style = MaterialTheme.typography.labelSmall,
                color = rowColors.contentColor,
            )
            Text(
                text = percentageLabel,
                style = MaterialTheme.typography.labelSmall,
                color = rowColors.contentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(rowColors.fillColor),
            )
        }
    }
}

@Composable
private fun pollResultRowColors(role: PollResultRowRole): PollResultRowColors {
    val colors = MaterialTheme.colorScheme
    return when (role) {
        PollResultRowRole.BLANK -> PollResultRowColors(
            fillColor = colors.outline,
            contentColor = colors.onSurfaceVariant,
        )
        PollResultRowRole.LEADING -> PollResultRowColors(
            fillColor = colors.primaryContainer,
            contentColor = colors.onSurface,
        )
        PollResultRowRole.REGULAR -> PollResultRowColors(
            fillColor = colors.tertiaryContainer,
            contentColor = colors.onSurfaceVariant,
        )
    }
}

private data class PollResultRowColors(
    val fillColor: Color,
    val contentColor: Color,
)

/**
 * #1182 — HFR's `Total` includes blank votes when it prints them: on
 * `topic_khakha_page_2.html`, option votes sum to 164 and `(12 votes blancs)` gives the total 176.
 * Bars therefore use `Poll.totalVotes` as the denominator, not the sum of option votes.
 */
internal fun calculatePollResultBars(poll: Poll): PollResultBars {
    val totalVotes = poll.totalVotes.coerceAtLeast(0)
    val optionVotes = poll.options.map { option -> option.votes.coerceAtLeast(0) }
    val blankVotes = poll.blankVotes?.coerceAtLeast(0)
    val percentages = roundedPercentages(optionVotes + listOfNotNull(blankVotes), totalVotes)
    val leadingVoteCount = optionVotes.maxOrNull() ?: 0

    return PollResultBars(
        options = poll.options.mapIndexed { index, option ->
            val votes = optionVotes[index]
            PollOptionResultBar(
                text = option.text,
                votes = votes,
                percentage = percentages.getOrElse(index) { 0 },
                widthFraction = voteWidthFraction(votes, totalVotes),
                isLeading = votes > 0 && votes == leadingVoteCount,
            )
        },
        blankVote = blankVotes?.let { votes ->
            PollBlankVoteResultBar(
                votes = votes,
                percentage = percentages.getOrElse(optionVotes.size) { 0 },
                widthFraction = voteWidthFraction(votes, totalVotes),
            )
        },
    )
}

private fun voteWidthFraction(votes: Int, totalVotes: Int): Float =
    if (totalVotes <= 0) {
        0f
    } else {
        (votes.toFloat() / totalVotes.toFloat()).coerceIn(0f, 1f)
    }

/**
 * Largest-remainder integer percentages. The target is the rounded known share of the displayed rows,
 * capped to 100 so defensive/corrupt data cannot make the rendered percentage sum exceed 100.
 */
private fun roundedPercentages(votes: List<Int>, totalVotes: Int): List<Int> {
    if (totalVotes <= 0 || votes.isEmpty()) return List(votes.size) { 0 }

    val exactPercentages = votes.map { vote -> vote.coerceAtLeast(0).toDouble() * PERCENT_SCALE / totalVotes }
    val targetSum = exactPercentages.sum().roundToInt().coerceIn(0, PERCENT_SCALE)
    val rounded = exactPercentages.map { exact -> floor(exact).toInt() }.toMutableList()
    val delta = targetSum - rounded.sum()

    if (delta > 0) {
        val indicesByRemainder = exactPercentages.indices.sortedWith(
            compareByDescending<Int> { index -> exactPercentages[index] - floor(exactPercentages[index]) }
                .thenBy { index -> index },
        )
        repeat(delta) { step ->
            rounded[indicesByRemainder[step % indicesByRemainder.size]] += 1
        }
    } else if (delta < 0) {
        trimPercentages(rounded, exactPercentages, excess = -delta)
    }

    return rounded
}

private fun trimPercentages(
    rounded: MutableList<Int>,
    exactPercentages: List<Double>,
    excess: Int,
) {
    val indicesByRemainder = exactPercentages.indices.sortedWith(
        compareBy<Int> { index -> exactPercentages[index] - floor(exactPercentages[index]) }
            .thenBy { index -> index },
    )
    var remaining = excess
    while (remaining > 0) {
        var changed = false
        indicesByRemainder.forEach { index ->
            if (remaining > 0 && rounded[index] > 0) {
                rounded[index] -= 1
                remaining -= 1
                changed = true
            }
        }
        if (!changed) return
    }
}

private const val PERCENT_SCALE = 100
