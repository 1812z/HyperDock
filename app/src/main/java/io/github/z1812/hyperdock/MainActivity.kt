package io.github.z1812.hyperdock

import io.github.z1812.hyperdock.PrefKeys

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.navigation.HyperDockAppRoot
import io.github.z1812.hyperdock.compose.theme.HyperDockTheme

/** 绾?Android/Compose 搴旂敤鍏ュ彛銆?*/
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val systemInDarkTheme =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val prefs = PrefsRepository(this)
        val useDarkSystemBars = when (prefs.getString(PrefKeys.THEME_MODE, "system")) {
            "light" -> false
            "dark" -> true
            else -> systemInDarkTheme
        }
        val systemBarStyle = if (useDarkSystemBars) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        setContent {
            val context = LocalContext.current
            val repository = remember { PrefsRepository(context) }
            HyperDockTheme(repository) {
                HyperDockAppRoot(repository)
            }
        }
    }
}
