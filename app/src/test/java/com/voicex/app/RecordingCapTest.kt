package com.voicex.app

import com.voicex.app.audio.RecordingCap
import com.voicex.app.audio.RecordingCap.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCapTest {
  @Test fun defaultFiveMinutes_phases() {
    val cap = RecordingCap(300)
    assertEquals(Phase.NORMAL, cap.phase(0))
    assertEquals(Phase.NORMAL, cap.phase(289_999))
    assertEquals(Phase.WARNING, cap.phase(290_000)) // 10 s before the cap
    assertEquals(Phase.WARNING, cap.phase(299_999))
    assertEquals(Phase.STOP, cap.phase(300_000))
    assertEquals(Phase.STOP, cap.phase(1_000_000))
  }

  @Test fun clampedToSliderRange() {
    assertEquals(30, RecordingCap(5).capSeconds)
    assertEquals(900, RecordingCap(10_000).capSeconds)
    assertEquals(450, RecordingCap(450).capSeconds)
  }

  @Test fun bufferSizedForCap() {
    assertEquals(30 * 16000, RecordingCap(30).capSamples)
    assertEquals(900 * 16000, RecordingCap(900).capSamples)
  }

  @Test fun samplePhases() {
    val cap = RecordingCap(30)
    assertEquals(Phase.NORMAL, cap.phaseForSamples(19 * 16000))
    assertEquals(Phase.WARNING, cap.phaseForSamples(20 * 16000))
    assertEquals(Phase.STOP, cap.phaseForSamples(30 * 16000))
  }

  @Test fun remaining() {
    val cap = RecordingCap(60)
    assertEquals(15_000, cap.remainingMs(45_000))
    assertEquals(0, cap.remainingMs(70_000))
  }

  @Test fun format() {
    assertEquals("0:00", RecordingCap.formatElapsed(0))
    assertEquals("0:09", RecordingCap.formatElapsed(9_999))
    assertEquals("1:05", RecordingCap.formatElapsed(65_000))
    assertEquals("15:00", RecordingCap.formatElapsed(900_000))
  }
}
