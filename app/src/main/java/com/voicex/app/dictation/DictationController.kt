package com.voicex.app.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import com.voicex.app.AppLog
import com.voicex.app.BuildConfig
import com.voicex.app.VoiceXApp
import com.voicex.app.audio.AudioCapture
import com.voicex.app.audio.PcmUtils
import com.voicex.app.audio.RecordingCap
import com.voicex.app.engine.CancelledException
import com.voicex.app.engine.NoModelException
import com.voicex.app.history.HistoryItem
import com.voicex.app.model.ModelFiles
import com.voicex.app.model.ModelSpec
import com.voicex.app.output.AutoPaster
import com.voicex.app.output.ClipboardWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Why the dot is orange; [setupStep] is the setup screen a tap opens. */
enum class ErrorKind(val setupStep: String, val message: String) {
  NO_MODEL("model", "No speech model yet"),
  MIC_DENIED("mic", "Microphone permission needed"),
  MIC_BLOCKED("overlay", "Android blocked the microphone"),
  MIC_BUSY("mic", "Microphone is busy"),
  ENGINE("benchmark", "Speech engine failed"),
}

sealed interface DotState {
  data object Idle : DotState

  /** [startedAt] is SystemClock.elapsedRealtime(). */
  data class Recording(val startedAt: Long, val capSeconds: Int) : DotState

  data object Transcribing : DotState

  data class Done(val label: String) : DotState

  data class Info(val label: String) : DotState

  data class Error(val kind: ErrorKind, val detail: String? = null) : DotState
}

/** Implemented by the accessibility service: the only component that can paste into another app. */
interface OutputSink {
  fun paste(text: String): AutoPaster.Outcome

  /** Switch the dot to a TYPE_APPLICATION_OVERLAY window (Risk 1 fallback). True when it now is one. */
  fun useAppOverlay(): Boolean
}

/**
 * Owns one dictation at a time. Runs on the main thread. The microphone is only opened from inside
 * [DictationService] after it is in the foreground (type microphone), as Android 14+ requires.
 */
class DictationController(private val app: VoiceXApp) {
  private val main = Handler(Looper.getMainLooper())
  private val _state = MutableStateFlow<DotState>(DotState.Idle)
  val state: StateFlow<DotState> = _state

  var output: OutputSink? = null

  private var capture: AudioCapture? = null
  private var transcribeJob: Job? = null
  private var resetJob: Job? = null
  private var retriedWithOverlay = false
  private val audio = app.getSystemService(AudioManager::class.java)
  private var focusRequest: AudioFocusRequest? = null

  private val modeListener = AudioManager.OnModeChangedListener { mode ->
    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
      main.post {
        if (_state.value is DotState.Recording) {
          AppLog.i(TAG, "call started: stopping")
          stop()
        }
      }
    }
  }

  val isBusy: Boolean
    get() = _state.value is DotState.Recording || _state.value is DotState.Transcribing

  /** Dot tap. */
  fun toggle() {
    when (_state.value) {
      is DotState.Recording -> stop()
      DotState.Transcribing -> Unit
      else -> start()
    }
  }

  fun start() {
    if (isBusy) return
    resetJob?.cancel()
    if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      AppLog.w(TAG, "dot tapped but the microphone permission is missing")
      _state.value = DotState.Error(ErrorKind.MIC_DENIED)
      return
    }
    if (!ModelFiles.isPresent(app, ModelSpec.of(app.settings.modelId))) {
      AppLog.w(TAG, "dot tapped but no model is downloaded")
      _state.value = DotState.Error(ErrorKind.NO_MODEL)
      return
    }
    if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
      flash(DotState.Info("In a call"))
      return
    }
    _state.value = DotState.Recording(SystemClock.elapsedRealtime(), app.settings.capSeconds)
    // Load (or reload after a memory trim) while the user speaks, so it's ready when they stop.
    app.scope.launch { runCatching { app.engine.ensureLoaded() } }
    try {
      app.startForegroundService(Intent(app, DictationService::class.java).setAction(DictationService.ACTION_START))
    } catch (e: Exception) { // ForegroundServiceStartNotAllowedException and friends
      AppLog.w(TAG, "could not start the microphone service: $e")
      onForegroundRefused(e)
    }
  }

  /** Called by [DictationService] once it is in the foreground: now the microphone may be opened. */
  internal fun onForeground() {
    val rec = _state.value as? DotState.Recording ?: run {
      DictationService.stop(app)
      return
    }
    val cap = RecordingCap(rec.capSeconds)
    val c = AudioCapture(cap) { main.post { if (_state.value is DotState.Recording) stop() } }
    try {
      c.start()
    } catch (e: Exception) {
      AppLog.w(TAG, "microphone failed: $e")
      DictationService.stop(app)
      _state.value = DotState.Error(ErrorKind.MIC_BUSY, e.message)
      return
    }
    capture = c
    retriedWithOverlay = false
    _state.value = rec.copy(startedAt = SystemClock.elapsedRealtime())
    requestFocus()
    audio.addOnModeChangedListener(app.mainExecutor, modeListener)
    // While-in-use check: if Android didn't consider us allowed, the mic delivers silence ("client silenced").
    main.postDelayed({
      if (capture === c && c.audioRecord?.activeRecordingConfiguration?.isClientSilenced == true) {
        AppLog.w(TAG, "the system silenced our recording")
        cancel()
        _state.value = DotState.Error(ErrorKind.MIC_BLOCKED, "silenced")
      }
    }, 800)
  }

  /** Called by [DictationService] when startForeground() was refused. */
  internal fun onForegroundRefused(e: Throwable) {
    app.settings.fgsBlockedSeen = true
    // Risk 1 fallback: with "Display over other apps" granted, a visible TYPE_APPLICATION_OVERLAY window
    // exempts the app from the background microphone restriction. Switch the dot to one and retry once.
    if (!retriedWithOverlay && AndroidSettings.canDrawOverlays(app) && output?.useAppOverlay() == true) {
      retriedWithOverlay = true
      _state.value = DotState.Idle
      main.postDelayed({ start() }, 150)
      return
    }
    _state.value = DotState.Error(ErrorKind.MIC_BLOCKED, e.message)
  }

  /** Dot tap while recording, the notification's Stop, the cap, focus loss or a call. Transcribes. */
  fun stop() {
    val c = capture
    if (_state.value !is DotState.Recording) return
    capture = null
    releaseAudioSession()
    var pcm = c?.stop() ?: FloatArray(0)
    DictationService.stop(app)
    if (BuildConfig.DEBUG) {
      // Emulator testing only (compiled out of release builds): the emulator's microphone hears nothing, so a WAV
      // pushed to files/debug_input.wav stands in for the recording.
      val f = java.io.File(app.filesDir, "debug_input.wav")
      if (f.isFile) pcm = runCatching { com.voicex.app.engine.WavReader.readMono16k(f.inputStream()) }.getOrDefault(pcm)
    }
    _state.value = DotState.Transcribing
    transcribeJob = app.scope.launch { transcribe(pcm) }
  }

  /** Long-press while recording, or the notification's Cancel: discards the audio. */
  fun cancel() {
    val c = capture
    capture = null
    releaseAudioSession()
    c?.cancel()
    DictationService.stop(app)
    if (_state.value is DotState.Transcribing) {
      app.engine.abort()
      transcribeJob?.cancel()
    }
    _state.value = DotState.Idle
  }

  /** Clears an error/info state (e.g. after the user opened setup). */
  fun dismiss() {
    if (!isBusy) _state.value = DotState.Idle
  }

  private suspend fun transcribe(raw: FloatArray) {
    val speech = PcmUtils.trimSilence(raw)
    if (speech == null || speech.size < PcmUtils.SAMPLE_RATE / 4) {
      AppLog.i(TAG, "nothing heard (${PcmUtils.samplesToMs(raw.size)} ms recorded, no speech above the noise floor)")
      flash(DotState.Info("Nothing heard"))
      return
    }
    val t =
      try {
        app.engine.transcribe(speech)
      } catch (e: CancelledException) {
        _state.value = DotState.Idle
        return
      } catch (e: NoModelException) {
        _state.value = DotState.Error(ErrorKind.NO_MODEL)
        return
      } catch (e: Exception) {
        AppLog.e(TAG, "transcription failed", e)
        _state.value = DotState.Error(ErrorKind.ENGINE, e.message)
        return
      }
    if (t.text.isBlank()) {
      AppLog.w(TAG, "no words recognised in ${t.audioMs} ms of audio (${t.kind.label} [${t.device}], ${t.inferenceMs} ms)")
      flash(DotState.Info("Nothing heard")) // don't overwrite the clipboard
      return
    }
    AppLog.ok(TAG, "transcribed ${t.audioMs} ms of audio in ${t.inferenceMs} ms on ${t.kind.label} [${t.device}], ${t.text.length} chars${if (t.truncated) " (truncated)" else ""}")
    val settings = app.settings
    val clipText = if (settings.autoPaste && settings.trailingSpace) t.text + " " else t.text
    ClipboardWriter.copy(app, clipText)
    app.history.add(HistoryItem(t.text, System.currentTimeMillis(), t.audioMs, t.inferenceMs, "${t.kind.label} · ${t.device}"))
    var label = "Copied"
    if (settings.autoPaste) {
      val outcome = output?.paste(clipText) ?: AutoPaster.Outcome.FAILED
      label = if (outcome == AutoPaster.Outcome.FAILED) "Copied (couldn't paste here)" else "Pasted"
      if (outcome == AutoPaster.Outcome.FAILED) AppLog.w(TAG, "auto-paste: the focused field accepted neither PASTE nor SET_TEXT; clipboard only")
      else AppLog.ok(TAG, "auto-paste: ${outcome.name}")
    }
    flash(DotState.Done(label), if (label.length > 10) 1800 else 1100)
  }

  private fun flash(s: DotState, ms: Long = 1100) {
    _state.value = s
    resetJob?.cancel()
    resetJob = app.scope.launch {
      delay(ms)
      if (_state.value == s) _state.value = DotState.Idle
    }
  }

  private fun requestFocus() {
    val req =
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
          if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            AppLog.i(TAG, "audio focus lost ($change): stopping")
            stop()
          }
        }, main)
        .build()
    focusRequest = req
    audio.requestAudioFocus(req)
  }

  private fun releaseAudioSession() {
    focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    focusRequest = null
    runCatching { audio.removeOnModeChangedListener(modeListener) }
  }

  companion object {
    private const val TAG = "dictation"
  }
}
