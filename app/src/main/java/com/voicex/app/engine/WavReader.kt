package com.voicex.app.engine

import com.voicex.app.audio.PcmUtils
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader for the bundled benchmark clip: 16-bit or float32 PCM, mono, 16 kHz. */
object WavReader {
  fun readMono16k(input: InputStream): FloatArray {
    val bytes = input.use { it.readBytes() }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var p = 12
    var format = 0
    var channels = 0
    var rate = 0
    var bits = 0
    while (p + 8 <= bytes.size) {
      val id = String(bytes, p, 4)
      val size = bb.getInt(p + 4)
      val body = p + 8
      when (id) {
        "fmt " -> {
          format = bb.getShort(body).toInt() and 0xFFFF
          channels = bb.getShort(body + 2).toInt()
          rate = bb.getInt(body + 4)
          bits = bb.getShort(body + 14).toInt()
        }
        "data" -> {
          require(channels == 1 && rate == PcmUtils.SAMPLE_RATE) { "need 16 kHz mono, got $rate Hz x $channels" }
          val len = minOf(size, bytes.size - body)
          return when {
            format == 1 && bits == 16 -> PcmUtils.pcm16LeBytesToFloat(bytes, body, len)
            format == 3 && bits == 32 -> FloatArray(len / 4) { bb.getFloat(body + it * 4) }
            else -> error("unsupported WAV format $format/$bits-bit")
          }
        }
      }
      p = body + size + (size and 1)
    }
    error("WAV has no data chunk")
  }
}
