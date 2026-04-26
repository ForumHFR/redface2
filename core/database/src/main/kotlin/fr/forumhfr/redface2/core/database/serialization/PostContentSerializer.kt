package fr.forumhfr.redface2.core.database.serialization

import fr.forumhfr.redface2.core.model.PostContent
import kotlinx.serialization.json.Json

internal object PostContentSerializer {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(value: PostContent): String = json.encodeToString(PostContent.serializer(), value)

    fun decode(value: String): PostContent = json.decodeFromString(PostContent.serializer(), value)
}
