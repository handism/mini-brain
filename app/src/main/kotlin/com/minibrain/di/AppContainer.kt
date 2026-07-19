package com.minibrain.di

import android.content.Context
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.CoverageChecker
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.llm.ModelDownloader
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.search.HyDE
import com.minibrain.ai.search.LlmReranker
import com.minibrain.ai.search.QueryExpander
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.repo.ChatRepository
import com.minibrain.data.repo.DocumentRepository

interface AppContainer {
    val database: AppDatabase
    val modelDownloader: ModelDownloader
    val embedderService: EmbedderService
    val llmService: LlmService
    val documentRepository: DocumentRepository
    val chatRepository: ChatRepository
    val ragPipeline: RagPipeline
    val queryExpander: QueryExpander
    val llmReranker: LlmReranker
    val hyde: HyDE
    val searchPipeline: SearchPipeline
    val coverageChecker: CoverageChecker
    val agentPipeline: AgentPipeline
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    override val modelDownloader: ModelDownloader by lazy { ModelDownloader(context) }

    override val embedderService: EmbedderService by lazy { EmbedderService() }

    override val llmService: LlmService by lazy { LlmService() }

    override val documentRepository: DocumentRepository by lazy {
        DocumentRepository(context, database.documentDao(), database.chunkDao(), embedderService, database, database.folderEmbeddingDao())
    }

    override val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatSessionDao(), database.chatMessageDao())
    }

    override val ragPipeline: RagPipeline by lazy {
        RagPipeline(embedderService, database.chunkDao(), database.documentDao(), database.folderEmbeddingDao())
    }

    override val queryExpander: QueryExpander by lazy { QueryExpander(llmService) }

    override val llmReranker: LlmReranker by lazy { LlmReranker(llmService) }

    override val hyde: HyDE by lazy { HyDE(llmService) }

    override val searchPipeline: SearchPipeline by lazy {
        SearchPipeline(queryExpander, llmReranker, ragPipeline, database.chunkDao(), database.documentDao(), hyde)
    }

    override val coverageChecker: CoverageChecker by lazy { CoverageChecker(llmService) }

    override val agentPipeline: AgentPipeline by lazy {
        AgentPipeline(llmService, embedderService, database.chunkDao(), database.documentDao(), ragPipeline, searchPipeline, coverageChecker)
    }
}
