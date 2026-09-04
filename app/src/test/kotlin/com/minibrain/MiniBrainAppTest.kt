package com.minibrain

import androidx.test.core.app.ApplicationProvider
import com.minibrain.di.DefaultAppContainer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class MiniBrainAppTest {

    @Test
    fun onCreate_plantsTimberDebugTree() {
        // ApplicationProvider.getApplicationContext implicitly calls onCreate()
        val app = ApplicationProvider.getApplicationContext<MiniBrainApp>()
        assertTrue("Timber should have trees planted", Timber.treeCount > 0)
    }

    @Test
    fun container_isInitializedByDefault() {
        val app = ApplicationProvider.getApplicationContext<MiniBrainApp>()
        assertTrue(app.container is DefaultAppContainer)
    }
}
