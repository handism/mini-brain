package com.minibrain.ai.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `fromName returns correct model for valid names`() {
        assertEquals(LlmModel.E2B, LlmModel.fromName("E2B"))
        assertEquals(LlmModel.E4B, LlmModel.fromName("E4B"))
    }

    @Test
    fun `fromName returns DEFAULT for invalid names`() {
        assertEquals(LlmModel.DEFAULT, LlmModel.fromName("UNKNOWN"))
        assertEquals(LlmModel.DEFAULT, LlmModel.fromName(""))
    }

    @Test
    fun `fromName returns DEFAULT for null`() {
        assertEquals(LlmModel.DEFAULT, LlmModel.fromName(null))
    }

    @Test
    fun `enum properties are correctly configured`() {
        val e2b = LlmModel.E2B
        assertEquals("gemma-4-E2B-it.litertlm", e2b.fileName)
        assertEquals(2_000_000_000L, e2b.minSize)

        val e4b = LlmModel.E4B
        assertEquals("gemma-4-E4B-it.litertlm", e4b.fileName)
        assertEquals(3_000_000_000L, e4b.minSize)
    }

    @Test
    fun `DataStore extensions correctly save and read LlmModel`() = runTest {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("test.preferences_pb")
        }

        // Initially it should be DEFAULT since nothing is set and fromName returns DEFAULT for null
        assertEquals(LlmModel.DEFAULT, dataStore.selectedLlmModelFlow().first())
        assertEquals(LlmModel.DEFAULT, dataStore.selectedLlmModel())

        // Set to E4B
        dataStore.setSelectedLlmModel(LlmModel.E4B)

        // Should now read E4B
        assertEquals(LlmModel.E4B, dataStore.selectedLlmModelFlow().first())
        assertEquals(LlmModel.E4B, dataStore.selectedLlmModel())

        // Set back to E2B
        dataStore.setSelectedLlmModel(LlmModel.E2B)
        assertEquals(LlmModel.E2B, dataStore.selectedLlmModel())
    }
}
