package com.minibrain.ui.vm

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minibrain.MiniBrainApp
import com.minibrain.data.repo.ChatRepository
import com.minibrain.data.repo.DocumentRepository
import com.minibrain.data.repo.IndexingState
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @MockK
    private lateinit var app: MiniBrainApp

    @MockK
    private lateinit var container: AppContainer

    @MockK
    private lateinit var documentRepository: DocumentRepository

    @MockK
    private lateinit var chatRepository: ChatRepository

    @MockK
    private lateinit var contentResolver: ContentResolver

    @MockK
    private lateinit var dataStore: DataStore<Preferences>

    private val testDispatcher = StandardTestDispatcher()
    private val prefsFlow = MutableStateFlow<Preferences>(mockk(relaxed = true))
    private val PREF_TREE_URI = stringPreferencesKey("tree_uri")

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
        every { app.contentResolver } returns contentResolver

        val indexingStateFlow = MutableStateFlow<IndexingState>(IndexingState.Idle)
        every { documentRepository.indexingState } returns indexingStateFlow

        every { documentRepository.observeDocCount(any()) } returns flowOf(10)
        every { documentRepository.observeChunkCount(any()) } returns flowOf(50)

        // Setup datastore edit behavior
        coEvery { dataStore.updateData(any()) } returns mockk(relaxed = true)

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
    fun `initial state is null and counts are zero`() = runTest(testDispatcher) {
        val emptyPrefs = mockk<Preferences>()
        every { emptyPrefs[PREF_TREE_URI] } returns null
        every { emptyPrefs.asMap() } returns emptyMap()
        prefsFlow.value = emptyPrefs

        val viewModel = HomeViewModel(app)
        advanceUntilIdle()

        assertEquals(null, viewModel.savedTreeUri.value)
        assertEquals(0, viewModel.docCount.value)
        assertEquals(0, viewModel.chunkCount.value)
    }

    @Test
    fun `savedTreeUri triggers docCount and chunkCount observations`() = runTest(testDispatcher) {
        val uriStr = "content://my_folder"
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI] } returns uriStr
        every { prefs.asMap() } returns mapOf(PREF_TREE_URI to uriStr as Any)
        prefsFlow.value = prefs

        val viewModel = HomeViewModel(app)
        advanceUntilIdle()

        assertEquals(uriStr, viewModel.savedTreeUri.value)
        assertEquals(10, viewModel.docCount.value)
        assertEquals(50, viewModel.chunkCount.value)
    }

    @Test
    fun `onFolderSelected takes permission, saves uri, and starts index`() = runTest(testDispatcher) {
        val emptyPrefs = mockk<Preferences>()
        every { emptyPrefs[PREF_TREE_URI] } returns null
        every { emptyPrefs.asMap() } returns emptyMap()
        prefsFlow.value = emptyPrefs

        val uriStr = "content://new_folder"
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns uriStr

        every {
            contentResolver.takePersistableUriPermission(
                mockUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns Unit

        coEvery { documentRepository.indexFolder(mockUri) } returns Unit

        val viewModel = HomeViewModel(app)
        viewModel.onFolderSelected(mockUri)
        advanceUntilIdle()

        coVerify {
            contentResolver.takePersistableUriPermission(
                mockUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        coVerify { documentRepository.indexFolder(mockUri) }
        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `reindex calls indexFolder with saved uri`() = runTest(testDispatcher) {
        val uriStr = "content://saved_folder"
        val prefs = mockk<Preferences>()
        every { prefs[PREF_TREE_URI] } returns uriStr
        every { prefs.asMap() } returns mapOf(PREF_TREE_URI to uriStr as Any)
        prefsFlow.value = prefs

        val mockUri = mockk<Uri>()
        every { Uri.parse(uriStr) } returns mockUri
        coEvery { documentRepository.indexFolder(mockUri) } returns Unit

        val viewModel = HomeViewModel(app)
        advanceUntilIdle()

        viewModel.reindex()
        advanceUntilIdle()

        coVerify { documentRepository.indexFolder(mockUri) }
    }
}
