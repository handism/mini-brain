package com.minibrain.ui.nav

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(instrumentedPackages = ["androidx.loader.content"])
class AppNavTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAppNavStartDestinationAndTransitions() {
        lateinit var navController: TestNavHostController

        composeTestRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            AppNav(navController = navController)
        }

        // Start destination should be Onboarding
        composeTestRule.onNodeWithText("Mini Brain").assertIsDisplayed()
        assertEquals(Routes.ONBOARDING, navController.currentDestination?.route)

        // Verify routing manually via navController API since UI elements for Home/Chat are tightly coupled with real VMs
        composeTestRule.runOnUiThread {
            navController.navigate(Routes.HOME)
        }
        composeTestRule.waitForIdle()
        assertEquals(Routes.HOME, navController.currentDestination?.route)

        composeTestRule.runOnUiThread {
            navController.navigate(Routes.SETTINGS)
        }
        composeTestRule.waitForIdle()
        assertEquals(Routes.SETTINGS, navController.currentDestination?.route)
    }
}
