package com.minibrain

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
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
