package com.voicex.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicex.app.BackendPref
import com.voicex.app.Settings
import com.voicex.app.VoiceXApp
import com.voicex.app.audio.AudioCapture
import com.voicex.app.audio.PcmUtils
import com.voicex.app.audio.RecordingCap
import com.voicex.app.engine.EngineKind
import com.voicex.app.engine.EngineState
import com.voicex.app.engine.Transcript
import com.voicex.app.model.Lang
import androidx.compose.foundation.selection.selectable
import com.voicex.app.model.ModelFiles
import com.voicex.app.model.ModelSpec
import com.voicex.app.model.WhisperLanguages
import com.voicex.app.output.ClipboardWriter
import com.voicex.app.ui.theme.Brand
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Home ---------------------------------------------------------------------------------------------------------
@Composable
fun HomeScreen(app: VoiceXApp, go: (Screen) -> Unit) {
  val ctx = LocalContext.current
  val tick = rememberResumeTick()
  val engine by app.engine.state.collectAsState()
  val history by app.history.items.collectAsState()
  ScreenScaffold("VoiceX", onBack = null) {
    BrandHeader("VoiceX", "Tap the dot above your keyboard to dictate")
    androidx.compose.runtime.key(tick) {
      SectionCard("Status") {
        StatusLine(Checks.accessibility(ctx), if (Checks.accessibility(ctx)) "Dictation dot is on" else "Dictation dot is off (accessibility service)")
        StatusLine(Checks.mic(ctx), if (Checks.mic(ctx)) "Microphone allowed" else "Microphone not allowed")
        val spec = ModelSpec.active(app.settings)
        val lang = app.settings.language
        val s = app.settings
        StatusLine(true, "Language: " + if (lang == Lang.EN) "English" else "${s.langNative(lang)} (${s.langLabel(lang)})")
        StatusLine(Checks.model(app), if (Checks.model(app)) "Model: ${spec.label}" else "${s.langLabel(lang)} model not downloaded")
        val e = engine
        StatusLine(e !is EngineState.Failed, engineText(e, app))
        if (e is EngineState.Ready && e.note != null) Muted(e.note)
        if (!(Checks.accessibility(ctx) && Checks.mic(ctx) && Checks.model(app))) Button(onClick = { go(Screen.Setup(null)) }) { Text("Finish setup") }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(onClick = { go(Screen.Test) }) { Text("Test transcription") }
      FilledTonalButton(onClick = { go(Screen.Settings) }) { Text("Settings") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { go(Screen.History) }) { Text("History") }
      OutlinedButton(onClick = { go(Screen.Setup(null)) }) { Text("Setup guide") }
    }
    history.firstOrNull()?.let { SectionCard("Last dictation") { Text(it.text) } }
  }
}

private fun engineText(e: EngineState, app: VoiceXApp): String =
  when (e) {
    EngineState.Unloaded -> app.engine.lastBenchmark()?.let { "Engine: ${it.winner.label} (loads on first use)" } ?: "Engine: not loaded yet"
    is EngineState.Loading -> e.what
    is EngineState.Ready -> "Engine: ${e.kind.label} · ${e.device}"
    is EngineState.Failed -> e.message
  }

// ---- Settings -----------------------------------------------------------------------------------------------------
@Composable
fun SettingsScreen(app: VoiceXApp, go: (Screen) -> Unit) {
  val s = app.settings
  val scope = rememberCoroutineScope()
  ScreenScaffold("Settings", onBack = { go(Screen.Home) }) {
    SectionCard("Recording") {
      var cap by remember { mutableFloatStateOf(s.capSeconds.toFloat()) }
      Text("Safety cap: ${RecordingCap.formatElapsed(cap.toLong() * 1000)} (auto-stops and transcribes)")
      Slider(value = cap, onValueChange = { cap = (it / 30).toInt() * 30f }, onValueChangeFinished = { s.capSeconds = cap.toInt() }, valueRange = Settings.CAP_MIN.toFloat()..Settings.CAP_MAX.toFloat(), steps = (Settings.CAP_MAX - Settings.CAP_MIN) / 30 - 1)
      Muted("The dot pulses amber 10 seconds before the cap.")
    }
    SectionCard("Output") {
      Toggle("Auto-paste into the text field", s.autoPaste) { s.autoPaste = it }
      Muted("After copying, also insert the text where you're typing. Falls back to clipboard only.")
      Toggle("Add a trailing space", s.trailingSpace) { s.trailingSpace = it }
      Toggle("Keep history (last ${com.voicex.app.history.HistoryStore.MAX}, on this phone only)", s.historyEnabled) {
        s.historyEnabled = it
        if (!it) app.history.clear()
      }
    }
    SectionCard("Language") {
      var lang by remember { mutableStateOf(s.language) }
      var whisperLang by remember { mutableStateOf(s.whisperLanguage) }
      var picking by remember { mutableStateOf(false) }
      Lang.entries.forEach { l ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().selectable(selected = lang == l, onClick = { lang = l; app.switchLanguage(l) })) {
          RadioButton(selected = lang == l, onClick = { lang = l; app.switchLanguage(l) })
          Text(if (l == Lang.EN) "English" else WhisperLanguages.displayName(whisperLang))
        }
      }
      Muted("Switch quickly: long-press the dot and pick English or ${WhisperLanguages.nativeName(whisperLang)}. The menu and the language badge (EN / ${WhisperLanguages.badge(whisperLang)}) appear once both models are downloaded; with one model, long-press just hides the dot. Switching loads the other model (a second or two).")
      HorizontalDivider()
      Text("Second language", fontWeight = FontWeight.SemiBold)
      Muted("English uses Parakeet. The second language uses Whisper, which knows about 100 languages; pick the one you speak. Accuracy varies by language.")
      OutlinedButton(onClick = { picking = true }) { Text(WhisperLanguages.displayName(whisperLang)) }
      if (picking) {
        WhisperLanguageDialog(whisperLang, onDismiss = { picking = false }) { code ->
          picking = false
          whisperLang = code
          s.whisperLanguage = code
          app.engine.refreshBenchmark()
          com.voicex.app.AppLog.i("app", "second language: ${WhisperLanguages.englishName(code)}")
        }
      }
      HorizontalDivider()
      Text("Whisper model", fontWeight = FontWeight.SemiBold)
      Muted("OpenAI Whisper large-v3-turbo (q5_0, MIT licence), 574 MB. One model for every second language.")
      var urRefresh by remember { mutableStateOf(0) }
      androidx.compose.runtime.key(urRefresh) {
        DownloadControls(app, ModelSpec.WHISPER_TURBO_Q5)
        if (ModelFiles.isPresent(app, ModelSpec.WHISPER_TURBO_Q5)) {
          TextButton(onClick = {
            scope.launch {
              if (s.language == Lang.WHISPER) app.engine.release()
              app.downloader.delete(ModelSpec.WHISPER_TURBO_Q5)
              app.downloader.reset()
              app.switchLanguage(Lang.EN)
              urRefresh++
            }
          }) { Text("Delete the Whisper model (574 MB)") }
        }
      }
    }
    SectionCard("English model") {
      ModelPicker(app)
      DownloadControls(app, ModelSpec.forLang(Lang.EN, s))
      var refresh by remember { mutableStateOf(0) }
      androidx.compose.runtime.key(refresh) {
        ModelSpec.english.filter { it.id != s.modelId && ModelFiles.isPresent(app, it) }.forEach { other ->
          TextButton(onClick = { app.downloader.delete(other); refresh++ }) { Text("Delete ${other.label} to free space") }
        }
        val cur = ModelSpec.forLang(Lang.EN, s)
        if (ModelFiles.isPresent(app, cur)) {
          TextButton(onClick = {
            scope.launch {
              if (s.language == Lang.EN) app.engine.release()
              app.downloader.delete(cur)
              app.downloader.reset()
              refresh++
            }
          }) { Text("Delete and re-download ${cur.label}") }
        }
      }
    }
    SectionCard("Speech engine") {
      var engines by remember { mutableStateOf<List<EngineKind>>(emptyList()) }
      LaunchedEffect(Unit) { engines = runCatching { app.engine.availableEngines() }.getOrDefault(listOf(EngineKind.CPU)) }
      var pref by remember { mutableStateOf(s.backend) }
      Muted("Available on this phone: " + engines.joinToString { it.label })
      BackendPref.entries.forEach { b ->
        val available = b == BackendPref.AUTO || engines.any { it.matches(b) }
        if (available) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = pref == b, onClick = { pref = b; s.backend = b })
            Text(b.label)
          }
        }
      }
      HorizontalDivider()
      BenchmarkPanel(app, autoStart = false)
      HorizontalDivider()
      var threads by remember { mutableFloatStateOf(s.threads.toFloat()) }
      Text("CPU threads: ${threads.toInt()} (pinned to the fastest cores)")
      Slider(value = threads, onValueChange = { threads = it }, onValueChangeFinished = { s.threads = threads.toInt() }, valueRange = 1f..8f, steps = 6)
      Toggle("Keep the model loaded", s.keepLoaded) { s.keepLoaded = it }
      Muted("Off: load when a keyboard opens, unload 60 s after it closes (saves memory, first tap is slower).")
    }
    SectionCard("Dot") {
      var size by remember { mutableFloatStateOf(s.dotSizeDp.toFloat()) }
      Text("Size: ${size.toInt()} dp")
      Slider(value = size, onValueChange = { size = it }, onValueChangeFinished = { s.dotSizeDp = size.toInt() }, valueRange = 36f..72f, steps = 8)
      var alpha by remember { mutableFloatStateOf(s.dotOpacity) }
      Text("Idle opacity: ${(alpha * 100).toInt()} %")
      Slider(value = alpha, onValueChange = { alpha = it }, onValueChangeFinished = { s.dotOpacity = alpha }, valueRange = 0.3f..1f)
      Toggle("Haptics", s.haptics) { s.haptics = it }
      TextButton(onClick = { s.resetDotOffsets() }) { Text("Reset dot position") }
    }
    SectionCard("Diagnostics") {
      Muted("Successes, errors and crashes, stored only on this phone. Transcript text is never logged.")
      FilledTonalButton(onClick = { go(Screen.Logs) }) { Text("Logs (activity & crashes)") }
    }
    SectionCard("About") {
      Muted("VoiceX ${com.voicex.app.BuildConfig.VERSION_NAME} · on-device speech recognition via transcribe.cpp (MIT): NVIDIA Parakeet Unified EN 0.6B for English, OpenAI Whisper large-v3-turbo (MIT) for Urdu. Urdu benchmark clips from Google FLEURS (CC BY 4.0).")
    }
  }
}

@Composable
private fun Toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
  var v by remember { mutableStateOf(initial) }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(label, modifier = Modifier.weight(1f))
    Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
  }
}

// ---- History ------------------------------------------------------------------------------------------------------
@Composable
fun HistoryScreen(app: VoiceXApp, go: (Screen) -> Unit) {
  val ctx = LocalContext.current
  val items by app.history.items.collectAsState()
  ScreenScaffold("History", onBack = { go(Screen.Home) }) {
    if (!app.settings.historyEnabled) Muted("History is off (Settings → Output).")
    if (items.isEmpty()) Muted("Nothing yet. Your last ${com.voicex.app.history.HistoryStore.MAX} dictations appear here, stored only on this phone.")
    items.forEach { item ->
      SectionCard {
        Text(item.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Muted(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp)) + "  ·  ${item.audioMs / 1000.0}s audio, ${item.inferenceMs} ms")
          androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
          IconButton(onClick = { ClipboardWriter.copy(ctx, item.text) }) { Icon(Icons.Filled.ContentCopy, "Copy") }
        }
      }
    }
    if (items.isNotEmpty()) OutlinedButton(onClick = { app.history.clear() }) { Text("Clear all") }
  }
}

// ---- Test transcription -------------------------------------------------------------------------------------------
@Composable
fun TestScreen(app: VoiceXApp, go: (Screen) -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var capture by remember { mutableStateOf<AudioCapture?>(null) }
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<Transcript?>(null) }
  var rawMs by remember { mutableLongStateOf(0L) }
  var error by remember { mutableStateOf<String?>(null) }
  var elapsed by remember { mutableLongStateOf(0L) }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  DisposableEffect(Unit) { onDispose { capture?.cancel() } }
  LaunchedEffect(capture) {
    val start = System.currentTimeMillis()
    while (capture != null) {
      elapsed = System.currentTimeMillis() - start
      delay(100)
    }
  }

  fun stopAndRun() {
    val c = capture ?: return
    capture = null
    val pcm = c.stop()
    rawMs = PcmUtils.samplesToMs(pcm.size)
    val speech = PcmUtils.trimSilence(pcm)
    if (speech == null) {
      error = "Nothing heard"
      return
    }
    busy = true
    scope.launch {
      try {
        result = app.engine.transcribe(speech)
      } catch (e: Exception) {
        error = e.message ?: e.toString()
      } finally {
        busy = false
      }
    }
  }

  ScreenScaffold("Test transcription", onBack = { go(Screen.Home) }) {
    Muted("Records here in the app (the dot isn't involved) and shows how long transcription takes.")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      val recording = capture != null
      IconButton(
        onClick = {
          if (recording) stopAndRun()
          else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
          else if (!busy) {
            error = null
            result = null
            val c = AudioCapture(RecordingCap(app.settings.capSeconds)) { scope.launch { stopAndRun() } }
            try {
              c.start()
              capture = c
            } catch (e: Exception) {
              error = e.message
            }
          }
        },
        modifier = Modifier.size(96.dp).clip(CircleShape).background(if (recording) Brand.Recording else Brand.Violet),
      ) {
        Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (recording) "Stop" else "Record", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(44.dp))
      }
    }
    if (capture != null) Text("Recording… ${RecordingCap.formatElapsed(elapsed)}", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Medium)
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { r ->
      SectionCard("Transcript") { Text(r.text.ifEmpty { "(nothing recognised)" }) }
      SectionCard("Timing") {
        Text("Audio recorded: %.2f s (%.2f s after trimming silence)".format(rawMs / 1000.0, r.audioMs / 1000.0))
        Text("Inference: ${r.inferenceMs} ms  (%.1fx real time)".format(if (r.inferenceMs > 0) r.audioMs.toDouble() / r.inferenceMs else 0.0))
        Text("Backend: ${r.kind.label} · ${r.device}")
        Muted("mel %.0f ms · encoder %.0f ms · decoder %.0f ms".format(r.timings[1], r.timings[2], r.timings[3]))
      }
    }
  }
}

/** Picks the Whisper language: a searchable list, native name first. */
@Composable
private fun WhisperLanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  val all = remember { WhisperLanguages.sorted }
  val shown = all.filter { query.isBlank() || WhisperLanguages.displayName(it).contains(query.trim(), ignoreCase = true) || it.equals(query.trim(), ignoreCase = true) }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    title = { Text("Second language") },
    text = {
      Column {
        androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
          items(shown.size) { i ->
            val code = shown[i]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().selectable(selected = code == current, onClick = { onPick(code) }).padding(vertical = 2.dp)) {
              RadioButton(selected = code == current, onClick = { onPick(code) })
              Text(WhisperLanguages.displayName(code), modifier = Modifier.weight(1f))
              Muted(WhisperLanguages.badge(code))
            }
          }
        }
      }
    },
  )
}
