package com.minibrain.ui.vm

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minibrain.MiniBrainApp
import com.minibrain.ai.llm.ModelDownloader
import com.minibrain.data.repo.ChatRepository
import com.minibrain.data.repo.DocumentRepository
import com.minibrain.dataStore
import com.minibrain.di.AppContainer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @MockK
    private lateinit var app: MiniBrainApp

    @MockK
    private lateinit var container: AppContainer

    @MockK
    private lateinit var documentRepository: DocumentRepository

    @MockK
    private lateinit var chatRepository: ChatRepository

    @MockK
    private lateinit var modelDownloader: ModelDownloader

    @MockK
    private lateinit var contentResolver: ContentResolver

    @MockK
    private lateinit var dataStore: DataStore<Preferences>

    private val testDispatcher = StandardTestDispatcher()
    private val prefsFlow = MutableStateFlow<Preferences>(mockk())
    private val PREF_TREE_URI_SETTINGS = stringPreferencesKey("tree_uri")
    private val PREF_SHOW_SEARCH_LOG = booleanPreferencesKey("show_search_log")

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns dataStore
        every { dataStore.data } returns prefsFlow
        every { app.container } returns container
        every { container.documentRepository } returns documentRepository
        every { container.chatRepository } returns chatRepository
        every { container.modelDownloader } returns modelDownloader
        every { app.contentResolver } returns contentResolver

        // Setup datastore edit behavior
        coEvery { dataStore.updateData(any()) } returns mockk(relaxed = true)

        // Default prefs behavior
        val defaultPrefs = mockk<Preferences>()
        every { defaultPrefs[PREF_TREE_URI_SETTINGS] } returns null
        every { defaultPrefs[PREF_SHOW_SEARCH_LOG] } returns true
        every { defaultPrefs.asMap() } returns emptyMap()
        prefsFlow.value = defaultPrefs


        // Mock static Uri
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val mockUri = mockk<Uri>()
            every { mockUri.toString() } returns firstArg()
            mockUri
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.minibrain.MiniBrainAppKt")
        unmockkStatic(Uri::class)
    }

    @Test
    fun `initial states are loaded correctly`() = runTest(testDispatcher) {
        val uriStr = "content://my_folder"
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI_SETTINGS] } returns uriStr
        every { prefs[PREF_SHOW_SEARCH_LOG] } returns false
        every { prefs.asMap() } returns mapOf(
            PREF_TREE_URI_SETTINGS to uriStr as Any,
            PREF_SHOW_SEARCH_LOG to false as Any
        )
        prefsFlow.value = prefs

        val viewModel = SettingsViewModel(app)
        advanceUntilIdle()

        assertEquals(uriStr, viewModel.savedTreeUri.value)
        assertEquals(false, viewModel.showSearchLog.value)
    }

    @Test
    fun `default showSearchLog is true when not set`() = runTest(testDispatcher) {
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI_SETTINGS] } returns null
        every { prefs[PREF_SHOW_SEARCH_LOG] } returns null
        every { prefs.asMap() } returns emptyMap()
        prefsFlow.value = prefs

        val viewModel = SettingsViewModel(app)
        advanceUntilIdle()

        assertEquals(true, viewModel.showSearchLog.value)
    }

    @Test
    fun `setShowSearchLog updates datastore`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(app)
        viewModel.setShowSearchLog(false)
        advanceUntilIdle()

        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `reindex calls indexFolder with saved uri`() = runTest(testDispatcher) {
        val uriStr = "content://saved_folder"
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI_SETTINGS] } returns uriStr
        every { prefs[PREF_SHOW_SEARCH_LOG] } returns true
        every { prefs.asMap() } returns mapOf(PREF_TREE_URI_SETTINGS to uriStr as Any)
        prefsFlow.value = prefs

        val mockUri = mockk<Uri>()
        every { Uri.parse(uriStr) } returns mockUri
        coEvery { documentRepository.indexFolder(mockUri) } returns Unit

        val viewModel = SettingsViewModel(app)
        advanceUntilIdle()

        viewModel.reindex()
        advanceUntilIdle()

        coVerify { documentRepository.indexFolder(mockUri) }
    }

    @Test
    fun `reindex does nothing if saved tree uri is null`() = runTest(testDispatcher) {
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI_SETTINGS] } returns null
        every { prefs[PREF_SHOW_SEARCH_LOG] } returns true
        every { prefs.asMap() } returns emptyMap()
        prefsFlow.value = prefs

        val viewModel = SettingsViewModel(app)
        advanceUntilIdle()

        viewModel.reindex()
        advanceUntilIdle()

        coVerify(exactly = 0) { documentRepository.indexFolder(any()) }
    }

    @Test
    fun `changeFolder clears old uri, takes permission, updates datastore, and indexes new uri`() = runTest(testDispatcher) {
        val oldUriStr = "content://old_folder"
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI_SETTINGS] } returns oldUriStr
        every { prefs[PREF_SHOW_SEARCH_LOG] } returns true
        every { prefs.asMap() } returns mapOf(PREF_TREE_URI_SETTINGS to oldUriStr as Any)
        prefsFlow.value = prefs

        val newUriStr = "content://new_folder"
        val mockNewUri = mockk<Uri>()
        every { mockNewUri.toString() } returns newUriStr

        every {
            contentResolver.takePersistableUriPermission(
                mockNewUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns Unit

        coEvery { documentRepository.clearFolder(oldUriStr) } returns Unit
        coEvery { documentRepository.indexFolder(mockNewUri) } returns Unit

        val viewModel = SettingsViewModel(app)
        advanceUntilIdle()

        viewModel.changeFolder(mockNewUri)
        advanceUntilIdle()

        coVerify { documentRepository.clearFolder(oldUriStr) }
        coVerify {
            contentResolver.takePersistableUriPermission(
                mockNewUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        coVerify { dataStore.updateData(any()) }
        coVerify { documentRepository.indexFolder(mockNewUri) }
    }

    @Test
    fun `clearChatHistory calls chatRepository clearAll`() = runTest(testDispatcher) {
        coEvery { chatRepository.clearAll() } returns Unit

        val viewModel = SettingsViewModel(app)
        viewModel.clearChatHistory()
        advanceUntilIdle()

        coVerify { chatRepository.clearAll() }
    }

    @Test
    fun `model files are retrieved correctly`() {
        val llmFile = File("llm.bin")
        val embedderFile = File("embedder.onnx")
        every { modelDownloader.llmModelFile } returns llmFile
        every { modelDownloader.embedderModelFile } returns embedderFile

        val viewModel = SettingsViewModel(app)

        assertEquals(llmFile, viewModel.llmModelFile)
        assertEquals(embedderFile, viewModel.embedderModelFile)
    }
}
