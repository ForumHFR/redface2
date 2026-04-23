package fr.forumhfr.redface2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import fr.forumhfr.redface2.navigation.RedfaceApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var topicFixtureRepository: TopicFixtureRepository

    private var latestIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        latestIntent = intent

        setContent {
            RedfaceApp(
                intent = latestIntent,
                topicFixtureRepository = topicFixtureRepository,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent = intent
    }
}
