package com.voicex.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.voicex.app.VoiceXApp
import com.voicex.app.ui.theme.VoiceXTheme

sealed interface Screen {
  data object Home : Screen

  data class Setup(val step: String?, val nonce: Long = System.nanoTime()) : Screen

  data object Settings : Screen

  data object History : Screen

  data object Test : Screen

  data object Logs : Screen
}

class MainActivity : ComponentActivity() {
  private var screen by mutableStateOf<Screen>(Screen.Home)

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as VoiceXApp
    screen = startScreen(intent, app)
    setContent { VoiceXTheme { Root(app) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.getStringExtra(EXTRA_STEP)?.let { screen = Screen.Setup(it) }
  }

  private fun startScreen(intent: Intent?, app: VoiceXApp): Screen {
    val step = intent?.getStringExtra(EXTRA_STEP)
    return when {
      step != null -> Screen.Setup(step)
      !app.settings.setupDone -> Screen.Setup(null)
      else -> Screen.Home
    }
  }

  @Composable
  private fun Root(app: VoiceXApp) {
    val s = screen
    BackHandler(enabled = s != Screen.Home && !(s is Screen.Setup && !app.settings.setupDone)) { screen = Screen.Home }
    val go: (Screen) -> Unit = { screen = it }
    when (s) {
      Screen.Home -> HomeScreen(app, go)
      // keyed on the request, so a deep link from the dot (onNewIntent) restarts the wizard at its step
      is Screen.Setup -> androidx.compose.runtime.key(s.nonce) {
        SetupWizard(app, s.step, onDone = { app.settings.setupDone = true; screen = Screen.Home }, onBack = { screen = Screen.Home })
      }
      Screen.Settings -> SettingsScreen(app, go)
      Screen.History -> HistoryScreen(app, go)
      Screen.Test -> TestScreen(app, go)
      Screen.Logs -> LogsScreen(app, go)
    }
  }

  companion object {
    const val EXTRA_STEP = "step"
  }
}
