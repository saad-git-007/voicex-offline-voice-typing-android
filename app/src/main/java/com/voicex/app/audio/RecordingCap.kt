package com.voicex.app.audio

/**
 * The recording safety cap (unit-tested in RecordingCapTest). Recording auto-stops at [capSeconds] and the dot
 * shows a warning pulse for the last [warnSeconds].
 */
class RecordingCap(capSeconds: Int, private val warnSeconds: Int = 10) {
  val capSeconds: Int = capSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

  val capSamples: Int
    get() = capSeconds * PcmUtils.SAMPLE_RATE

  enum class Phase { NORMAL, WARNING, STOP }

  fun phase(elapsedMs: Long): Phase =
    when {
      elapsedMs >= capSeconds * 1000L -> Phase.STOP
      elapsedMs >= (capSeconds - warnSeconds).coerceAtLeast(0) * 1000L -> Phase.WARNING
      else -> Phase.NORMAL
    }

  fun phaseForSamples(samples: Int): Phase = phase(PcmUtils.samplesToMs(samples))

  /** Milliseconds left before the auto-stop, never negative. */
  fun remainingMs(elapsedMs: Long): Long = (capSeconds * 1000L - elapsedMs).coerceAtLeast(0)

  companion object {
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 15 * 60

    /** "m:ss" for the dot's elapsed-time label. */
    fun formatElapsed(ms: Long): String {
      val s = (ms / 1000).coerceAtLeast(0)
      return "%d:%02d".format(s / 60, s % 60)
    }
  }
}
