package com.minibrain.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {

    private lateinit var context: Context
    private lateinit var appContainer: DefaultAppContainer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appContainer = DefaultAppContainer(context)
    }

    @Test
    fun `database can be instantiated`() {
        assertNotNull(appContainer.database)
    }

    @Test
    fun `modelDownloader can be instantiated`() {
        assertNotNull(appContainer.modelDownloader)
    }

    @Test
    fun `embedderService can be instantiated`() {
        assertNotNull(appContainer.embedderService)
    }

    @Test
    fun `llmService can be instantiated`() {
        assertNotNull(appContainer.llmService)
    }

    @Test
    fun `documentRepository can be instantiated`() {
        assertNotNull(appContainer.documentRepository)
    }

    @Test
    fun `chatRepository can be instantiated`() {
        assertNotNull(appContainer.chatRepository)
    }

    @Test
    fun `ragPipeline can be instantiated`() {
        assertNotNull(appContainer.ragPipeline)
    }

    @Test
    fun `queryExpander can be instantiated`() {
        assertNotNull(appContainer.queryExpander)
    }

    @Test
    fun `llmReranker can be instantiated`() {
        assertNotNull(appContainer.llmReranker)
    }

    @Test
    fun `hyde can be instantiated`() {
        assertNotNull(appContainer.hyde)
    }

    @Test
    fun `searchPipeline can be instantiated`() {
        assertNotNull(appContainer.searchPipeline)
    }

    @Test
    fun `coverageChecker can be instantiated`() {
        assertNotNull(appContainer.coverageChecker)
    }

    @Test
    fun `agentPipeline can be instantiated`() {
        assertNotNull(appContainer.agentPipeline)
    }
}
