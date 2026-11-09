package com.voicex.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Which compute device the user wants. AUTO = the benchmark winner. */
enum class BackendPref(val key: String, val label: String) {
  AUTO("auto", "Auto (fastest correct)"),
  NPU("npu", "NPU (Hexagon)"),
  GPU_OPENCL("gpu_opencl", "GPU (OpenCL)"),
  GPU_VULKAN("gpu_vulkan", "GPU (Vulkan)"),
  CPU("cpu", "CPU");

  companion object {
    fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
  }
}

/** App settings in one SharedPreferences file. Local only; nothing here leaves the device. */
class Settings(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("voicex", Context.MODE_PRIVATE)

  /** Emits the changed key. */
  val changes: SharedFlow<String>
    get() = _changes

  private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> key?.let { _changes.tryEmit(it) } }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  var capSeconds: Int
    get() = prefs.getInt(K_CAP, 300).coerceIn(CAP_MIN, CAP_MAX)
    set(v) = prefs.edit().putInt(K_CAP, v.coerceIn(CAP_MIN, CAP_MAX)).apply()

  var autoPaste: Boolean
    get() = prefs.getBoolean(K_AUTO_PASTE, false)
    set(v) = prefs.edit().putBoolean(K_AUTO_PASTE, v).apply()

  var trailingSpace: Boolean
    get() = prefs.getBoolean(K_TRAILING_SPACE, false)
    set(v) = prefs.edit().putBoolean(K_TRAILING_SPACE, v).apply()

  var modelId: String
    get() = prefs.getString(K_MODEL, "Q5_K_M") ?: "Q5_K_M"
    set(v) = prefs.edit().putString(K_MODEL, v).apply()

  var wifiOnly: Boolean
    get() = prefs.getBoolean(K_WIFI_ONLY, true)
    set(v) = prefs.edit().putBoolean(K_WIFI_ONLY, v).apply()

  var backend: BackendPref
    get() = BackendPref.of(prefs.getString(K_BACKEND, "auto"))
    set(v) = prefs.edit().putString(K_BACKEND, v.key).apply()

  var threads: Int
    get() = prefs.getInt(K_THREADS, 4).coerceIn(1, 8)
    set(v) = prefs.edit().putInt(K_THREADS, v.coerceIn(1, 8)).apply()

  /** true: keep the model resident (default). false: load when the keyboard opens, unload 60 s after it closes. */
  var keepLoaded: Boolean
    get() = prefs.getBoolean(K_KEEP_LOADED, true)
    set(v) = prefs.edit().putBoolean(K_KEEP_LOADED, v).apply()

  var dotSizeDp: Int
    get() = prefs.getInt(K_DOT_SIZE, 48).coerceIn(36, 72)
    set(v) = prefs.edit().putInt(K_DOT_SIZE, v.coerceIn(36, 72)).apply()

  /** Idle opacity of the dot, 0.3..1. */
  var dotOpacity: Float
    get() = prefs.getFloat(K_DOT_OPACITY, 0.6f).coerceIn(0.3f, 1f)
    set(v) = prefs.edit().putFloat(K_DOT_OPACITY, v.coerceIn(0.3f, 1f)).apply()

  var haptics: Boolean
    get() = prefs.getBoolean(K_HAPTICS, true)
    set(v) = prefs.edit().putBoolean(K_HAPTICS, v).apply()

  var historyEnabled: Boolean
    get() = prefs.getBoolean(K_HISTORY, true)
    set(v) = prefs.edit().putBoolean(K_HISTORY, v).apply()

  var setupDone: Boolean
    get() = prefs.getBoolean(K_SETUP_DONE, false)
    set(v) = prefs.edit().putBoolean(K_SETUP_DONE, v).apply()

  /** Set when starting the microphone service from the accessibility overlay was refused (Risk 1). */
  var fgsBlockedSeen: Boolean
    get() = prefs.getBoolean(K_FGS_BLOCKED, false)
    set(v) = prefs.edit().putBoolean(K_FGS_BLOCKED, v).apply()

  /** Benchmark result for a model, as JSON (see BenchmarkResult). */
  fun benchmarkJson(modelId: String): String? = prefs.getString("bench_$modelId", null)

  fun setBenchmarkJson(modelId: String, json: String?) = prefs.edit().apply { if (json == null) remove("bench_$modelId") else putString("bench_$modelId", json) }.apply()

  /** Saved dot position: x (px from the left) and dy (px between the dot's top and the keyboard's top). */
  fun dotOffset(landscape: Boolean): Pair<Int, Int>? {
    val o = if (landscape) "land" else "port"
    if (!prefs.contains("dot_x_$o")) return null
    return prefs.getInt("dot_x_$o", 0) to prefs.getInt("dot_dy_$o", 0)
  }

  fun setDotOffset(landscape: Boolean, x: Int, dy: Int) {
    val o = if (landscape) "land" else "port"
    prefs.edit().putInt("dot_x_$o", x).putInt("dot_dy_$o", dy).apply()
  }

  fun resetDotOffsets() = prefs.edit().remove("dot_x_port").remove("dot_dy_port").remove("dot_x_land").remove("dot_dy_land").apply()

  companion object {
    const val CAP_MIN = 30
    const val CAP_MAX = 15 * 60

    const val K_CAP = "cap_seconds"
    const val K_AUTO_PASTE = "auto_paste"
    const val K_TRAILING_SPACE = "trailing_space"
    const val K_MODEL = "model"
    const val K_WIFI_ONLY = "wifi_only"
    const val K_BACKEND = "backend"
    const val K_THREADS = "threads"
    const val K_KEEP_LOADED = "keep_loaded"
    const val K_DOT_SIZE = "dot_size"
    const val K_DOT_OPACITY = "dot_opacity"
    const val K_HAPTICS = "haptics"
    const val K_HISTORY = "history"
    const val K_SETUP_DONE = "setup_done"
    const val K_FGS_BLOCKED = "fgs_blocked"
  }
}
