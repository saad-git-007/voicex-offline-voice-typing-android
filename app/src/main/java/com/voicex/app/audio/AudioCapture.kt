package com.voicex.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.voicex.app.AppLog

/**
 * Records 16 kHz mono audio from [MediaRecorder.AudioSource.VOICE_RECOGNITION] into a buffer preallocated for the
 * whole cap, so a long dictation never reallocates or copies while recording.
 *
 * ENCODING_PCM_FLOAT is used when the device supports it; otherwise 16-bit PCM is converted to float.
 * Reads happen on a dedicated thread; [onCapReached] fires (on that thread) when the buffer is full.
 */
class AudioCapture(private val cap: RecordingCap, private val onCapReached: () -> Unit) {
  private val buffer = FloatArray(cap.capSamples)

  @Volatile private var written = 0
  @Volatile private var running = false
  private var record: AudioRecord? = null
  private var thread: Thread? = null

  /** Samples captured so far. */
  val samples: Int
    get() = written

  var usingFloat = false
    private set

  /** The live AudioRecord, e.g. to check whether the system silenced it. */
  val audioRecord: AudioRecord?
    get() = record

  /** @throws IllegalStateException when the microphone can't be opened. */
  @SuppressLint("MissingPermission") // checked by the caller before starting the service
  fun start() {
    check(!running) { "already recording" }
    val rate = PcmUtils.SAMPLE_RATE
    val ch = AudioFormat.CHANNEL_IN_MONO
    val floatMin = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_FLOAT)
    var rec: AudioRecord? = null
    if (floatMin > 0) {
      rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, ch, AudioFormat.ENCODING_PCM_FLOAT, maxOf(floatMin, rate * 4 / 5)) }.getOrNull()
      if (rec?.state != AudioRecord.STATE_INITIALIZED) {
        rec?.release()
        rec = null
      }
    }
    usingFloat = rec != null
    if (rec == null) {
      val shortMin = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
      check(shortMin > 0) { "16 kHz mono capture is not supported on this device" }
      rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, ch, AudioFormat.ENCODING_PCM_16BIT, maxOf(shortMin, rate * 2 / 5))
      if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        throw IllegalStateException("microphone could not be opened")
      }
    }
    AppLog.i(TAG, "recording: ${if (usingFloat) "PCM_FLOAT" else "PCM_16BIT -> float"}, cap ${cap.capSeconds}s")
    record = rec
    written = 0
    rec.startRecording()
    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      rec.release()
      record = null
      throw IllegalStateException("microphone is in use by another app")
    }
    running = true
    thread = Thread({ readLoop(rec) }, "voicex-mic").apply { start() }
  }

  private fun readLoop(rec: AudioRecord) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val chunk = PcmUtils.SAMPLE_RATE / 10 // 100 ms
    val shorts = if (usingFloat) null else ShortArray(chunk)
    var capHit = false
    while (running) {
      val room = buffer.size - written
      if (room <= 0) {
        capHit = true
        break
      }
      val want = minOf(chunk, room)
      val got =
        if (shorts == null) {
          rec.read(buffer, written, want, AudioRecord.READ_BLOCKING)
        } else {
          val r = rec.read(shorts, 0, want, AudioRecord.READ_BLOCKING)
          if (r > 0) PcmUtils.pcm16ToFloat(shorts, r, buffer, written)
          r
        }
      if (got < 0) {
        AppLog.w(TAG, "AudioRecord.read error $got")
        break
      }
      written += got
    }
    if (capHit && running) onCapReached()
  }

  /** Stops recording and returns the captured audio (a copy, sanitized), or an empty array. */
  fun stop(): FloatArray {
    running = false
    record?.let { r ->
      runCatching { r.stop() }
      thread?.join(500)
      r.release()
    }
    record = null
    thread = null
    val n = written
    val out = buffer.copyOf(n)
    PcmUtils.sanitize(out)
    return out
  }

  /** Stops and drops the audio. */
  fun cancel() {
    stop()
    written = 0
  }

  companion object {
    private const val TAG = "audio"
  }
}
