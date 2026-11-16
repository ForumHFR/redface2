package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant

@Entity(tableName = "flag_category_coverage", primaryKeys = ["userId", "type", "cat", "source"])
data class FlagCategoryCoverageEntity(
    val userId: String,
    val type: FlagType,
    val cat: Int,
    val source: FlagSource,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant,
    val failure: FlagFailureKind?,
)
