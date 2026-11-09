package com.voicex.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceX's own log, shown in Settings → Logs. Every entry also goes to logcat.
 *
 *  - Activity: successes (OK) and problems (WARN/ERROR), kept in files/logs/activity.log (newest 10 only).
 *  - Crashes: uncaught Java exceptions (files/logs/crash-*.txt) plus Android's exit records for this app, which
 *    include native crashes (e.g. inside ggml / transcribe.cpp) with their tombstone trace.
 *  - Session: this process's logcat (includes transcribe.cpp's native messages).
 *
 * Privacy: transcript text is never written here; only lengths, timings and engine names.
 */
object AppLog {
  enum class Level(val tag: String) { OK("OK"), INFO("INFO"), WARN("WARN"), ERROR("ERROR") }

  data class Entry(val time: Long, val level: Level, val tag: String, val message: String) {
    fun line(): String = "${FMT.get()!!.format(Date(time))} ${level.tag.padEnd(5)} [$tag] $message"
  }

  private val FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
  /** Logs auto-delete: only the newest [MAX_ENTRIES] activity entries and crash reports are kept. */
  const val MAX_ENTRIES = 10
  private var dir: File? = null
  private val buffer = ArrayDeque<Entry>()
  private val _entries = MutableStateFlow<List<Entry>>(emptyList())
  val entries: StateFlow<List<Entry>> = _entries

  fun init(context: Context) {
    val d = File(context.filesDir, "logs").apply { mkdirs() }
    dir = d
    // reload the previous runs' activity
    runCatching {
      File(d, "activity.log").takeIf { it.exists() }?.readLines()?.takeLast(MAX_ENTRIES)?.forEach { l -> parse(l)?.let { buffer.addLast(it) } }
    }
    _entries.value = buffer.toList()
    installCrashHandler(context)
    i("app", "VoiceX ${BuildConfig.VERSION_NAME} started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SOC_MODEL}")
  }

  fun ok(tag: String, msg: String) = add(Level.OK, tag, msg, null)

  fun i(tag: String, msg: String) = add(Level.INFO, tag, msg, null)

  fun w(tag: String, msg: String, t: Throwable? = null) = add(Level.WARN, tag, msg, t)

  fun e(tag: String, msg: String, t: Throwable? = null) = add(Level.ERROR, tag, msg, t)

  @Synchronized
  private fun add(level: Level, tag: String, msg: String, t: Throwable?) {
    val full = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg
    when (level) {
      Level.ERROR -> Log.e("VoiceX-$tag", full, t)
      Level.WARN -> Log.w("VoiceX-$tag", full, t)
      else -> Log.i("VoiceX-$tag", full)
    }
    val e = Entry(System.currentTimeMillis(), level, tag, full.replace('\n', ' '))
    buffer.addLast(e)
    while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
    _entries.value = buffer.toList()
    val f = dir?.let { File(it, "activity.log") } ?: return
    runCatching { f.writeText(buffer.joinToString("\n", postfix = "\n") { it.line() }) }
  }

  private fun parse(line: String): Entry? =
    runCatching {
      val time = FMT.get()!!.parse(line.substring(0, 23))!!.time
      val level = Level.valueOf(line.substring(24, 29).trim())
      val tagEnd = line.indexOf(']', 30)
      Entry(time, level, line.substring(31, tagEnd), line.substring(tagEnd + 2))
    }.getOrNull()

  @Synchronized
  fun clearActivity() {
    buffer.clear()
    _entries.value = emptyList()
    dir?.let { File(it, "activity.log").delete() }
  }

  // ---- crashes --------------------------------------------------------------------------------------------------
  private fun installCrashHandler(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
      runCatching {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val text = buildString {
          appendLine("VoiceX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crashed")
          appendLine("Time: ${FMT.get()!!.format(Date())}")
          appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
          appendLine("Thread: ${thread.name}")
          appendLine()
          append(sw)
        }
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        File(d, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        pruneCrashFiles(d)
      }
      previous?.uncaughtException(thread, ex)
    }
  }

  data class Crash(val time: Long, val title: String, val body: String)

  /** Deletes all but the newest [MAX_ENTRIES] saved crash reports. */
  private fun pruneCrashFiles(d: File) {
    d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: it.lastModified() }
      ?.drop(MAX_ENTRIES)?.forEach { it.delete() }
  }

  /** Saved Java crashes plus Android's records of how earlier VoiceX processes ended (native crashes, ANRs). */
  fun crashes(context: Context): List<Crash> {
    val out = mutableListOf<Crash>()
    dir?.let { pruneCrashFiles(it) }
    dir?.listFiles { f -> f.name.startsWith("crash-") }?.forEach { f ->
      out += Crash(f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified(), "Java crash", f.readText())
    }
    runCatching {
      val am = context.getSystemService(ActivityManager::class.java)
      am.getHistoricalProcessExitReasons(context.packageName, 0, 20).forEach { r ->
        val kind =
          when (r.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java crash (system record)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: low memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed: excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
            else -> null
          } ?: return@forEach
        val trace = runCatching { r.traceInputStream?.use { s -> s.readBytes().decodeToString().take(60_000) } }.getOrNull()
        out += Crash(r.timestamp, kind, buildString {
          appendLine("$kind · ${r.description ?: ""}")
          appendLine("Process: ${r.processName} (pid ${r.pid}), importance ${r.importance}, PSS ${r.pss / 1024} MB")
          appendLine()
          append(trace ?: "(no trace recorded)")
        })
      }
    }
    return out.sortedByDescending { it.time }.take(MAX_ENTRIES)
  }

  fun clearCrashes() {
    dir?.listFiles { f -> f.name.startsWith("crash-") }?.forEach { it.delete() }
  }

  /** This process's logcat (apps may read their own log lines). */
  fun sessionLog(): String =
    runCatching {
      val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
      p.inputStream.bufferedReader().readText().takeLast(300_000)
    }.getOrElse { "Could not read logcat: ${it.message}" }

  fun format(time: Long): String = FMT.get()!!.format(Date(time))
}
