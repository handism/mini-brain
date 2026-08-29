package com.minibrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.minibrain.ui.nav.AppNav
import com.minibrain.ui.theme.MiniBrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (intent?.action != null && intent.action != android.content.Intent.ACTION_MAIN) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            MiniBrainTheme {
                AppNav()
            }
        }
    }
}
