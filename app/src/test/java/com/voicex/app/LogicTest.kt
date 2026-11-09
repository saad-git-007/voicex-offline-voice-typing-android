package com.voicex.app

import com.voicex.app.dot.DotPlacement
import com.voicex.app.engine.CpuTopology
import com.voicex.app.engine.Device
import com.voicex.app.engine.EngineKind
import com.voicex.app.engine.WavReader
import com.voicex.app.engine.WordDiff
import com.voicex.app.output.TextInsert
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {
  private val ref = "And so, my fellow Americans, ask not."

  @Test fun normalisesCaseAndPunctuation() = assertEquals(listOf("and", "so", "my", "fellow", "americans", "ask", "not"), WordDiff.words(ref))

  @Test fun exactAndOneWordAreCorrect() {
    assertTrue(WordDiff.matches(ref, "and so my fellow americans ask not"))
    assertTrue(WordDiff.matches(ref, "And so my fellow American, ask not.")) // 1 substitution
    assertTrue(WordDiff.matches(ref, "So, my fellow Americans, ask not.")) // 1 deletion
  }

  @Test fun twoWordsOffIsWrong() {
    assertFalse(WordDiff.matches(ref, "And so my yellow American ask not"))
    assertFalse(WordDiff.matches(ref, "")) // empty never counts
  }

  @Test fun distance() {
    assertEquals(0, WordDiff.distance("a b c", "A, b; c!"))
    assertEquals(2, WordDiff.distance("a b c", "a"))
    assertEquals(1, WordDiff.distance("don't stop", "dont stop"))
  }
}

class DotPlacementTest {
  private val w = 1080
  private val h = 2316
  private val size = 132
  private val m = 33

  @Test fun defaultIsRightEdgeAboveKeyboard() {
    val p = DotPlacement.place(kbTop = 1400, screenW = w, screenH = h, sizePx = size, marginPx = m, saved = null)
    assertEquals(w - size - m, p.x)
    assertEquals(1400 - size - m, p.y)
  }

  @Test fun savedOffsetFollowsKeyboardTop() {
    val saved = DotPlacement.offsetFor(x = 100, y = 1200, kbTop = 1400) // 200 px above the keyboard
    assertEquals(100 to 200, saved)
    val p = DotPlacement.place(kbTop = 1600, screenW = w, screenH = h, sizePx = size, marginPx = m, saved = saved)
    assertEquals(DotPlacement.Pos(100, 1400), p)
  }

  @Test fun clampedOnScreen() {
    assertEquals(DotPlacement.Pos(0, 0), DotPlacement.clamp(-50, -80, w, h, size))
    assertEquals(DotPlacement.Pos(w - size, h - size), DotPlacement.clamp(5000, 9000, w, h, size))
    val p = DotPlacement.place(kbTop = 100, screenW = w, screenH = h, sizePx = size, marginPx = m, saved = 2000 to 900)
    assertEquals(DotPlacement.Pos(w - size, 0), p)
  }
}

class TextInsertTest {
  @Test fun insertsAtCursor() = assertEquals(TextInsert.Result("Hello world there", 12), TextInsert.insert("Hello there", 6, 6, "world "))

  @Test fun replacesSelection() = assertEquals(TextInsert.Result("I like tea", 10), TextInsert.insert("I like coffee", 7, 13, "tea"))

  @Test fun reversedSelection() = assertEquals(TextInsert.Result("I like tea", 10), TextInsert.insert("I like coffee", 13, 7, "tea"))

  @Test fun noCursorAppends() = assertEquals(TextInsert.Result("abc def", 7), TextInsert.insert("abc", -1, -1, " def"))

  @Test fun emptyField() = assertEquals(TextInsert.Result("hi", 2), TextInsert.insert("", 0, 0, "hi"))

  @Test fun outOfRangeSelectionIsSafe() = assertEquals(TextInsert.Result("abcX", 4), TextInsert.insert("abc", 10, 12, "X"))
}

class EngineBitsTest {
  @Test fun classifiesDevices() {
    assertEquals(EngineKind.NPU, Device.parse("1\tHTP0\tHexagon\tgpu\tgpu")!!.engine)
    assertEquals(EngineKind.GPU_OPENCL, Device.parse("2\tGPUOpenCL\tQUALCOMM Adreno(TM) 740\tgpu\tgpu")!!.engine)
    assertEquals(EngineKind.GPU_VULKAN, Device.parse("3\tVulkan0\tAdreno (TM) 740\tvulkan\tgpu")!!.engine)
    assertEquals(EngineKind.CPU, Device.parse("0\tCPU\tARMv8\tcpu\tcpu")!!.engine)
    assertEquals(null, Device.parse("4\tBLAS\t\taccel\taccel")!!.engine)
    assertEquals(null, Device.parse("garbage"))
  }

  @Test fun fastestCoresS23Layout() {
    // Snapdragon 8 Gen 2: cpu0-2 A510 (cap 404), cpu3-4 A715 (cap 871), cpu5-6 A710 (cap 869), cpu7 X3 (cap 1024)
    val cores = listOf(404, 404, 404, 871, 871, 869, 869, 1024).mapIndexed { i, c -> CpuTopology.Core(i, c.toLong(), 0) }
    assertArrayEquals(intArrayOf(7, 4, 3, 6), CpuTopology.fastest(4, cores))
  }

  @Test fun fastestCoresFallsBackToFrequency() {
    val cores = listOf(1800, 1800, 2800, 3200).mapIndexed { i, f -> CpuTopology.Core(i, 0, f.toLong()) }
    assertArrayEquals(intArrayOf(3, 2), CpuTopology.fastest(2, cores))
  }

  @Test fun readsBundledBenchmarkClip() {
    val pcm = File("src/main/assets/bench/jfk-5s.wav").inputStream().let { WavReader.readMono16k(it) }
    assertEquals(86400, pcm.size) // 5.4 s at 16 kHz
    assertTrue(pcm.all { it >= -1f && it < 1f })
    assertTrue(pcm.any { kotlin.math.abs(it) > 0.1f })
  }
}
