package com.voicex.app.model

import android.content.Context
import java.io.File

/**
 * The two Parakeet Unified EN 0.6B quantizations VoiceX can use. Sizes and SHA-256 hashes are from the
 * Hugging Face LFS metadata of handy-computer/parakeet-unified-en-0.6b-gguf (checked 2026-11-03 against
 * downloaded copies).
 */
enum class ModelSpec(
  val id: String,
  val fileName: String,
  val sizeBytes: Long,
  val sha256: String,
  val label: String,
  val blurb: String,
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
  );

  val url: String
    get() = "https://huggingface.co/handy-computer/parakeet-unified-en-0.6b-gguf/resolve/main/$fileName"

  companion object {
    fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Q5_K_M
  }
}

object ModelFiles {
  fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

  fun file(context: Context, spec: ModelSpec) = File(dir(context), spec.fileName)

  fun partFile(context: Context, spec: ModelSpec) = File(dir(context), spec.fileName + ".part")

  /** Present = fully downloaded and verified (unverified data only ever lives in the .part file). */
  fun isPresent(context: Context, spec: ModelSpec): Boolean = file(context, spec).let { it.isFile && it.length() == spec.sizeBytes }
}
