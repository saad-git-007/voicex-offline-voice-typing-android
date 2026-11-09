package com.voicex.app.engine

/**
 * JNI bindings to transcribe.cpp (app/src/main/cpp/voicex_jni.cpp). Thin by design: all policy (device
 * order, the TRANSCRIBE_ERR_BACKEND -> CPU retry, benchmarking) is in [AsrEngine].
 *
 * Not thread-safe per handle: one session is used from one thread at a time ([nativeAbort] excepted).
 */
object TranscribeNative {
  // transcribe_status values used by the app (include/transcribe.h)
  const val OK = 0
  const val ERR_FILE_NOT_FOUND = 3
  const val ERR_GGUF = 4
  const val ERR_OOM = 7
  const val ERR_BACKEND = 8
  const val ERR_ABORTED = 13
  const val ERR_OUTPUT_TRUNCATED = 18
  const val ERR_OUTPUT_REPETITION = 19
  const val ERR_INPUT_TOO_SHORT = 21

  @Volatile private var loaded = false

  /** Loads libvoicex_jni (Android) or the library at [hostPath] (JVM unit tests on the build machine). */
  @Synchronized
  fun load(hostPath: String? = null) {
    if (loaded) return
    if (hostPath != null) System.load(hostPath) else System.loadLibrary("voicex_jni")
    loaded = true
  }

  external fun nativeVersion(): String

  external fun nativeStatusString(status: Int): String

  /** Loads ggml backend modules from [dir] (nativeLibraryDir). Call once before anything else. */
  external fun nativeInitBackends(dir: String?): Int

  /** "index\tname\tdescription\tkind\tdevice_type" per registered device. */
  external fun nativeDevices(): Array<String>

  /**
   * [deviceIndex] < 0 = strict CPU. Returns a transcribe_status; on [OK] the handle is in [out][0].
   * Handles are opaque and may be negative (arm64 tagged pointers): only 0 means "none".
   */
  external fun nativeOpen(path: String, deviceIndex: Int, nThreads: Int, out: LongArray): Int

  external fun nativeBackend(handle: Long): String

  /** 16 kHz mono float PCM in [-1, 1], with a language hint ("en", "ur"; null = model default). Returns a transcribe_status. */
  external fun nativeRun(handle: Long, pcm: FloatArray, n: Int, language: String?): Int

  /** UTF-8 bytes of transcribe_full_text(). */
  external fun nativeText(handle: Long): ByteArray

  /** [load, mel, encode, decode] in ms. */
  external fun nativeTimings(handle: Long): FloatArray

  external fun nativeAbort(handle: Long)

  external fun nativeClose(handle: Long)

  /** Pins the calling thread (and the ggml workers it spawns) to [cpus]; empty = all CPUs. 0 or errno. */
  external fun nativePinThread(cpus: IntArray): Int
}
