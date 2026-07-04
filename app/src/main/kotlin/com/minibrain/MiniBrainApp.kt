package com.minibrain

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.llm.ModelDownloader
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.CoverageChecker
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.search.HyDE
import com.minibrain.ai.search.LlmReranker
import com.minibrain.ai.search.QueryExpander
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.repo.ChatRepository
import com.minibrain.data.repo.DocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

val Context.dataStore by preferencesDataStore(name = "settings")

class MiniBrainApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val modelDownloader: ModelDownloader by lazy { ModelDownloader(this) }

    val embedderService: EmbedderService by lazy { EmbedderService() }

    val llmService: LlmService by lazy { LlmService(this) }

    val documentRepository: DocumentRepository by lazy {
        DocumentRepository(this, database.documentDao(), database.chunkDao(), embedderService, database, database.folderEmbeddingDao())
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatSessionDao(), database.chatMessageDao())
    }

    val ragPipeline: RagPipeline by lazy {
        RagPipeline(embedderService, database.chunkDao(), database.documentDao(), database.folderEmbeddingDao())
    }

    val queryExpander: QueryExpander by lazy { QueryExpander(llmService) }

    val llmReranker: LlmReranker by lazy { LlmReranker(llmService) }

    val hyde: HyDE by lazy { HyDE(llmService) }

    val searchPipeline: SearchPipeline by lazy {
        SearchPipeline(queryExpander, llmReranker, ragPipeline, database.chunkDao(), database.documentDao(), hyde)
    }

    val coverageChecker: CoverageChecker by lazy { CoverageChecker(llmService) }

    val agentPipeline: AgentPipeline by lazy {
        AgentPipeline(llmService, embedderService, database.chunkDao(), database.documentDao(), ragPipeline, searchPipeline, coverageChecker)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        applicationScope.launch(Dispatchers.IO) {
            documentRepository.ensureFtsIndex()
        }
    }
}
