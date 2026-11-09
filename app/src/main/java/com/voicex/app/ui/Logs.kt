package com.voicex.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicex.app.AppLog
import com.voicex.app.VoiceXApp
import com.voicex.app.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LogsScreen(app: VoiceXApp, go: (Screen) -> Unit) {
  val ctx = LocalContext.current
  var tab by remember { mutableIntStateOf(0) }
  ScreenScaffold("Logs", onBack = { go(Screen.Settings) }) {
    PrimaryTabRow(selectedTabIndex = tab) {
      listOf("Activity", "Crashes", "Session").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
    }
    when (tab) {
      0 -> ActivityTab(ctx)
      1 -> CrashesTab(ctx)
      else -> SessionTab(ctx)
    }
  }
}

@Composable
private fun ActivityTab(ctx: Context) {
  val entries by AppLog.entries.collectAsState()
  var errorsOnly by remember { mutableStateOf(false) }
  val shown = entries.asReversed().filter { !errorsOnly || it.level == AppLog.Level.WARN || it.level == AppLog.Level.ERROR }
  Muted("Successes (OK) and problems (WARN / ERROR), newest first. Only the newest ${AppLog.MAX_ENTRIES} are kept; older ones are deleted automatically.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { copyOrShare(ctx, shown.asReversed().joinToString("\n") { it.line() }, share = false) }, enabled = shown.isNotEmpty()) { Text("Copy all") }
    OutlinedButton(onClick = { copyOrShare(ctx, shown.asReversed().joinToString("\n") { it.line() }, share = true) }, enabled = shown.isNotEmpty()) { Text("Share") }
    OutlinedButton(onClick = { AppLog.clearActivity() }) { Text("Clear") }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { errorsOnly = !errorsOnly }) { Text(if (errorsOnly) "Show all" else "Problems only") }
  }
  Muted("Tap any line to copy just that line.")
  if (shown.isEmpty()) Muted("Nothing logged yet.")
  shown.forEach { e ->
    val color =
      when (e.level) {
        AppLog.Level.OK -> Brand.Success
        AppLog.Level.WARN -> Brand.Warning
        AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
        AppLog.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
      }
    Column(Modifier.fillMaxWidth().clickable { copyOrShare(ctx, e.line(), share = false) }.padding(vertical = 2.dp)) {
      Row {
        Text(e.level.tag, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("  ${AppLog.format(e.time).substring(5)}  ·  ${e.tag}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text(e.message, fontSize = 13.sp)
    }
  }
}

@Composable
private fun CrashesTab(ctx: Context) {
  var refresh by remember { mutableIntStateOf(0) }
  var crashes by remember { mutableStateOf<List<AppLog.Crash>?>(null) }
  LaunchedEffect(refresh) { crashes = withContext(Dispatchers.IO) { AppLog.crashes(ctx) } }
  Muted("Java crashes caught by VoiceX, plus Android's records of how earlier runs ended (native crashes in the speech engine, freezes, low-memory kills). The newest ${AppLog.MAX_ENTRIES} are kept. Tap one to expand.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { copyOrShare(ctx, crashes.orEmpty().joinToString("\n\n========\n\n") { it.body }, share = false) }, enabled = !crashes.isNullOrEmpty()) { Text("Copy all") }
    OutlinedButton(onClick = { copyOrShare(ctx, crashes.orEmpty().joinToString("\n\n========\n\n") { it.body }, share = true) }, enabled = !crashes.isNullOrEmpty()) { Text("Share") }
    OutlinedButton(onClick = { AppLog.clearCrashes(); refresh++ }) { Text("Clear saved") }
  }
  val list = crashes
  when {
    list == null -> Muted("Loading…")
    list.isEmpty() -> StatusLine(true, "No crashes recorded")
    else ->
      list.forEach { c ->
        var open by remember(c.time, c.title) { mutableStateOf(false) }
        SectionCard {
          Text(c.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { open = !open })
          Muted(AppLog.format(c.time))
          if (open) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { Text(c.body.take(20_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { copyOrShare(ctx, c.body, share = false) }) { Text("Copy") }
              OutlinedButton(onClick = { copyOrShare(ctx, c.body, share = true) }) { Text("Share") }
            }
          } else {
            Muted(c.body.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
            OutlinedButton(onClick = { copyOrShare(ctx, c.body, share = false) }) { Text("Copy") }
          }
        }
      }
  }
}

@Composable
private fun SessionTab(ctx: Context) {
  var refresh by remember { mutableIntStateOf(0) }
  var text by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(refresh) { text = withContext(Dispatchers.IO) { AppLog.sessionLog() } }
  Muted("Everything this run of VoiceX wrote to the system log, including the speech engine's own messages.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { copyOrShare(ctx, text.orEmpty(), share = false) }, enabled = !text.isNullOrEmpty()) { Text("Copy all") }
    OutlinedButton(onClick = { copyOrShare(ctx, text.orEmpty(), share = true) }, enabled = !text.isNullOrEmpty()) { Text("Share") }
    OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
  }
  val t = text
  if (t == null) Muted("Loading…")
  else Row(Modifier.horizontalScroll(rememberScrollState())) { Text(t.takeLast(40_000), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
}

private fun copyOrShare(ctx: Context, text: String, share: Boolean) {
  if (share) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "VoiceX log").putExtra(Intent.EXTRA_TEXT, text.takeLast(500_000))
    ctx.startActivity(Intent.createChooser(send, "Share VoiceX log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } else {
    // a clip travels over binder (~1 MB limit): keep the newest part of very long logs
    val clip = if (text.length > 200_000) "…(older lines cut)\n" + text.takeLast(200_000) else text
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoiceX log", clip))
    Toast.makeText(ctx, "Copied ${clip.lineSequence().count()} lines", Toast.LENGTH_SHORT).show()
  }
}
