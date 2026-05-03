package fr.forumhfr.redface2.core.database.converters

import androidx.room.TypeConverter
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.serialization.PostContentSerializer
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal object Converters {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val intListSerializer = ListSerializer(Int.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    @TypeConverter
    @JvmStatic
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    @JvmStatic
    fun instantFromEpochMillis(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    @JvmStatic
    fun intListToJson(value: List<Int>): String = json.encodeToString(intListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun intListFromJson(value: String): List<Int> = json.decodeFromString(intListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun stringListToJson(value: List<String>): String =
        json.encodeToString(stringListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun stringListFromJson(value: String): List<String> =
        json.decodeFromString(stringListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun postContentToJson(value: PostContent): String =
        PostContentSerializer.encode(value)

    @TypeConverter
    @JvmStatic
    fun postContentFromJson(value: String): PostContent =
        PostContentSerializer.decode(value)

    @TypeConverter
    @JvmStatic
    fun fetchModeToString(value: FetchMode): String = value.name

    /**
     * Defensive read : if a future PR ever renames a [FetchMode] entry, every cached
     * row written by an older build holding the old name would `IllegalArgumentException`
     * here and crash the DAO. Falling back to `AUTHENTICATED` keeps the page openable
     * — at worst the row is treated as authenticated and the next user-driven fetch
     * overwrites it with the current canonical value. Renaming an enum value is a
     * schema-affecting change ; if you need to do it, write a Room migration that
     * rewrites every affected column instead of relying on this fallback.
     */
    @TypeConverter
    @JvmStatic
    fun fetchModeFromString(value: String): FetchMode =
        runCatching { FetchMode.valueOf(value) }.getOrDefault(FetchMode.AUTHENTICATED)

    @TypeConverter
    @JvmStatic
    fun flagTypeToString(value: FlagType): String = value.name

    /**
     * Defensive read for the same reason as [fetchModeFromString]. Falls back to
     * [FlagType.CYAN] (sujets participés — the most common drapeau bucket). Still
     * a destructive fallback : the row's true type is lost. Renaming a [FlagType]
     * value should bump Room and rewrite `flag_topics.type`.
     */
    @TypeConverter
    @JvmStatic
    fun flagTypeFromString(value: String): FlagType =
        runCatching { FlagType.valueOf(value) }.getOrDefault(FlagType.CYAN)
}
