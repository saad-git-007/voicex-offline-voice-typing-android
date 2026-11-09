package com.voicex.app.model

import android.content.Context
import com.voicex.app.Settings
import java.io.File

/** Dictation languages. Each uses its own model (see [ModelSpec.lang]). */
enum class Lang(val code: String, val label: String, val native: String, val badge: String) {
  EN("en", "English", "English", "EN"),
  UR("ur", "Urdu", "اردو", "UR");

  companion object {
    fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
  }
}

/**
 * The models VoiceX can use. English: the two Parakeet Unified EN 0.6B quantizations (handy-computer/
 * parakeet-unified-en-0.6b-gguf, checked 2026-11-03). Urdu: OpenAI Whisper large-v3-turbo, q5_0, in whisper.cpp's
 * .bin format (ggerganov/whisper.cpp, checked 2026-11-09), which transcribe.cpp loads directly. Sizes and SHA-256
 * hashes are from the Hugging Face LFS metadata and were checked against downloaded copies.
 */
enum class ModelSpec(
  val id: String,
  val fileName: String,
  val sizeBytes: Long,
  val sha256: String,
  val label: String,
  val blurb: String,
  val lang: Lang = Lang.EN,
  val repo: String = "handy-computer/parakeet-unified-en-0.6b-gguf",
  /** Accelerators known to crash with this model (EngineKind names); never tried. */
  val knownBadEngines: Set<String> = emptySet(),
) {
  Q5_K_M(
    "Q5_K_M",
    "parakeet-unified-en-0.6b-Q5_K_M.gguf",
    540_795_264L,
    "f9def6f9b4e83ab7d006df3e1b676dfa1f973a3b6da232a9c99fcaa66bcd2836",
    "Q5_K_M (541 MB)",
    "Recommended. Best accuracy.",
  ),
  Q4_K_M(
    "Q4_K_M",
    "parakeet-unified-en-0.6b-Q4_K_M.gguf",
    477_274_496L,
    "a8bf3de2b393bd14ead5a858c3748d5e3b07a20fdeabdd3b498fba4f463fa929",
    "Q4_K_M (477 MB)",
    "Low-memory alternative.",
  ),
  WHISPER_TURBO_Q5(
    "whisper-large-v3-turbo-q5_0",
    "ggml-large-v3-turbo-q5_0.bin",
    574_041_195L,
    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    "Whisper large-v3-turbo q5_0 (574 MB)",
    "OpenAI Whisper, used for Urdu.",
    Lang.UR,
    "ggerganov/whisper.cpp",
    // S23 Ultra (Hexagon v73): ggml_abort in ggml_backend_sched_split_graph on the first run, which kills the app
    setOf("NPU"),
  );

  val url: String
    get() = "https://huggingface.co/$repo/resolve/main/$fileName"

  companion object {
    /** An English model by id (the English model setting); Q5_K_M when unknown. */
    fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Q5_K_M

    /** English models, for the English model picker. */
    val english: List<ModelSpec>
      get() = entries.filter { it.lang == Lang.EN }

    /** The model for [lang]: English uses the chosen Parakeet quantization, Urdu uses Whisper. */
    fun forLang(lang: Lang, settings: Settings): ModelSpec =
      when (lang) {
        Lang.EN -> of(settings.modelId).takeIf { it.lang == Lang.EN } ?: Q5_K_M
        Lang.UR -> WHISPER_TURBO_Q5
      }

    /** The model for the current dictation language. */
    fun active(settings: Settings): ModelSpec = forLang(settings.language, settings)
  }
}

object ModelFiles {
  fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

  fun file(context: Context, spec: ModelSpec) = File(dir(context), spec.fileName)

  fun partFile(context: Context, spec: ModelSpec) = File(dir(context), spec.fileName + ".part")

  /** Present = fully downloaded and verified (unverified data only ever lives in the .part file). */
  fun isPresent(context: Context, spec: ModelSpec): Boolean = file(context, spec).let { it.isFile && it.length() == spec.sizeBytes }
}
