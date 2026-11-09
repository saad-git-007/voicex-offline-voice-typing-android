package com.voicex.app

import android.app.Application
import com.voicex.app.dictation.DictationController
import com.voicex.app.engine.AsrEngine
import com.voicex.app.history.HistoryStore
import com.voicex.app.model.Lang
import com.voicex.app.model.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Process-wide singletons. The accessibility service, the mic service and the UI all share one engine. */
class VoiceXApp : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  lateinit var settings: Settings
    private set
  lateinit var engine: AsrEngine
    private set
  lateinit var downloader: ModelDownloader
    private set
  lateinit var history: HistoryStore
    private set
  lateinit var dictation: DictationController
    private set

  override fun onCreate() {
    super.onCreate()
    AppLog.init(this)
    settings = Settings(this)
    engine = AsrEngine(this, settings)
    downloader = ModelDownloader(this)
    history = HistoryStore(this, settings)
    dictation = DictationController(this)
    Notifications.createChannels(this)
  }

  /** Switches the dictation language (Settings or the dot's menu). The dot service preloads the new model. */
  fun switchLanguage(lang: Lang) {
    if (settings.language == lang) return
    settings.language = lang
    engine.refreshBenchmark()
    AppLog.i("app", "dictation language: ${lang.label}")
  }
}
