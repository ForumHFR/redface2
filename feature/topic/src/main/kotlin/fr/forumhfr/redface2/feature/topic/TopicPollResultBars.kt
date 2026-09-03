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
import java.util.Locale
import kotlin.math.roundToInt

internal data class PollResultBars(
    val options: List<PollOptionResultBar>,
    val blankVote: PollBlankVoteResultBar?,
)

internal data class PollOptionResultBar(
    val text: String,
    val votes: Int,
    val percentage: String,
    val widthFraction: Float,
    val isLeading: Boolean,
)

internal data class PollBlankVoteResultBar(
    val votes: Int,
    val percentage: String,
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
    percentage: String,
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

/** HFR exposes poll option percentages directly; use them before any defensive recomputation. */
internal fun calculatePollResultBars(poll: Poll): PollResultBars {
    val totalVotes = poll.totalVotes.coerceAtLeast(0)
    val optionVotes = poll.options.map { option -> option.votes.coerceAtLeast(0) }
    val blankVotes = poll.blankVotes?.coerceAtLeast(0)
    val fallbackPercentages = fallbackOptionPercentages(optionVotes)
    val leadingVoteCount = optionVotes.maxOrNull() ?: 0

    return PollResultBars(
        options = poll.options.mapIndexed { index, option ->
            val votes = optionVotes[index]
            val percentage = parsedResultPercentage(option.percentage, votes)
                ?: fallbackPercentages.getOrElse(index) { resultPercentage(0f) }
            PollOptionResultBar(
                text = option.text,
                votes = votes,
                percentage = percentage.label,
                widthFraction = percentageWidthFraction(percentage.value),
                isLeading = votes > 0 && votes == leadingVoteCount,
            )
        },
        blankVote = blankVotes?.let { votes ->
            val percentage = resultPercentage(percentageFromVotes(votes, totalVotes))
            PollBlankVoteResultBar(
                votes = votes,
                percentage = percentage.label,
                widthFraction = percentageWidthFraction(percentage.value),
            )
        },
    )
}

private data class PollResultPercentage(
    val value: Float,
    val label: String,
)

private fun parsedResultPercentage(percentage: Float, votes: Int): PollResultPercentage? {
    if (!percentage.isFinite() || percentage < 0f) return null
    return if (percentage > 0f || votes == 0) {
        resultPercentage(percentage)
    } else {
        null
    }
}

private fun fallbackOptionPercentages(votes: List<Int>): List<PollResultPercentage> {
    val totalOptionVotes = votes.sum()
    return votes.map { vote -> resultPercentage(percentageFromVotes(vote, totalOptionVotes)) }
}

private fun percentageFromVotes(votes: Int, totalVotes: Int): Float =
    if (totalVotes <= 0) 0f else votes.coerceAtLeast(0).toFloat() * PERCENT_SCALE / totalVotes

private fun resultPercentage(value: Float): PollResultPercentage {
    val clamped = value.coerceIn(0f, PERCENT_SCALE)
    return PollResultPercentage(
        value = clamped,
        label = formatPercentage(clamped),
    )
}

private fun formatPercentage(value: Float): String {
    val roundedToTenth = (value * TENTH_SCALE).roundToInt().toFloat() / TENTH_SCALE
    val roundedInteger = roundedToTenth.roundToInt()
    return if (roundedToTenth == roundedInteger.toFloat()) {
        roundedInteger.toString()
    } else {
        String.format(Locale.ROOT, "%.1f", roundedToTenth)
    }
}

private fun percentageWidthFraction(percentage: Float): Float =
    (percentage / PERCENT_SCALE).coerceIn(0f, 1f)

private const val PERCENT_SCALE = 100f
private const val TENTH_SCALE = 10f
