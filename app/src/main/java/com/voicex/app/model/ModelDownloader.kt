package com.voicex.app.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.voicex.app.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext

sealed interface DownloadState {
  data object Idle : DownloadState

  data object WaitingForWifi : DownloadState

  data class Downloading(val spec: ModelSpec, val bytes: Long, val total: Long, val bytesPerSec: Long) : DownloadState {
    val fraction: Float
      get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
  }

  data class Verifying(val spec: ModelSpec) : DownloadState

  data class Done(val spec: ModelSpec) : DownloadState

  data class Failed(val spec: ModelSpec, val message: String) : DownloadState
}

/**
 * Downloads a model into app-private storage (filesDir/models).
 *
 *  - Resume: bytes go to "<name>.part"; a retry sends `Range: bytes=<partSize>-` and appends on 206.
 *  - Wi-Fi only (setting): waits for an unmetered Wi-Fi/Ethernet network before starting or resuming.
 *  - Integrity: the finished file's SHA-256 must equal the hash pinned in [ModelSpec]; only then is the .part
 *    renamed to the final name, so a model on disk is always a verified one.
 */
class ModelDownloader(private val context: Context) {
  private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
  val state: StateFlow<DownloadState> = _state

  private val cm = context.getSystemService(ConnectivityManager::class.java)

  fun isOnWifi(): Boolean {
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
  }

  private suspend fun awaitWifi() {
    if (isOnWifi()) return
    _state.value = DownloadState.WaitingForWifi
    val ready = CompletableDeferred<Unit>()
    val cb =
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ready.complete(Unit)
        }
      }
    val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
    cm.registerNetworkCallback(req, cb)
    try {
      ready.await()
    } finally {
      runCatching { cm.unregisterNetworkCallback(cb) }
    }
  }

  /** Downloads [spec] (resuming any partial file). Returns true when the verified model is in place. */
  suspend fun download(spec: ModelSpec, wifiOnly: Boolean): Boolean =
    withContext(Dispatchers.IO) {
      try {
        downloadOnIo(spec, wifiOnly)
      } catch (e: CancellationException) {
        // paused: the .part file stays for the next resume. Set here, after the last progress update.
        _state.value = DownloadState.Idle
        throw e
      }
    }

  private suspend fun downloadOnIo(spec: ModelSpec, wifiOnly: Boolean): Boolean {
    val dest = ModelFiles.file(context, spec)
    if (ModelFiles.isPresent(context, spec)) {
      _state.value = DownloadState.Done(spec)
      return true
    }
    val part = ModelFiles.partFile(context, spec)
    var attempt = 0
    while (true) {
      coroutineContext.ensureActive()
      if (wifiOnly) awaitWifi()
      try {
        fetch(spec, part)
        break
      } catch (e: IOException) {
        attempt++
        AppLog.w(TAG, "download attempt $attempt failed: $e")
        if (attempt >= MAX_ATTEMPTS) {
          _state.value = DownloadState.Failed(spec, "Download interrupted (${e.message ?: e.javaClass.simpleName}). Tap Retry to resume.")
          return false
        }
        delay(minOf(30_000L, 2_000L * attempt * attempt))
      }
    }

    _state.value = DownloadState.Verifying(spec)
    val hash = sha256(part)
    if (!hash.equals(spec.sha256, ignoreCase = true)) {
      AppLog.e(TAG, "SHA-256 mismatch: got $hash want ${spec.sha256}")
      part.delete()
      _state.value = DownloadState.Failed(spec, "The download was corrupted (checksum mismatch) and has been deleted. Tap Retry.")
      return false
    }
    if (!part.renameTo(dest)) {
      _state.value = DownloadState.Failed(spec, "Could not save the model file.")
      return false
    }
    AppLog.ok(TAG, "${spec.fileName} downloaded and verified (sha256 $hash)")
    _state.value = DownloadState.Done(spec)
    return true
  }

  private suspend fun fetch(spec: ModelSpec, part: File) {
    var have = if (part.exists()) part.length() else 0L
    if (have > spec.sizeBytes) {
      part.delete()
      have = 0
    }
    if (have == spec.sizeBytes) return
    val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 20_000
      readTimeout = 30_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "VoiceX-Android/1.0")
      if (have > 0) setRequestProperty("Range", "bytes=$have-")
    }
    try {
      val code = conn.responseCode
      val append =
        when (code) {
          HttpURLConnection.HTTP_PARTIAL -> true
          HttpURLConnection.HTTP_OK -> false // server ignored the range: start over
          416 -> { // range not satisfiable: the .part is bad
            part.delete()
            throw IOException("server rejected resume; restarting")
          }
          else -> throw IOException("HTTP $code")
        }
      if (!append) have = 0
      AppLog.i(TAG, "GET ${spec.fileName}: HTTP $code, ${if (append) "resuming at $have" else "from start"}")
      FileOutputStream(part, append).use { out ->
        conn.inputStream.use { input ->
          val buf = ByteArray(256 * 1024)
          var bytes = have
          var lastEmit = 0L
          var windowStart = System.nanoTime()
          var windowBytes = 0L
          var rate = 0L
          while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            bytes += n
            windowBytes += n
            val now = System.nanoTime()
            if (now - windowStart > 1_000_000_000L) {
              rate = windowBytes * 1_000_000_000L / (now - windowStart)
              windowStart = now
              windowBytes = 0
            }
            if (now - lastEmit > 200_000_000L) {
              lastEmit = now
              _state.value = DownloadState.Downloading(spec, bytes, spec.sizeBytes, rate)
            }
          }
          out.fd.sync()
          if (bytes < spec.sizeBytes) throw IOException("connection closed at $bytes of ${spec.sizeBytes} bytes")
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  fun reset() {
    _state.value = DownloadState.Idle
  }

  /** Deletes a downloaded model (and any partial download). */
  fun delete(spec: ModelSpec) {
    ModelFiles.file(context, spec).delete()
    ModelFiles.partFile(context, spec).delete()
  }

  companion object {
    private const val TAG = "download"
    private const val MAX_ATTEMPTS = 6

    fun sha256(f: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      f.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          md.update(buf, 0, n)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
