package com.minibrain

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minibrain.data.repo.IndexingState
import com.minibrain.di.AppContainer
import com.minibrain.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import timber.log.Timber

val Context.dataStore by preferencesDataStore(name = "settings")

/** 最後にインデックスが完了した日時と、そのときのフォルダ。Home に「最終インデックス」として出す。 */
internal val PREF_LAST_INDEXED_AT = longPreferencesKey("last_indexed_at")
internal val PREF_LAST_INDEXED_TREE = stringPreferencesKey("last_indexed_tree")
private val PREF_TREE_URI = stringPreferencesKey("tree_uri")

class MiniBrainApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: AppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        applicationScope.launch(Dispatchers.IO) {
            container.documentRepository.ensureFtsIndex()
        }
        // インデックスは Home・Settings のどちらからも走るので、完了の記録はアプリ全体で 1 か所にまとめる
        applicationScope.launch {
            container.documentRepository.indexingState
                .filterIsInstance<IndexingState.Done>()
                .collect {
                    dataStore.edit { prefs ->
                        prefs[PREF_LAST_INDEXED_AT] = System.currentTimeMillis()
                        prefs[PREF_TREE_URI]?.let { prefs[PREF_LAST_INDEXED_TREE] = it }
                    }
                }
        }
    }
}
