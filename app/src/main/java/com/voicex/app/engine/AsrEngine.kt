package com.voicex.app.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.system.Os
import com.voicex.app.AppLog
import com.voicex.app.BackendPref
import com.voicex.app.Settings
import com.voicex.app.audio.PcmUtils
import com.voicex.app.model.Lang
import com.voicex.app.model.ModelFiles
import com.voicex.app.model.ModelSpec
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The kinds of compute device VoiceX knows how to use, in the order the spec asks to try them. */
enum class EngineKind(val label: String) {
  NPU("NPU (Hexagon)"),
  GPU_OPENCL("GPU (OpenCL)"),
  GPU_VULKAN("GPU (Vulkan)"),
  CPU("CPU");

  fun matches(pref: BackendPref) =
    when (pref) {
      BackendPref.NPU -> this == NPU
      BackendPref.GPU_OPENCL -> this == GPU_OPENCL
      BackendPref.GPU_VULKAN -> this == GPU_VULKAN
      BackendPref.CPU -> this == CPU
      BackendPref.AUTO -> true
    }
}

/** One device registered with transcribe.cpp. */
data class Device(val index: Int, val name: String, val description: String, val kind: String, val type: String) {
  val engine: EngineKind?
    get() =
      when {
        name.startsWith("HTP") -> EngineKind.NPU
        name.contains("OpenCL", ignoreCase = true) -> EngineKind.GPU_OPENCL
        kind == "vulkan" -> EngineKind.GPU_VULKAN
        type == "cpu" -> EngineKind.CPU
        else -> null // host accelerators (BLAS/AMX) can't be a model primary
      }

  companion object {
    fun parse(row: String): Device? {
      val p = row.split('\t')
      if (p.size < 5) return null
      return Device(p[0].toIntOrNull() ?: return null, p[1], p[2], p[3], p[4])
    }
  }
}

sealed interface EngineState {
  data object Unloaded : EngineState

  data class Loading(val what: String) : EngineState

  data class Ready(val kind: EngineKind, val device: String, val note: String? = null) : EngineState

  data class Failed(val message: String) : EngineState
}

data class Transcript(
  val text: String,
  val audioMs: Long,
  val inferenceMs: Long,
  val kind: EngineKind,
  val device: String,
  val timings: FloatArray,
  val truncated: Boolean,
)

class NoModelException(spec: ModelSpec) : Exception("The ${spec.label} model is not downloaded")

class EngineException(message: String) : Exception(message)

class CancelledException : Exception("cancelled")

/** [maxOkMs]: longest recording this engine is verified for (see AccelPolicy); CPU is always unlimited. */
data class BenchEntry(
  val kind: EngineKind,
  val device: String,
  val ms: Long,
  val text: String,
  val correct: Boolean,
  val error: String?,
  val maxOkMs: Long = if (kind == EngineKind.CPU) AccelPolicy.UNLIMITED else AccelPolicy.SHORT_CLIP_MS,
)

data class BenchmarkResult(val modelId: String, val reference: String, val entries: List<BenchEntry>, val winner: EngineKind, val timestamp: Long) {
  fun toJson(): String =
    JSONObject()
      .put("model", modelId)
      .put("reference", reference)
      .put("winner", winner.name)
      .put("ts", timestamp)
      .put(
        "entries",
        JSONArray().apply {
          entries.forEach {
            put(JSONObject().put("kind", it.kind.name).put("device", it.device).put("ms", it.ms).put("text", it.text).put("correct", it.correct).put("error", it.error ?: "").put("maxOkMs", it.maxOkMs))
          }
        },
      )
      .toString()

  companion object {
    fun fromJson(s: String?): BenchmarkResult? =
      runCatching {
        val o = JSONObject(s!!)
        val arr = o.getJSONArray("entries")
        BenchmarkResult(
          o.getString("model"),
          o.getString("reference"),
          (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            val kind = EngineKind.valueOf(e.getString("kind"))
            // results saved before the long-clip check existed only verified the 5.4 s clip
            val fallbackMax = if (kind == EngineKind.CPU) AccelPolicy.UNLIMITED else AccelPolicy.SHORT_CLIP_MS
            BenchEntry(kind, e.getString("device"), e.getLong("ms"), e.getString("text"), e.getBoolean("correct"), e.getString("error").ifEmpty { null }, e.optLong("maxOkMs", fallbackMax))
          },
          EngineKind.valueOf(o.getString("winner")),
          o.getLong("ts"),
        )
      }.getOrNull()
  }
}

/**
 * Owns the transcribe.cpp session. Everything native runs on one dedicated thread ("voicex-asr"), which is also
 * the thread pinned to the performance cores, so ggml's per-graph workers inherit the pinning.
 *
 * Fallback rules (never let an accelerator stop the app from working):
 *  - a device that fails to load -> CPU
 *  - TRANSCRIBE_ERR_BACKEND during a run -> free the session, reload on CPU, retry once (per transcribe.h)
 */
class AsrEngine(private val context: Context, private val settings: Settings) {
  private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "voicex-asr") }.asCoroutineDispatcher()
  private val libDir = context.applicationInfo.nativeLibraryDir

  private var nativeReady = false
  private var initError: String? = null

  @Volatile private var handle = 0L
  private var loadedPath: String? = null
  private var loadedKind: EngineKind? = null
  private var loadedDevice = ""
  private var loadedThreads = 0
  /** Language hint passed to every run on the loaded model ("en", "ur"). */
  private var loadedLang = "en"
  private var pinned: IntArray? = null

  /** A CPU session kept next to an accelerator, for recordings the accelerator isn't trusted with. Lazy. */
  @Volatile private var cpuHandle = 0L

  /** Lowered in this process when an accelerator returns empty output (AccelPolicy.lowered). */
  private val runtimeLimitMs = mutableMapOf<EngineKind, Long>()

  /** Devices whose load or run failed in this process: skipped until the next benchmark. */
  private val broken = mutableSetOf<EngineKind>()

  private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
  val state: StateFlow<EngineState> = _state

  var devices: List<Device> = emptyList()
    private set

  val isLoaded: Boolean
    get() = handle != 0L

  private fun initNative() {
    if (nativeReady) return
    initError?.let { throw EngineException(it) }
    try {
      // FastRPC looks for the NPU-side libggml-htp-v*.so on this ';'-separated path; ours sits in nativeLibraryDir
      Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/dsp", true)
      // Whisper (Urdu): encode short recordings over their real length + a margin instead of a padded 30 s window.
      // The encoder is most of the cost and scales with the window (see the patch in arch/whisper/model.cpp).
      Os.setenv("TRANSCRIBE_WHISPER_SHORT_WINDOW_PAD_FRAMES", WHISPER_WINDOW_PAD_FRAMES.toString(), true)
      TranscribeNative.load()
      val st = TranscribeNative.nativeInitBackends(libDir)
      devices = TranscribeNative.nativeDevices().mapNotNull { Device.parse(it) }
      AppLog.i(TAG, "transcribe.cpp ${TranscribeNative.nativeVersion()}; init=${TranscribeNative.nativeStatusString(st)}; devices=$devices")
      if (st != TranscribeNative.OK && devices.none { it.engine == EngineKind.CPU }) {
        throw EngineException("No compute backend could be loaded (${TranscribeNative.nativeStatusString(st)})")
      }
      nativeReady = true
      noteCrashFromLastRun()
    } catch (e: Throwable) {
      initError = e.message ?: e.toString()
      AppLog.e(TAG, "native engine init failed", e)
      throw EngineException(initError!!)
    }
  }

  /**
   * A ggml abort in an accelerator backend kills the whole process; Kotlin can't catch it. So each accelerator call
   * is bracketed by Settings.nativeInFlight, and if the last process died in native code with it still set, that
   * model + accelerator pair is recorded and never tried again (until the next app version).
   */
  private fun noteCrashFromLastRun() {
    val inFlight = settings.nativeInFlight ?: return
    settings.nativeInFlight = null
    val nativeCrash =
      runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
      }.getOrDefault(false)
    if (!nativeCrash) return
    val file = inFlight.substringBefore('|')
    val kind = inFlight.substringAfter('|')
    settings.addCrashedEngine(file, kind)
    AppLog.w(TAG, "the app crashed in native code while running $file on $kind; that engine won't be used with this model again")
  }

  /** [kind] must not be used for [spec]: known to crash with it, or it crashed the app on this phone. */
  private fun crashes(spec: ModelSpec, kind: EngineKind): Boolean =
    kind != EngineKind.CPU && (kind.name in spec.knownBadEngines || kind.name in settings.crashedEngines(spec.fileName))

  /** Runs [block] with the crash marker set when [kind] is an accelerator. */
  private inline fun <T> guarded(path: String, kind: EngineKind?, block: () -> T): T {
    if (kind == null || kind == EngineKind.CPU) return block()
    settings.nativeInFlight = "${File(path).name}|${kind.name}"
    try {
      return block()
    } finally {
      settings.nativeInFlight = null
    }
  }

  /** Engines available on this phone, best-first (NPU, OpenCL, Vulkan, CPU). */
  suspend fun availableEngines(): List<EngineKind> =
    withContext(dispatcher) {
      initNative()
      EngineKind.entries.filter { k -> k == EngineKind.CPU || devices.any { it.engine == k } }
    }

  suspend fun deviceList(): List<Device> =
    withContext(dispatcher) {
      initNative()
      devices
    }

  private fun pinThreads(n: Int) {
    val cpus = CpuTopology.fastest(n)
    if (pinned?.contentEquals(cpus) == true) return
    val rc = TranscribeNative.nativePinThread(cpus)
    pinned = cpus
    AppLog.i(TAG, "inference thread pinned to cpus ${cpus.joinToString()} (rc=$rc)")
  }

  private fun deviceFor(kind: EngineKind): Device? = if (kind == EngineKind.CPU) null else devices.firstOrNull { it.engine == kind }

  /** Opens [kind]; returns null on success or the error text. Leaves the engine unloaded on failure. */
  private fun open(path: String, kind: EngineKind, threads: Int, lang: String = loadedLang): String? {
    closeNow()
    val dev = deviceFor(kind)
    if (kind != EngineKind.CPU && dev == null) return "${kind.label} is not available on this phone"
    pinThreads(threads)
    val out = LongArray(1)
    val st = guarded(path, kind) { TranscribeNative.nativeOpen(path, dev?.index ?: -1, threads, out) }
    if (st != TranscribeNative.OK || out[0] == 0L) {
      val why = TranscribeNative.nativeStatusString(st)
      AppLog.w(TAG, "${kind.label}: model load failed: $why (status $st)")
      return why
    }
    val h = out[0]
    handle = h
    loadedPath = path
    loadedKind = kind
    loadedThreads = threads
    loadedLang = lang
    loadedDevice = TranscribeNative.nativeBackend(h)
    return null
  }

  private fun closeNow() {
    val h = handle
    if (h != 0L) {
      handle = 0L
      TranscribeNative.nativeClose(h)
    }
    val c = cpuHandle
    if (c != 0L) {
      cpuHandle = 0L
      TranscribeNative.nativeClose(c)
    }
    loadedPath = null
    loadedKind = null
  }

  private fun currentSpec() = ModelSpec.active(settings)

  /** Runs [pcm] on session [h] with the loaded model's language. */
  private fun run(h: Long, pcm: FloatArray): Int {
    val kind = if (h == cpuHandle) EngineKind.CPU else loadedKind
    return guarded(loadedPath ?: "", kind) { TranscribeNative.nativeRun(h, pcm, pcm.size, loadedLang, promptFor(loadedLang)) }
  }

  /** What the settings + benchmark say to use. Null = AUTO without a benchmark yet. */
  private fun desiredKind(spec: ModelSpec): EngineKind? {
    val pref = settings.backend
    val available = EngineKind.entries.filter { k -> k == EngineKind.CPU || devices.any { it.engine == k } }
    if (pref != BackendPref.AUTO) {
      val k = available.firstOrNull { it.matches(pref) } ?: EngineKind.CPU
      return if (k in broken || crashes(spec, k)) EngineKind.CPU else k
    }
    val bench = BenchmarkResult.fromJson(settings.benchmarkJson(spec.id)) ?: return null
    val w = bench.winner
    return if (w in available && w !in broken && !crashes(spec, w)) w else EngineKind.CPU
  }

  /**
   * Loads the selected model on the selected device (running the first-load benchmark if AUTO has none yet).
   * Cheap when already loaded as configured.
   */
  suspend fun ensureLoaded(): EngineState.Ready = withContext(dispatcher) { ensureLoadedNow() }

  private fun ensureLoadedNow(): EngineState.Ready {
    initNative()
    val spec = currentSpec()
    val file = ModelFiles.file(context, spec)
    if (!ModelFiles.isPresent(context, spec)) {
      closeNow()
      _state.value = EngineState.Unloaded
      throw NoModelException(spec)
    }
    val threads = settings.threads
    var kind = desiredKind(spec)
    if (kind == null) {
      benchmarkNow(spec) { _state.value = EngineState.Loading(it) }
      kind = desiredKind(spec) ?: EngineKind.CPU
    }
    // same model, different Whisper language: the language is passed per run, so no reload is needed
    if (handle != 0L && loadedPath == file.path) loadedLang = settings.langCode(spec.lang)
    val ready = _state.value
    if (handle != 0L && loadedPath == file.path && loadedKind == kind && loadedThreads == threads && ready is EngineState.Ready) return ready

    _state.value = EngineState.Loading("Loading model on ${kind.label}")
    var note: String? = null
    EngineKind.entries.firstOrNull { it != EngineKind.CPU && it.matches(settings.backend) && settings.backend != BackendPref.AUTO }?.let { chosen ->
      if (crashes(spec, chosen)) note = "${chosen.label} crashes with ${spec.id}; using ${kind.label}"
    }
    var err = open(file.path, kind, threads, settings.langCode(spec.lang))
    if (err != null && kind != EngineKind.CPU) {
      AppLog.w(TAG, "${kind.label} failed to load ($err); falling back to CPU")
      broken += kind
      note = "${kind.label} failed ($err); using CPU"
      kind = EngineKind.CPU
      err = open(file.path, kind, threads, settings.langCode(spec.lang))
    }
    if (err != null) {
      _state.value = EngineState.Failed("Could not load the model: $err")
      throw EngineException("Could not load the model: $err")
    }
    AppLog.ok(TAG, "${spec.id} (${settings.langLabel(spec.lang)}) loaded on ${kind.label} [$loadedDevice], $threads threads")
    return EngineState.Ready(kind, loadedDevice, note).also { _state.value = it }
  }

  /**
   * Transcribes 16 kHz mono float PCM (whole recording, offline/batch mode).
   *
   * Seam for live dictation: a streaming variant would open a transcribe_stream_* session here instead and feed
   * AudioCapture's chunks as they arrive; nothing outside this class would change except the dot's label.
   */
  suspend fun transcribe(pcm: FloatArray): Transcript =
    withContext(dispatcher) {
      ensureLoadedNow()
      val audioMs = PcmUtils.samplesToMs(pcm.size)
      val primary = loadedKind!!
      val limit = verifiedMs(primary)
      if (!AccelPolicy.trusted(primary, audioMs, limit)) {
        AppLog.i(TAG, "${audioMs} ms recording: ${primary.label} is verified up to ${limit / 1000} s, so this one runs on CPU")
        return@withContext runOnCpu(pcm, audioMs)
      }
      val first = runOnPrimary(pcm, audioMs)
      if (first.kind != EngineKind.CPU && AccelPolicy.looksEmpty(first.text, audioMs)) {
        // Safety net: an accelerator answered with (almost) nothing for audio that has speech. Redo it on CPU and
        // stop trusting this accelerator with recordings this long.
        runtimeLimitMs[first.kind] = AccelPolicy.lowered(limit, audioMs)
        AppLog.w(TAG, "${first.kind.label} returned ${first.text.length} chars for $audioMs ms of speech; re-running on CPU (now trusted below ${audioMs / 1000} s)")
        val cpu = runOnCpu(pcm, audioMs)
        return@withContext if (cpu.text.length >= first.text.length) cpu.copy(inferenceMs = first.inferenceMs + cpu.inferenceMs) else first
      }
      first
    }

  /** Longest recording [kind] is trusted with: benchmark result, lowered by any empty output seen this run. */
  private fun verifiedMs(kind: EngineKind): Long {
    if (kind == EngineKind.CPU) return AccelPolicy.UNLIMITED
    val bench = lastBenchmark()?.entries?.firstOrNull { it.kind == kind }?.maxOkMs ?: AccelPolicy.UNLIMITED
    return minOf(bench, runtimeLimitMs[kind] ?: AccelPolicy.UNLIMITED)
  }

  /** Runs on the CPU safety-net session (opened next to the accelerator on first use). */
  private fun runOnCpu(pcm: FloatArray, audioMs: Long): Transcript {
    val h =
      if (loadedKind == EngineKind.CPU) handle
      else {
        if (cpuHandle == 0L) {
          val out = LongArray(1)
          val st = TranscribeNative.nativeOpen(loadedPath!!, -1, settings.threads, out)
          if (st != TranscribeNative.OK || out[0] == 0L) throw EngineException("CPU fallback could not load the model: ${TranscribeNative.nativeStatusString(st)}")
          cpuHandle = out[0]
          AppLog.i(TAG, "CPU session opened next to ${loadedKind?.label} for long or failed recordings")
        }
        cpuHandle
      }
    val t0 = System.nanoTime()
    val st = run(h, pcm)
    val ms = (System.nanoTime() - t0) / 1_000_000
    return toTranscript(st, h, audioMs, ms, EngineKind.CPU, "CPU")
  }

  private fun toTranscript(st: Int, h: Long, audioMs: Long, ms: Long, kind: EngineKind, device: String): Transcript =
    when (st) {
      TranscribeNative.OK, TranscribeNative.ERR_OUTPUT_TRUNCATED, TranscribeNative.ERR_OUTPUT_REPETITION ->
        Transcript(TranscribeNative.nativeText(h).toString(Charsets.UTF_8).trim(), audioMs, ms, kind, device, TranscribeNative.nativeTimings(h), st != TranscribeNative.OK)
      TranscribeNative.ERR_INPUT_TOO_SHORT -> Transcript("", audioMs, ms, kind, device, FloatArray(4), false)
      TranscribeNative.ERR_ABORTED -> throw CancelledException()
      else -> throw EngineException("Transcription failed: ${TranscribeNative.nativeStatusString(st)}")
    }

  private fun runOnPrimary(pcm: FloatArray, audioMs: Long): Transcript {
      var t0 = System.nanoTime()
      var st = run(handle, pcm)
      if (st == TranscribeNative.ERR_BACKEND && loadedKind != EngineKind.CPU) {
        // transcribe.h: TRANSCRIBE_ERR_BACKEND is recoverable in-process: free, reload on CPU, retry.
        val failed = loadedKind!!
        AppLog.w(TAG, "${failed.label} backend error during run; reloading on CPU and retrying")
        broken += failed
        val path = loadedPath!!
        open(path, EngineKind.CPU, settings.threads)?.let { throw EngineException("CPU reload failed: $it") }
        _state.value = EngineState.Ready(EngineKind.CPU, loadedDevice, "${failed.label} failed during transcription; using CPU")
        t0 = System.nanoTime()
        st = run(handle, pcm)
      }
      val ms = (System.nanoTime() - t0) / 1_000_000
      return toTranscript(st, handle, audioMs, ms, loadedKind!!, loadedDevice)
  }

  /** Cancels a running transcription (thread-safe). */
  fun abort() {
    val h = handle
    if (h != 0L) TranscribeNative.nativeAbort(h)
    val c = cpuHandle
    if (c != 0L) TranscribeNative.nativeAbort(c)
  }

  /** Frees the model (memory pressure, or "load on keyboard open" mode). Reloads lazily on the next use. */
  suspend fun release() =
    withContext(dispatcher) {
      if (handle != 0L) AppLog.i(TAG, "releasing model")
      closeNow()
      _state.value = EngineState.Unloaded
    }

  // ---- benchmark as one shared job (the UI only observes it; at most one runs at a time) ------------------------
  private val _benchRunning = MutableStateFlow(false)
  val benchRunning: StateFlow<Boolean> = _benchRunning
  private val _benchLines = MutableStateFlow<List<String>>(emptyList())
  val benchLines: StateFlow<List<String>> = _benchLines
  private val _benchError = MutableStateFlow<String?>(null)
  val benchError: StateFlow<String?> = _benchError
  private val _benchResult by lazy { MutableStateFlow(lastBenchmark()) }

  /** Stored result for the selected model; updated when a run finishes or [refreshBenchmark] is called. */
  val benchResult: StateFlow<BenchmarkResult?>
    get() = _benchResult

  /** Call after the model selection changes. */
  fun refreshBenchmark() {
    _benchResult.value = lastBenchmark()
  }

  /** Starts the benchmark in [scope] unless one is already running. Returns false when one was. */
  fun startBenchmark(scope: CoroutineScope): Boolean {
    if (!_benchRunning.compareAndSet(false, true)) return false
    _benchLines.value = emptyList()
    _benchError.value = null
    scope.launch {
      try {
        _benchResult.value = runBenchmark { line -> _benchLines.update { it + line } }
      } catch (e: Exception) {
        _benchError.value = e.message ?: e.toString()
      } finally {
        _benchRunning.value = false
      }
    }
    return true
  }

  fun lastBenchmark(): BenchmarkResult? = BenchmarkResult.fromJson(settings.benchmarkJson(currentSpec().id))

  /** Runs the benchmark for the selected model and stores the result. [progress] gets one line per step. */
  suspend fun runBenchmark(progress: (String) -> Unit = {}): BenchmarkResult =
    withContext(dispatcher) {
      initNative()
      val spec = currentSpec()
      if (!ModelFiles.isPresent(context, spec)) throw NoModelException(spec)
      broken.clear()
      runtimeLimitMs.clear()
      val r = benchmarkNow(spec, progress)
      // leave the engine loaded the way the settings now say
      runCatching { ensureLoadedNow() }
      r
    }

  /**
   * Transcribes the bundled short clip (English: 5.4 s of JFK; Urdu: a 6.2 s FLEURS sentence) with every available
   * engine. CPU runs first and is the reference; a backend
   * counts as correct when its transcript matches it exactly or differs by at most one word. Each backend gets a
   * warm-up run (GPU shader compilation, NPU session setup), then a timed run. The fastest correct one wins.
   *
   * Accelerators then get correctness checks on longer audio (the full 11 s JFK clip, and 33 s = three copies), since
   * on the S23 the NPU was right on 5.4 s but returned nothing for a 30 s dictation. The longest clip each one gets
   * right becomes its limit; longer recordings go to the CPU (AccelPolicy).
   */
  private fun benchmarkNow(spec: ModelSpec, progress: (String) -> Unit): BenchmarkResult {
    val path = ModelFiles.file(context, spec).path
    val (shortClip, longClip) = clipsFor(spec.lang)
    val clip = context.assets.open(shortClip).use { WavReader.readMono16k(it) }
    val clip11 = context.assets.open(longClip).use { WavReader.readMono16k(it) }
    val gap = FloatArray(PcmUtils.SAMPLE_RATE / 2)
    val clip33 = clip11 + gap + clip11 + gap + clip11
    var ref11 = ""
    var ref33 = ""
    val threads = settings.threads
    val order = listOf(EngineKind.CPU) + listOf(EngineKind.NPU, EngineKind.GPU_OPENCL, EngineKind.GPU_VULKAN).filter { k -> devices.any { it.engine == k } }
    val entries = mutableListOf<BenchEntry>()
    var reference = ""
    for (kind in order) {
      if (crashes(spec, kind)) {
        entries += BenchEntry(kind, deviceFor(kind)?.name ?: "", 0, "", false, "skipped: crashes with this model")
        AppLog.w(TAG, "benchmark ${kind.label}: skipped, it crashes with ${spec.id}")
        progress("Benchmark: ${kind.label}: skipped (crashes with this model)")
        continue
      }
      progress("Benchmark: ${kind.label}…")
      // the benchmark compares engines on its bundled clips, so it uses their language (Urdu for Whisper)
      val err = open(path, kind, threads, benchLangCode(spec.lang))
      if (err != null) {
        entries += BenchEntry(kind, deviceFor(kind)?.name ?: "", 0, "", false, "load failed: $err")
        AppLog.w(TAG, "benchmark ${kind.label}: load failed: $err")
        if (kind == EngineKind.CPU) {
          closeNow()
          throw EngineException("CPU could not load the model: $err")
        }
        continue
      }
      val device = loadedDevice
      try {
        var st = run(handle, clip) // warm-up
        var ms = 0L
        if (st == TranscribeNative.OK) {
          val t0 = System.nanoTime()
          st = run(handle, clip)
          ms = (System.nanoTime() - t0) / 1_000_000
        }
        val text = if (st == TranscribeNative.OK) TranscribeNative.nativeText(handle).toString(Charsets.UTF_8).trim() else ""
        if (kind == EngineKind.CPU) reference = text
        val ok = st == TranscribeNative.OK && WordDiff.matches(reference, text)
        var error = if (st != TranscribeNative.OK) TranscribeNative.nativeStatusString(st) else if (!ok) "transcript differs from CPU" else null
        var maxOk = AccelPolicy.UNLIMITED
        if (kind == EngineKind.CPU) {
          progress("Benchmark: CPU on 11 s and 33 s clips (reference)…")
          ref11 = runText(clip11)
          ref33 = runText(clip33)
        } else if (ok) {
          progress("Benchmark: ${kind.label} on 11 s and 33 s clips…")
          val ok11 = longCheck(clip11, ref11)
          val ok33 = if (ok11) longCheck(clip33, ref33) else null
          maxOk = AccelPolicy.verifiedMs(true, ok11, ok33)
          if (maxOk != AccelPolicy.UNLIMITED) {
            error = "wrong on ${if (ok11) 33 else 11} s audio; used up to ${maxOk / 1000} s"
            AppLog.w(TAG, "benchmark ${kind.label}: long-audio check failed; trusted for recordings up to ${maxOk / 1000} s")
          } else {
            AppLog.ok(TAG, "benchmark ${kind.label}: 11 s and 33 s clips match CPU")
          }
        }
        entries += BenchEntry(kind, device, ms, text, ok, error, if (ok) maxOk else 0L)
        // benchmark text is the bundled public-domain clip, not user speech, so it may be logged
        if (ok) AppLog.ok(TAG, "benchmark ${kind.label} [$device]: $ms ms, transcript matches CPU")
        else AppLog.w(TAG, "benchmark ${kind.label} [$device]: ${error ?: "failed"} (\"$text\")")
        progress("Benchmark: ${kind.label}: ${if (ok) "$ms ms" else error}")
      } finally {
        closeNow()
      }
    }
    // fastest on the short clip wins; recordings longer than its verified length still go to the CPU
    val winner = entries.filter { it.correct }.minByOrNull { it.ms }?.kind ?: EngineKind.CPU
    val result = BenchmarkResult(spec.id, reference, entries, winner, System.currentTimeMillis())
    settings.setBenchmarkJson(spec.id, result.toJson())
    AppLog.ok(TAG, "benchmark (${spec.id}): fastest correct = ${winner.label}")
    progress("Fastest correct: ${winner.label}")
    return result
  }

  /** Transcribes with the current session and returns the text ("" on any error). Benchmark helper. */
  private fun runText(pcm: FloatArray): String {
    val st = run(handle, pcm)
    return if (st == TranscribeNative.OK) TranscribeNative.nativeText(handle).toString(Charsets.UTF_8).trim() else ""
  }

  /** The current (accelerator) session's transcript of [pcm] is within AccelPolicy.tolerance of [reference]. */
  private fun longCheck(pcm: FloatArray, reference: String): Boolean {
    if (reference.isBlank()) return false
    val text = runText(pcm)
    val d = WordDiff.distance(reference, text)
    return text.isNotBlank() && d <= AccelPolicy.tolerance(WordDiff.words(reference).size)
  }

  companion object {
    private const val TAG = "engine"
    const val BENCH_CLIP = "bench/jfk-5s.wav"
    const val BENCH_CLIP_11S = "bench/jfk-11s.wav"
    /** Urdu sentences from Google FLEURS (CC BY 4.0), test split: 6.2 s and 12.2 s. */
    const val BENCH_CLIP_UR = "bench/fleurs-ur-6s.wav"
    const val BENCH_CLIP_UR_12S = "bench/fleurs-ur-12s.wav"

    /** Short timing clip and long check clip for [lang]. */
    fun clipsFor(lang: Lang): Pair<String, String> =
      when (lang) {
        Lang.EN -> BENCH_CLIP to BENCH_CLIP_11S
        Lang.WHISPER -> BENCH_CLIP_UR to BENCH_CLIP_UR_12S
      }

    /** Length of the short benchmark clip, for the results table. */
    /** Mel frames (10 ms each) of silence encoded after a short Urdu recording; chosen on FLEURS, see README. */
    const val WHISPER_WINDOW_PAD_FRAMES = 200

    /**
     * Whisper writes Chinese in Traditional or Simplified characters unpredictably; a Simplified-Chinese starting
     * prompt keeps it Simplified (checked on three FLEURS Mandarin sentences: one came out Traditional without it).
     */
    fun promptFor(lang: String): String? = if (lang == "zh") "以下是普通话的句子。" else null

    /** Language of the bundled benchmark clips for [lang]'s model. */
    fun benchLangCode(lang: Lang): String = if (lang == Lang.EN) "en" else "ur"

    fun shortClipLabel(lang: Lang): String = if (lang == Lang.WHISPER) "6.2 s Urdu clip" else "5.4 s clip"
  }
}
