package com.voicex.app.model

import android.content.Context
import com.voicex.app.Settings
import java.io.File
import java.util.Locale

/**
 * The two dictation slots, each with its own model (see [ModelSpec.lang]): English (Parakeet) and a second language
 * transcribed by Whisper, chosen in Settings (default Urdu, [Settings.whisperLanguage]). [key] is what Settings
 * stores; "ur" predates the choice of language and is kept so existing installs keep their setting.
 */
enum class Lang(val key: String) {
  EN("en"),
  WHISPER("ur");

  companion object {
    fun of(key: String?) = entries.firstOrNull { it.key == key } ?: EN
  }
}

/** The languages Whisper large-v3 transcribes, minus English (which uses Parakeet). */
object WhisperLanguages {
  const val DEFAULT = "ur"

  /** Whisper's language codes, in its own order (openai/whisper, whisper/tokenizer.py), without "en". */
  val codes: List<String> =
    ("zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml " +
      "cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu " +
      "am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su yue").split(' ')

  // Whisper uses "jw" for Javanese; ISO 639 and Java's Locale use "jv"
  private fun locale(code: String): Locale = Locale.forLanguageTag(if (code == "jw") "jv" else code)

  fun englishName(code: String): String =
    when (code) {
      "yue" -> "Cantonese"
      "haw" -> "Hawaiian"
      else -> locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
    }

  /** The language's name in itself (اردو, Français, 中文); the English name when Java has none. */
  fun nativeName(code: String): String {
    val l = locale(code)
    val n = l.getDisplayLanguage(l)
    return if (n.isBlank() || n == code) englishName(code) else n.replaceFirstChar { it.titlecase(l) }
  }

  fun badge(code: String): String = code.uppercase(Locale.ROOT)

  /** "اردو · Urdu", "Français · French", or just "Hawaiian" when both names are the same. */
  fun displayName(code: String): String {
    val en = englishName(code)
    val nat = nativeName(code)
    return if (nat == en) en else "$nat · $en"
  }

  /** Sorted by English name, for the picker. */
  val sorted: List<String>
    get() = codes.sortedBy { englishName(it) }
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
    "OpenAI Whisper, for the second language (Urdu by default).",
    Lang.WHISPER,
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
        Lang.WHISPER -> WHISPER_TURBO_Q5
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
