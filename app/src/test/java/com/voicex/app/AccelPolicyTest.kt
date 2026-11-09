package com.voicex.app

import com.voicex.app.engine.AccelPolicy
import com.voicex.app.engine.BenchEntry
import com.voicex.app.engine.BenchmarkResult
import com.voicex.app.engine.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelPolicyTest {
  @Test fun emptyOrNearEmptyIsSuspicious() {
    assertTrue(AccelPolicy.looksEmpty("", 30_480)) // the S23 NPU case
    assertTrue(AccelPolicy.looksEmpty("   ", 2_000))
    assertTrue(AccelPolicy.looksEmpty("a", 30_000)) // < 1 char per second
    assertFalse(AccelPolicy.looksEmpty("Yes.", 1_500)) // short answers are fine
    assertFalse(AccelPolicy.looksEmpty("And so, my fellow Americans, ask not.", 5_400))
  }

  @Test fun routing() {
    assertTrue(AccelPolicy.trusted(EngineKind.CPU, 900_000, 0))
    assertTrue(AccelPolicy.trusted(EngineKind.NPU, 5_000, AccelPolicy.SHORT_CLIP_MS))
    assertFalse(AccelPolicy.trusted(EngineKind.NPU, 30_480, AccelPolicy.SHORT_CLIP_MS))
    assertTrue(AccelPolicy.trusted(EngineKind.NPU, 600_000, AccelPolicy.UNLIMITED))
  }

  @Test fun verifiedLengthFromChecks() {
    assertEquals(0L, AccelPolicy.verifiedMs(false, null, null))
    assertEquals(AccelPolicy.SHORT_CLIP_MS, AccelPolicy.verifiedMs(true, false, null))
    assertEquals(11_000L, AccelPolicy.verifiedMs(true, true, false))
    assertEquals(AccelPolicy.UNLIMITED, AccelPolicy.verifiedMs(true, true, true))
  }

  @Test fun loweringAfterEmptyOutput() {
    assertEquals(30_479L, AccelPolicy.lowered(AccelPolicy.UNLIMITED, 30_480))
    assertEquals(5_400L, AccelPolicy.lowered(5_400, 30_480))
  }

  @Test fun tolerance() {
    assertEquals(1, AccelPolicy.tolerance(7))
    assertEquals(2, AccelPolicy.tolerance(22))
    assertEquals(6, AccelPolicy.tolerance(66))
  }

  @Test fun oldBenchmarkResultsTrustAcceleratorsOnlyForTheShortClip() {
    // JSON saved by the previous build (no maxOkMs): the S23's NPU result must not be trusted with 30 s audio
    val old = """{"model":"Q5_K_M","reference":"r","winner":"NPU","ts":1,"entries":[
      {"kind":"CPU","device":"CPU","ms":742,"text":"r","correct":true,"error":""},
      {"kind":"NPU","device":"HTP0","ms":444,"text":"r","correct":true,"error":""}]}"""
    val r = BenchmarkResult.fromJson(old)!!
    assertEquals(AccelPolicy.UNLIMITED, r.entries.first { it.kind == EngineKind.CPU }.maxOkMs)
    assertEquals(AccelPolicy.SHORT_CLIP_MS, r.entries.first { it.kind == EngineKind.NPU }.maxOkMs)
    // and the new field round-trips
    val n = BenchmarkResult("Q5_K_M", "r", listOf(BenchEntry(EngineKind.NPU, "HTP0", 444, "r", true, null, 11_000L)), EngineKind.NPU, 2)
    assertEquals(11_000L, BenchmarkResult.fromJson(n.toJson())!!.entries[0].maxOkMs)
  }
}
