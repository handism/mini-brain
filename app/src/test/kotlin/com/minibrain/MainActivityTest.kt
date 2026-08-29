package com.minibrain

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.content.ComponentName

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun testValidIntent() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = ComponentName("com.minibrain", "com.minibrain.MainActivity")
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun testInvalidIntent() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.component = ComponentName("com.minibrain", "com.minibrain.MainActivity")
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
    }
}
