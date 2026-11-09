package com.voicex.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicex.app.VoiceXApp
import com.voicex.app.dot.DotAccessibilityService
import com.voicex.app.engine.BenchmarkResult
import com.voicex.app.model.DownloadState
import com.voicex.app.model.ModelDownloadService
import com.voicex.app.model.ModelFiles
import com.voicex.app.model.ModelSpec

/** Permission / setup state checks shared by the wizard and the home screen. */
object Checks {
  fun mic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  fun notifications(c: Context) = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun accessibility(c: Context) = DotAccessibilityService.isEnabled(c)

  fun overlay(c: Context) = AndroidSettings.canDrawOverlays(c)

  fun model(app: VoiceXApp) = ModelFiles.isPresent(app, ModelSpec.of(app.settings.modelId))
}

private val STEPS = listOf("welcome", "mic", "notifications", "accessibility", "overlay", "model", "benchmark", "done")

@Composable
fun SetupWizard(app: VoiceXApp, startStep: String?, onDone: () -> Unit, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val tick = rememberResumeTick()
  var index by remember { mutableStateOf(STEPS.indexOf(startStep).takeIf { it >= 0 } ?: 0) }
  val step = STEPS[index]
  val next = { index = (index + 1).coerceAtMost(STEPS.lastIndex) }

  ScreenScaffold(title = "Set up VoiceX  ·  ${index + 1}/${STEPS.size}", onBack = if (app.settings.setupDone) onBack else null) {
    BrandHeader("VoiceX", "Dictate into any app, fully on-device")
    LinearProgressIndicator(progress = { (index + 1f) / STEPS.size }, modifier = Modifier.fillMaxWidth())
    // `tick` forces a re-read of every permission when we come back from a Settings screen
    key(tick) {
      when (step) {
        "welcome" -> WelcomeStep()
        "mic" -> MicStep(ctx)
        "notifications" -> NotificationStep(ctx)
        "accessibility" -> AccessibilityStep(ctx)
        "overlay" -> OverlayStep(ctx, app)
        "model" -> ModelStep(app)
        "benchmark" -> BenchmarkStep(app)
        "done" -> DoneStep()
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = { index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) { Text("Back") }
      if (step == "done") Button(onClick = onDone) { Text("Finish") }
      else Button(onClick = next) { Text(if (step == "overlay") "Next (or skip)" else "Next") }
    }
  }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun WelcomeStep() {
  SectionCard("How it works") {
    Muted("A small dot appears just above your keyboard (Samsung Keyboard, Gboard, any keyboard). Tap it, speak, tap again: your words are transcribed on this phone and copied to the clipboard.")
  }
  SectionCard("Privacy") {
    Text("• Speech is transcribed entirely on this phone by NVIDIA Parakeet. Audio and text never leave it.")
    Text("• The accessibility service only notices when a keyboard window is open and where it is. It does not read, store or send anything on your screen.")
    Text("• The one exception is the optional Auto-paste (off by default), which inserts your dictation into the field you're typing in.")
    Text("• The internet is used once, to download the speech model from Hugging Face.")
  }
}

@Composable
private fun MicStep(ctx: Context) {
  var granted by remember { mutableStateOf(Checks.mic(ctx)) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  SectionCard("Microphone") {
    Muted("VoiceX listens only while the dot is red, and shows a notification the whole time.")
    StatusLine(granted, if (granted) "Microphone allowed" else "Not allowed yet")
    if (!granted) Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
    if (!granted) TextButton(onClick = { openAppInfo(ctx) }) { Text("Denied before? Open App info → Permissions") }
  }
}

@Composable
private fun NotificationStep(ctx: Context) {
  var granted by remember { mutableStateOf(Checks.notifications(ctx)) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  SectionCard("Notifications") {
    Muted("Android requires a visible notification while an app uses the microphone. VoiceX also shows model download progress.")
    StatusLine(granted, if (granted) "Notifications allowed" else "Not allowed yet")
    if (!granted && Build.VERSION.SDK_INT >= 33) Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
  }
}

@Composable
private fun AccessibilityStep(ctx: Context) {
  val on = Checks.accessibility(ctx)
  SectionCard("Turn on the dictation dot") {
    StatusLine(on, if (on) "VoiceX dictation dot is on" else "Not turned on yet")
    if (!on) {
      Text("1. Tap Open accessibility settings.", fontWeight = FontWeight.Medium)
      Muted("Samsung: Installed apps (or Installed services) → VoiceX dictation dot. Pixel/others: Downloaded apps → VoiceX dictation dot.")
      Text("2. Turn it on and confirm.", fontWeight = FontWeight.Medium)
      Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open accessibility settings") }
    }
  }
  if (!on) {
    SectionCard("Greyed out, or \"Restricted setting\"?") {
      Muted("Android blocks accessibility for apps installed from an APK until you allow it once:")
      Text("1. Tap Open App info below.")
      Text("2. Tap the ⋮ menu (top right).")
      Text("3. Tap Allow restricted settings and confirm with your PIN or fingerprint.")
      Text("4. Come back here and repeat \"Open accessibility settings\".")
      Muted("The ⋮ item only appears after you have tried to turn the service on once.")
      OutlinedButton(onClick = { openAppInfo(ctx) }) { Text("Open App info") }
    }
  }
  SectionCard("What this service can see") {
    Muted("Only whether a keyboard window is open and its position on screen. It does not read, store or send on-screen text. Auto-paste (off by default) is the only feature that touches a text field, and only the one you're typing in.")
  }
}

@Composable
private fun OverlayStep(ctx: Context, app: VoiceXApp) {
  val granted = Checks.overlay(ctx)
  SectionCard("Optional: Display over other apps") {
    Muted("Most phones don't need this. Some versions of Android refuse to start the microphone from an accessibility overlay. With this permission the dot switches to a normal overlay window, which Android does allow.")
    if (app.settings.fgsBlockedSeen) StatusLine(false, "This phone refused the microphone from the accessibility overlay: please allow this.")
    StatusLine(granted, if (granted) "Allowed" else "Not allowed (optional)")
    if (!granted) {
      OutlinedButton(onClick = {
        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }) { Text("Allow display over other apps") }
    }
  }
}

@Composable
fun ModelPicker(app: VoiceXApp) {
  var model by remember { mutableStateOf(app.settings.modelId) }
  ModelSpec.entries.forEach { spec ->
    Row(
      Modifier.fillMaxWidth().selectable(selected = model == spec.id, onClick = { model = spec.id; app.settings.modelId = spec.id; app.engine.refreshBenchmark() }).padding(vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(selected = model == spec.id, onClick = { model = spec.id; app.settings.modelId = spec.id; app.engine.refreshBenchmark() })
      Column {
        Text(spec.label)
        Muted(spec.blurb + if (ModelFiles.isPresent(app, spec)) "  ·  downloaded" else "")
      }
    }
  }
}

@Composable
fun DownloadControls(app: VoiceXApp) {
  val ctx = LocalContext.current
  val st by app.downloader.state.collectAsState()
  var wifiOnly by remember { mutableStateOf(app.settings.wifiOnly) }
  val spec = ModelSpec.of(app.settings.modelId)
  val present = ModelFiles.isPresent(app, spec) || (st as? DownloadState.Done)?.spec == spec
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Download on Wi-Fi only", modifier = Modifier.weight(1f))
    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; app.settings.wifiOnly = it })
  }
  when (val s = st) {
    is DownloadState.Downloading -> {
      LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
      Muted("%.0f of %.0f MB  ·  %.1f MB/s".format(s.bytes / 1e6, s.total / 1e6, s.bytesPerSec / 1e6))
      OutlinedButton(onClick = { ctx.startService(Intent(ctx, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_CANCEL)) }) { Text("Pause") }
    }
    is DownloadState.Verifying -> {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      Muted("Verifying SHA-256…")
    }
    DownloadState.WaitingForWifi -> Muted("Waiting for Wi-Fi… (turn off \"Wi-Fi only\" to use mobile data)")
    is DownloadState.Failed -> {
      Text(s.message, color = MaterialTheme.colorScheme.error)
      Button(onClick = { ModelDownloadService.start(ctx, spec) }) { Text("Retry (resumes)") }
    }
    else -> {
      if (present) StatusLine(true, "${spec.label} downloaded and verified")
      else {
        val partial = ModelFiles.partFile(app, spec).length()
        Button(onClick = { ModelDownloadService.start(ctx, spec) }) { Text(if (partial > 0) "Resume download (%.0f MB done)".format(partial / 1e6) else "Download ${spec.label}") }
      }
    }
  }
}

@Composable
private fun ModelStep(app: VoiceXApp) {
  SectionCard("Speech model") {
    Muted("NVIDIA Parakeet Unified EN 0.6B, downloaded once from Hugging Face into VoiceX's private storage and checked against its SHA-256.")
    ModelPicker(app)
    DownloadControls(app)
  }
}

@Composable
fun BenchmarkPanel(app: VoiceXApp, autoStart: Boolean) {
  val running by app.engine.benchRunning.collectAsState()
  val lines by app.engine.benchLines.collectAsState()
  val error by app.engine.benchError.collectAsState()
  val result by app.engine.benchResult.collectAsState()
  val present = Checks.model(app)
  // the engine guards against a second concurrent run, so recomposition can't start two
  LaunchedEffect(Unit) { if (autoStart && present && result == null) app.engine.startBenchmark(app.scope) }
  if (!present) Muted("Download the model first.")
  if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
  if (running) lines.takeLast(6).forEach { Muted(it) }
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  result?.let { BenchmarkTable(it) }
  OutlinedButton(onClick = { app.engine.startBenchmark(app.scope) }, enabled = present && !running) { Text(if (result == null) "Run benchmark" else "Re-run benchmark") }
}

@Composable
fun BenchmarkTable(r: BenchmarkResult) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Muted("5.4 s clip · reference (CPU): \"${r.reference}\"")
    r.entries.forEach { e ->
      val txt = when {
        e.correct -> "${e.kind.label}: ${e.ms} ms" + if (e.kind == r.winner) "  ← chosen" else ""
        else -> "${e.kind.label}: ${e.error ?: "failed"}"
      }
      StatusLine(e.correct, txt)
      // accelerators are only used for recordings as long as they were verified on (longer ones go to the CPU)
      if (e.correct && e.kind != com.voicex.app.engine.EngineKind.CPU) {
        Muted(
          if (e.maxOkMs == com.voicex.app.engine.AccelPolicy.UNLIMITED) "Correct on 11 s and 33 s clips: any length" else "Used for recordings up to ${e.maxOkMs / 1000} s; longer ones run on CPU",
          modifier = Modifier.padding(start = 28.dp),
        )
      }
    }
  }
}

@Composable
private fun BenchmarkStep(app: VoiceXApp) {
  SectionCard("Pick the fastest engine") {
    Muted("VoiceX transcribes a built-in 5-second clip on every engine this phone has (NPU, GPU, CPU), checks each transcript against the CPU's, and uses the fastest correct one. Takes under a minute.")
    BenchmarkPanel(app, autoStart = true)
  }
}

@Composable
private fun DoneStep() {
  SectionCard("You're set") {
    Text("Open any app and tap a text field. The dot appears just above the keyboard.")
    Text("• Tap: start. Tap again: stop and copy.")
    Text("• Long-press while recording: cancel.")
    Text("• Long-press when idle: hide until the keyboard opens again.")
    Text("• Drag: move it. VoiceX remembers where.")
    Muted("Orange dot = something needs attention: tap it.")
  }
}

fun openAppInfo(ctx: Context) {
  ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
