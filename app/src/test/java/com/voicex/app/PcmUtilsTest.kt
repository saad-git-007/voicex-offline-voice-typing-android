package com.voicex.app

import com.voicex.app.audio.PcmUtils
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmUtilsTest {
  private val rate = PcmUtils.SAMPLE_RATE

  @Test fun pcm16ToFloat_scalesFullRange() {
    val out = PcmUtils.pcm16ToFloat(shortArrayOf(0, 16384, -16384, Short.MAX_VALUE, Short.MIN_VALUE))
    assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f), out, 1e-7f)
  }

  @Test fun pcm16ToFloat_writesAtOffset() {
    val dst = FloatArray(5) { 9f }
    PcmUtils.pcm16ToFloat(shortArrayOf(16384, -16384, 1), n = 2, dst = dst, dstOffset = 2)
    assertArrayEquals(floatArrayOf(9f, 9f, 0.5f, -0.5f, 9f), dst, 0f)
  }

  @Test fun littleEndianBytes() {
    // 0x4000 = 16384 -> 0.5 ; 0xC000 = -16384 -> -0.5
    val out = PcmUtils.pcm16LeBytesToFloat(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()))
    assertArrayEquals(floatArrayOf(0.5f, -0.5f), out, 1e-7f)
  }

  @Test fun sanitize_clampsAndRemovesNonFinite() {
    val a = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1.5f, -2f, 0.25f)
    PcmUtils.sanitize(a)
    assertArrayEquals(floatArrayOf(0f, 0f, 1f, -1f, 0.25f), a, 0f)
  }

  private fun tone(seconds: Double, amp: Float) = FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toFloat() }

  private fun noise(seconds: Double, amp: Float, seed: Long = 1): FloatArray {
    val r = java.util.Random(seed)
    return FloatArray((seconds * rate).toInt()) { ((r.nextFloat() * 2 - 1) * amp) }
  }

  @Test fun pureSilence_isNothingHeard() {
    assertNull(PcmUtils.speechRange(FloatArray(rate * 2)))
    assertNull(PcmUtils.trimSilence(noise(2.0, 0.001f)))
  }

  @Test fun trim_keepsSpeechPlusPadding() {
    val pcm = noise(1.0, 0.001f) + tone(0.5, 0.3f) + noise(1.0, 0.001f, 2)
    val r = PcmUtils.speechRange(pcm)!!
    // speech is samples [16000, 24000); pad is 250 ms = 4000 samples each side
    assertEquals(16000 - 4000, r.first)
    assertEquals(24000 + 4000, r.last + 1)
    val trimmed = PcmUtils.trimSilence(pcm)!!
    assertEquals(16000, trimmed.size)
  }

  @Test fun trim_neverCutsInsideSpeech() {
    // two words with a 600 ms pause between them: one range covering both
    val pcm = noise(0.5, 0.001f) + tone(0.3, 0.2f) + noise(0.6, 0.001f, 3) + tone(0.3, 0.2f) + noise(0.5, 0.001f, 4)
    val r = PcmUtils.speechRange(pcm)!!
    assertTrue(r.first <= 8000 && r.last >= 8000 + 4800 + 9600 + 4800 - 1)
  }

  @Test fun trim_adaptsToNoisyRoom() {
    // steady room noise at RMS ~0.006 must not count as speech by itself...
    assertNull(PcmUtils.speechRange(noise(2.0, 0.01f)))
    // ...but speech over it still does
    val pcm = noise(1.0, 0.01f) + tone(0.5, 0.25f) + noise(1.0, 0.01f, 5)
    val r = PcmUtils.speechRange(pcm)!!
    assertTrue(r.first in 11000..16000 && r.last in 24000..29000)
  }

  @Test fun quietSpeakerStillDetected() {
    val pcm = noise(1.0, 0.0005f) + tone(0.4, 0.02f) + noise(1.0, 0.0005f, 6)
    assertNotNull(PcmUtils.speechRange(pcm))
  }

  @Test fun samplesToMs() = assertEquals(1500L, PcmUtils.samplesToMs(24000))
}
