package fr.forumhfr.redface2.core.network

import java.time.Duration

object HfrConstants {
    const val BASE_URL: String = "https://forum.hardware.fr/"

    const val USER_AGENT: String = "Redface2/0.1 (Android; +https://github.com/ForumHFR/redface2)"

    val ConnectTimeout: Duration = Duration.ofSeconds(15)
    val ReadTimeout: Duration = Duration.ofSeconds(20)
    val WriteTimeout: Duration = Duration.ofSeconds(20)
    val CallTimeout: Duration = Duration.ofSeconds(30)
}
