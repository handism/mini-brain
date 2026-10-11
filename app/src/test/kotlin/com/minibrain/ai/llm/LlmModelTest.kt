package com.minibrain.ai.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
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
        assertThat(LlmModel.fromName("E2B")).isEqualTo(LlmModel.E2B)
        assertThat(LlmModel.fromName("E4B")).isEqualTo(LlmModel.E4B)
    }

    @Test
    fun `fromName returns DEFAULT for invalid names`() {
        assertThat(LlmModel.fromName("UNKNOWN")).isEqualTo(LlmModel.DEFAULT)
        assertThat(LlmModel.fromName("")).isEqualTo(LlmModel.DEFAULT)
    }

    @Test
    fun `fromName returns DEFAULT for null`() {
        assertThat(LlmModel.fromName(null)).isEqualTo(LlmModel.DEFAULT)
    }

    @Test
    fun `enum properties are correctly configured`() {
        val e2b = LlmModel.E2B
        assertThat(e2b.fileName).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(e2b.minSize).isEqualTo(2_000_000_000L)

        val e4b = LlmModel.E4B
        assertThat(e4b.fileName).isEqualTo("gemma-4-E4B-it.litertlm")
        assertThat(e4b.minSize).isEqualTo(3_000_000_000L)
    }

    @Test
    fun `DataStore extensions correctly save and read LlmModel`() = runTest {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("test.preferences_pb")
        }

        // Initially it should be DEFAULT since nothing is set and fromName returns DEFAULT for null
        assertThat(dataStore.selectedLlmModelFlow().first()).isEqualTo(LlmModel.DEFAULT)
        assertThat(dataStore.selectedLlmModel()).isEqualTo(LlmModel.DEFAULT)

        // Set to E4B
        dataStore.setSelectedLlmModel(LlmModel.E4B)

        // Should now read E4B
        assertThat(dataStore.selectedLlmModelFlow().first()).isEqualTo(LlmModel.E4B)
        assertThat(dataStore.selectedLlmModel()).isEqualTo(LlmModel.E4B)

        // Set back to E2B
        dataStore.setSelectedLlmModel(LlmModel.E2B)
        assertThat(dataStore.selectedLlmModel()).isEqualTo(LlmModel.E2B)
    }
}
