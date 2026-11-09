package com.voicex.app.engine

/**
 * When to trust an accelerator (NPU / GPU) with a recording (unit-tested in AccelPolicyTest).
 *
 * Background: on the S23 Ultra the Hexagon NPU transcribed the 5.4 s benchmark clip correctly but returned an
 * empty transcript for a 30 s dictation. So accelerators are only trusted up to the longest clip the benchmark
 * verified them on, and any suspiciously empty result is redone on the CPU.
 */
object AccelPolicy {
  /** The timing clip's length: what an accelerator that passed only the old (short-only) benchmark is trusted for. */
  const val SHORT_CLIP_MS = 5_400L

  /** No limit: the accelerator passed the longest benchmark clip; the empty-output safety net still applies. */
  const val UNLIMITED = Long.MAX_VALUE

  /**
   * True when a transcript is implausibly short for speech of [audioMs]: empty, or under one character per second
   * for 4 s or more (real dictation is ~12-18 characters per second).
   */
  fun looksEmpty(text: String, audioMs: Long): Boolean {
    val n = text.trim().length
    if (n == 0) return true
    return audioMs >= 4_000 && n < audioMs / 1_000
  }

  /** Whether [kind], verified up to [verifiedMs], may take a recording of [audioMs]. CPU always may. */
  fun trusted(kind: EngineKind, audioMs: Long, verifiedMs: Long): Boolean = kind == EngineKind.CPU || audioMs <= verifiedMs

  /** Allowed word differences against the CPU for a long check clip: 10 % of the words, at least one. */
  fun tolerance(referenceWords: Int): Int = maxOf(1, referenceWords / 10)

  /**
   * How far a benchmarked accelerator is verified, from its long-clip checks (each null when that check didn't run
   * because an earlier one failed). [shortOk] = it passed the 5.4 s timing clip.
   */
  fun verifiedMs(shortOk: Boolean, ok11s: Boolean?, ok33s: Boolean?): Long =
    when {
      !shortOk -> 0L
      ok11s != true -> SHORT_CLIP_MS
      ok33s != true -> 11_000L
      else -> UNLIMITED
    }

  /** After an accelerator produced empty output for [audioMs], it is trusted only below that length. */
  fun lowered(currentMs: Long, failedAtMs: Long): Long = minOf(currentMs, (failedAtMs - 1).coerceAtLeast(0))
}
