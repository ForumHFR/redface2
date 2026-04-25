package fr.forumhfr.redface2.core.data.fixtures

import android.content.Context
import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.parser.HfrParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AssetTopicFixtureRepository(
    private val context: Context,
    private val parser: HfrParser,
    private val ioDispatcher: CoroutineDispatcher,
) : TopicFixtureRepository {
    override suspend fun loadTopicPage(page: Int): Topic = withContext(ioDispatcher) {
        require(page in FixedTopicFixtures.availablePages) {
            "Unsupported embedded topic page: $page"
        }

        val assetPath = "topic_khakha/topic_khakha_page_${page}.html"
        val html = context.assets.open(assetPath)
            .bufferedReader()
            .use { reader -> reader.readText() }
        parser.parseTopicPage(html)
    }
}
