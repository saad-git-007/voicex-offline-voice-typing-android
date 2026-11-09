package com.voicex.app.dot

import android.accessibilityservice.AccessibilityService
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings as AndroidSettings
import com.voicex.app.AppLog
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.voicex.app.Settings
import com.voicex.app.VoiceXApp
import com.voicex.app.dictation.DotState
import com.voicex.app.dictation.OutputSink
import com.voicex.app.model.Lang
import com.voicex.app.model.ModelFiles
import com.voicex.app.model.ModelSpec
import com.voicex.app.output.AutoPaster
import com.voicex.app.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "VoiceX dictation dot". Watches window changes for a soft keyboard (AccessibilityWindowInfo.TYPE_INPUT_METHOD)
 * and shows the dot just above it.
 *
 * Privacy: it never reads, stores or logs on-screen text. It reads window *types and bounds* only. The single
 * exception is the optional auto-paste, which acts on the focused editable field right after a dictation.
 */
class DotAccessibilityService : AccessibilityService(), DotView.Listener, OutputSink {
  private val app
    get() = application as VoiceXApp

  private lateinit var wm: WindowManager
  private var dot: DotView? = null
  private var menu: DotMenu? = null
  private var params: WindowManager.LayoutParams? = null
  private var attached = false
  private var useAppOverlay = false

  private var keyboardVisible = false
  private var keyboardTop = 0
  private var hiddenUntilNextKeyboard = false
  private var dotPos: DotPlacement.Pos? = null
  /** True while the finger is dragging the dot: our own window moves fire TYPE_WINDOWS_CHANGED, which must not snap it back. */
  private var dragging = false

  private var stateJob: Job? = null
  private var settingsJob: Job? = null
  private var unloadJob: Job? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    AppLog.i(TAG, "service connected")
    instance = this
    wm = getSystemService(WindowManager::class.java)
    useAppOverlay = app.settings.fgsBlockedSeen && AndroidSettings.canDrawOverlays(this)
    dot = DotView(this, this).also { applySettings(it) }
    updateLanguages()
    menu = DotMenu(this, wm, onLanguage = ::pickLanguage, onHide = ::hideUntilNextKeyboard)
    app.dictation.output = this
    stateJob = app.scope.launch { app.dictation.state.collect { onDictationState(it) } }
    settingsJob = app.scope.launch {
      app.settings.changes.collect { key ->
        dot?.let { applySettings(it) }
        if (key == Settings.K_DOT_SIZE) app.settings.resetDotOffsets()
        if (key == Settings.K_KEEP_LOADED || key == Settings.K_MODEL || key == Settings.K_BACKEND || key == Settings.K_THREADS || key == Settings.K_LANGUAGE) maybePreload()
        if (key == Settings.K_WHISPER_LANGUAGE) dot?.invalidate()
        refresh()
      }
    }
    maybePreload()
    checkKeyboard()
  }

  private fun applySettings(v: DotView) {
    val s = app.settings
    v.dotSizePx = (s.dotSizeDp * resources.displayMetrics.density).toInt()
    v.idleAlpha = s.dotOpacity
    v.hapticsEnabled = s.haptics
    v.badgeSource = { if (multiLang) app.settings.let { it.langBadge(it.language) } else null }
  }

  /** Default lifecycle: load at service start and keep resident. */
  private fun maybePreload() {
    if (!app.settings.keepLoaded) return
    if (!ModelFiles.isPresent(this, ModelSpec.active(app.settings))) return
    app.scope.launch { runCatching { app.engine.ensureLoaded() }.onFailure { AppLog.w(TAG, "preload failed: $it") } }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkKeyboard()
  }

  override fun onInterrupt() = Unit

  /** Reads only the window list's types and bounds. */
  private fun checkKeyboard() {
    val ime = runCatching { windows }.getOrNull()?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    val r = Rect()
    ime?.getBoundsInScreen(r)
    val visible = ime != null && r.height() > 0
    if (visible && !keyboardVisible) {
      hiddenUntilNextKeyboard = false
      onKeyboardOpened()
    } else if (!visible && keyboardVisible) {
      onKeyboardClosed()
    }
    keyboardVisible = visible
    if (visible) keyboardTop = r.top
    refresh()
  }

  /** True when more than one language's model is downloaded: only then is there a language to switch to. */
  @Volatile private var multiLang = false

  /** Languages whose model is downloaded. */
  private fun downloadedLangs(): List<Lang> = Lang.entries.filter { ModelFiles.isPresent(this, ModelSpec.forLang(it, app.settings)) }

  /**
   * Re-reads which models are downloaded. With a single language downloaded there is no menu or badge, and the
   * dictation language follows that model (e.g. back to English after the Urdu model is deleted).
   */
  private fun updateLanguages() {
    val langs = downloadedLangs()
    multiLang = langs.size > 1
    if (langs.size == 1 && app.settings.language != langs[0]) app.switchLanguage(langs[0])
    dot?.invalidate()
  }

  private fun onKeyboardOpened() {
    updateLanguages()
    unloadJob?.cancel()
    if (!app.settings.keepLoaded && ModelFiles.isPresent(this, ModelSpec.active(app.settings))) {
      app.scope.launch { runCatching { app.engine.ensureLoaded() } }
    }
  }

  private fun onKeyboardClosed() {
    if (!app.settings.keepLoaded) {
      unloadJob?.cancel()
      unloadJob = app.scope.launch {
        delay(60_000)
        if (!app.dictation.isBusy && !keyboardVisible) app.engine.release()
      }
    }
  }

  private fun onDictationState(s: DotState) {
    val v = dot ?: return
    val prev = v.state
    v.state = s
    if (s !is DotState.Idle && s !is DotState.Info) menu?.dismiss()
    when {
      s is DotState.Recording && prev !is DotState.Recording -> v.haptic(DotView.HAPTIC_TICK)
      s is DotState.Done -> v.haptic(DotView.HAPTIC_CONFIRM)
      s is DotState.Error && prev !is DotState.Error -> v.haptic(DotView.HAPTIC_REJECT)
    }
    refresh()
  }

  /** Shows/hides and positions the dot. Visible while the keyboard is up, and always while busy. */
  private fun refresh() {
    val v = dot ?: return
    val s = app.dictation.state.value
    val busy = s is DotState.Recording || s is DotState.Transcribing || s is DotState.Done || s is DotState.Info
    val show = (keyboardVisible && !hiddenUntilNextKeyboard) || busy
    if (!show) {
      menu?.dismiss()
      detach()
      return
    }
    val bounds = wm.currentWindowMetrics.bounds
    val landscape = bounds.width() > bounds.height()
    val margin = (12 * resources.displayMetrics.density).toInt()
    if (keyboardVisible && !dragging) {
      dotPos = DotPlacement.place(keyboardTop, bounds.width(), bounds.height(), v.dotSizePx, margin, app.settings.dotOffset(landscape))
    }
    val pos = dotPos ?: DotPlacement.place(bounds.height() * 2 / 3, bounds.width(), bounds.height(), v.dotSizePx, margin, null).also { dotPos = it }
    val labelW = v.preferredWidth() - v.dotSizePx - 2 * v.pad
    v.labelOnRight = pos.x < labelW
    val p = params ?: newParams().also { params = it }
    p.width = v.preferredWidth()
    p.height = v.preferredHeight()
    p.x = pos.x - v.dotLeftInView()
    p.y = pos.y - v.pad
    if (!attached) {
      try {
        wm.addView(v, p)
        attached = true
      } catch (e: Exception) {
        AppLog.e(TAG, "could not add the dot window", e)
      }
    } else {
      runCatching { wm.updateViewLayout(v, p) }
    }
  }

  private fun newParams() =
    WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (useAppOverlay) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      title = "VoiceX dot"
    }

  private fun detach() {
    if (attached) {
      runCatching { wm.removeViewImmediate(dot) }
      attached = false
    }
  }

  // ---- DotView.Listener -------------------------------------------------------------------------------------
  override fun onTap() {
    if (menu?.isShowing == true) {
      menu?.dismiss()
      return
    }
    when (val s = app.dictation.state.value) {
      is DotState.Error -> {
        app.dictation.dismiss()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_STEP, s.kind.setupStep))
      }
      else -> app.dictation.toggle()
    }
  }

  override fun onLongPress() {
    when (app.dictation.state.value) {
      is DotState.Recording, DotState.Transcribing -> {
        dot?.haptic(DotView.HAPTIC_REJECT)
        app.dictation.cancel()
      }
      DotState.Idle, is DotState.Info, is DotState.Error -> {
        val v = dot ?: return
        val pos = dotPos ?: return
        app.dictation.dismiss()
        updateLanguages()
        // one language downloaded: nothing to switch to, so long-press just hides the dot
        if (!multiLang) return hideUntilNextKeyboard()
        v.haptic(DotView.HAPTIC_LONG)
        menu?.show(app.settings.language, ::menuLabel, pos.x, pos.y, v.dotSizePx, params?.type ?: WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
      }
      else -> Unit
    }
  }

  /** "English", "اردو  Urdu", "Français  French"; one name when both are the same. */
  private fun menuLabel(lang: Lang): String {
    val s = app.settings
    val en = s.langLabel(lang)
    val nat = s.langNative(lang)
    return if (nat == en) en else "$nat  $en"
  }

  private fun pickLanguage(lang: Lang) {
    dot?.haptic(DotView.HAPTIC_TICK)
    app.switchLanguage(lang)
    dot?.invalidate()
    val ready = ModelFiles.isPresent(this, ModelSpec.active(app.settings))
    app.dictation.showInfo(if (ready) app.settings.langNative(lang) else "${app.settings.langLabel(lang)} model not downloaded", if (ready) 1200 else 2500)
  }

  private fun hideUntilNextKeyboard() {
    dot?.haptic(DotView.HAPTIC_LONG)
    hiddenUntilNextKeyboard = true
    refresh()
  }

  override fun onDrag(dx: Int, dy: Int) {
    menu?.dismiss()
    dragging = true
    val v = dot ?: return
    val cur = dotPos ?: return
    val b = wm.currentWindowMetrics.bounds
    dotPos = DotPlacement.clamp(cur.x + dx, cur.y + dy, b.width(), b.height(), v.dotSizePx)
    params?.let { p ->
      p.x = dotPos!!.x - v.dotLeftInView()
      p.y = dotPos!!.y - v.pad
      runCatching { wm.updateViewLayout(v, p) }
    }
  }

  override fun onDragEnd() {
    dragging = false
    val pos = dotPos ?: return
    if (!keyboardVisible) return
    val b = wm.currentWindowMetrics.bounds
    val (x, dy) = DotPlacement.offsetFor(pos.x, pos.y, keyboardTop)
    app.settings.setDotOffset(b.width() > b.height(), x, dy)
  }

  // ---- OutputSink -------------------------------------------------------------------------------------------
  override fun paste(text: String): AutoPaster.Outcome = runCatching { AutoPaster.paste(this, text) }.getOrDefault(AutoPaster.Outcome.FAILED)

  override fun useAppOverlay(): Boolean {
    if (!AndroidSettings.canDrawOverlays(this)) return false
    if (useAppOverlay && attached) return true
    AppLog.i(TAG, "switching the dot to TYPE_APPLICATION_OVERLAY")
    useAppOverlay = true
    detach()
    params = null
    refresh()
    return attached
  }

  // ---- memory -----------------------------------------------------------------------------------------------
  @Deprecated("Deprecated in Java")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // RUNNING_CRITICAL (15) and above. UI_HIDDEN (20) only means our activity went to the background, which is
    // not memory pressure, so it's skipped.
    @Suppress("DEPRECATION")
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !app.dictation.isBusy) {
      AppLog.i(TAG, "onTrimMemory($level): releasing the model")
      app.scope.launch { app.engine.release() }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onLowMemory() {
    @Suppress("DEPRECATION")
    super.onLowMemory()
    if (!app.dictation.isBusy) app.scope.launch { app.engine.release() }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    checkKeyboard()
  }

  override fun onDestroy() {
    AppLog.i(TAG, "service destroyed")
    if (instance === this) instance = null
    stateJob?.cancel()
    settingsJob?.cancel()
    unloadJob?.cancel()
    menu?.dismiss()
    if (app.dictation.output === this) app.dictation.output = null
    if (app.dictation.state.value is DotState.Recording) app.dictation.cancel()
    detach()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "dot"

    @Volatile var instance: DotAccessibilityService? = null
      private set

    fun isEnabled(context: Context): Boolean {
      val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      val me = "${context.packageName}/${DotAccessibilityService::class.java.name}"
      return enabled.split(':').any { it.equals(me, ignoreCase = true) || it.equals("${context.packageName}/.dot.DotAccessibilityService", ignoreCase = true) }
    }
  }
}
