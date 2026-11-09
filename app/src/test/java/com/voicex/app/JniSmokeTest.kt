package com.voicex.app

import com.voicex.app.engine.TranscribeNative
import com.voicex.app.engine.WavReader
import com.voicex.app.engine.WordDiff
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * JNI smoke test on the build machine: the same voicex_jni.cpp, compiled for the host against a host build of
 * transcribe.cpp, loaded into this JVM and run on the bundled clip. Skipped unless pointed at the pieces:
 *
 *   ./gradlew :app:testDebugUnitTest -Dvoicex.hostLib=/abs/libvoicex_jni.so -Dvoicex.model=/abs/model.gguf
 *
 * (scripts/host-jni-test.sh builds the host library and runs this.)
 */
class JniSmokeTest {
  private val lib = System.getProperty("voicex.hostLib")
  private val model = System.getProperty("voicex.model")

  @Test fun transcribesBundledClipThroughJni() {
    assumeTrue("set -Dvoicex.hostLib and -Dvoicex.model to run", lib != null && model != null)
    TranscribeNative.load(lib)
    assertEquals(TranscribeNative.OK, TranscribeNative.nativeInitBackends(null))
    val devices = TranscribeNative.nativeDevices()
    assertTrue("a CPU device is registered: ${devices.toList()}", devices.any { it.split('\t')[4] == "cpu" })

    // error path: bad file -> -status, not a crash
    val out = LongArray(1)
    assertEquals(TranscribeNative.ERR_FILE_NOT_FOUND, TranscribeNative.nativeOpen("/nonexistent/model.gguf", -1, 4, out))
    assertEquals(0L, out[0])

    val st = TranscribeNative.nativeOpen(model!!, -1, 4, out)
    assertEquals("open failed: ${TranscribeNative.nativeStatusString(st)}", TranscribeNative.OK, st)
    val h = out[0]
    assertTrue(h != 0L) // may be negative on arm64 (tagged pointers)
    try {
      assertEquals("CPU", TranscribeNative.nativeBackend(h))
      val pcm = File("src/main/assets/bench/jfk-5s.wav").inputStream().let { WavReader.readMono16k(it) }
      val st = TranscribeNative.nativeRun(h, pcm, pcm.size)
      assertEquals(TranscribeNative.nativeStatusString(st), TranscribeNative.OK, st)
      val text = TranscribeNative.nativeText(h).toString(Charsets.UTF_8)
      println("JNI transcript: \"$text\"  timings(ms)=${TranscribeNative.nativeTimings(h).toList()}")
      assertTrue(text, WordDiff.matches("And so, my fellow Americans, ask not.", text))
      // invalid input is rejected cleanly
      assertEquals(1 /* INVALID_ARG */, TranscribeNative.nativeRun(h, pcm, 0))
    } finally {
      TranscribeNative.nativeClose(h)
    }
  }
}
