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

    @TypeConverter
    @JvmStatic
    fun fetchModeFromString(value: String): FetchMode = FetchMode.valueOf(value)

    @TypeConverter
    @JvmStatic
    fun flagTypeToString(value: FlagType): String = value.name

    @TypeConverter
    @JvmStatic
    fun flagTypeFromString(value: String): FlagType = FlagType.valueOf(value)
}
