package com.voicex.app.audio

import kotlin.math.sqrt

/** Pure PCM helpers (unit-tested in PcmUtilsTest). Audio is 16 kHz mono throughout. */
object PcmUtils {
  const val SAMPLE_RATE = 16_000

  /** Signed 16-bit PCM -> float in [-1, 1). */
  fun pcm16ToFloat(src: ShortArray, n: Int = src.size, dst: FloatArray = FloatArray(n), dstOffset: Int = 0): FloatArray {
    require(n <= src.size && dstOffset + n <= dst.size) { "buffer too small" }
    for (i in 0 until n) dst[dstOffset + i] = src[i] / 32768f
    return dst
  }

  /** Little-endian 16-bit PCM bytes -> float. */
  fun pcm16LeBytesToFloat(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): FloatArray {
    val n = length / 2
    val out = FloatArray(n)
    var p = offset
    for (i in 0 until n) {
      val s = ((bytes[p + 1].toInt() shl 8) or (bytes[p].toInt() and 0xFF)).toShort()
      out[i] = s / 32768f
      p += 2
    }
    return out
  }

  /** Clamps to [-1, 1] and replaces NaN/Inf with 0 (transcribe_run rejects non-finite samples). */
  fun sanitize(pcm: FloatArray, n: Int = pcm.size) {
    for (i in 0 until n) {
      val v = pcm[i]
      pcm[i] = if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(-1f, 1f)
    }
  }

  /** Root mean square of one frame. */
  fun rms(pcm: FloatArray, from: Int, to: Int): Float {
    if (to <= from) return 0f
    var sum = 0.0
    for (i in from until to) {
      val v = pcm[i].toDouble()
      sum += v * v
    }
    return sqrt(sum / (to - from)).toFloat()
  }

  /**
   * Finds the speech span by frame energy. Frames are [frameMs] long; a frame is "voiced" when its RMS is above
   * an adaptive threshold: [floorRms] or [noiseFactor] x the quietest 10 % of frames (the noise floor), capped at
   * [ceilRms] so a noisy room can't swallow speech. [padMs] of audio is kept on both sides so word onsets and
   * tails aren't clipped.
   *
   * @return the [start, end) sample range to keep, or null when no frame is voiced (nothing was said).
   */
  fun speechRange(
    pcm: FloatArray,
    n: Int = pcm.size,
    sampleRate: Int = SAMPLE_RATE,
    frameMs: Int = 20,
    padMs: Int = 250,
    floorRms: Float = 0.004f,
    ceilRms: Float = 0.03f,
    noiseFactor: Float = 3f,
  ): IntRange? {
    val frame = sampleRate * frameMs / 1000
    if (n < frame) return null
    val frames = n / frame
    val energies = FloatArray(frames) { rms(pcm, it * frame, (it + 1) * frame) }
    val sorted = energies.sortedArray()
    val noise = sorted[(frames / 10).coerceAtMost(frames - 1)]
    val threshold = maxOf(floorRms, minOf(ceilRms, noise * noiseFactor))

    var first = -1
    var last = -1
    for (i in 0 until frames) {
      if (energies[i] > threshold) {
        if (first < 0) first = i
        last = i
      }
    }
    if (first < 0) return null
    val pad = sampleRate * padMs / 1000
    val start = (first * frame - pad).coerceAtLeast(0)
    val end = ((last + 1) * frame + pad).coerceAtMost(n)
    return start until end
  }

  /** Copy of the voiced part of [pcm] (see [speechRange]), or null when there is no speech. */
  fun trimSilence(pcm: FloatArray, n: Int = pcm.size, sampleRate: Int = SAMPLE_RATE): FloatArray? {
    val r = speechRange(pcm, n, sampleRate) ?: return null
    return pcm.copyOfRange(r.first, r.last + 1)
  }

  fun samplesToMs(samples: Int, sampleRate: Int = SAMPLE_RATE): Long = samples * 1000L / sampleRate
}
