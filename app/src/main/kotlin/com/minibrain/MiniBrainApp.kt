package com.minibrain

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.llm.ModelDownloader
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.CoverageChecker
import com.minibrain.ai.search.HyDE
import com.minibrain.ai.search.LlmReranker
import com.minibrain.ai.search.QueryExpander
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.repo.ChatRepository
import com.minibrain.data.repo.DocumentRepository
import com.minibrain.di.AppContainer
import com.minibrain.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

val Context.dataStore by preferencesDataStore(name = "settings")

class MiniBrainApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: AppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        applicationScope.launch(Dispatchers.IO) {
            container.documentRepository.ensureFtsIndex()
        }
    }
}
