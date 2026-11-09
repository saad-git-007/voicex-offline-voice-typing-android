package com.voicex.app.model

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voicex.app.Notifications
import com.voicex.app.R
import com.voicex.app.VoiceXApp
import com.voicex.app.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs the model download as a dataSync foreground service, so a 541 MB download survives the user leaving the
 * setup screen. Progress is mirrored in a notification.
 */
class ModelDownloadService : Service() {
  private var job: Job? = null
  private var watcher: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val app = application as VoiceXApp
    if (intent?.action == ACTION_CANCEL) {
      job?.cancel() // the downloader returns to Idle itself once the transfer has stopped
      stopSelf()
      return START_NOT_STICKY
    }
    val spec = intent?.getStringExtra(EXTRA_MODEL)?.let { ModelSpec.of(it) } ?: ModelSpec.active(app.settings)
    startForeground(Notifications.ID_DOWNLOAD, progressNotification("Starting download…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    if (job?.isActive == true) return START_NOT_STICKY

    watcher?.cancel()
    watcher = app.scope.launch {
      app.downloader.state.collectLatest { s ->
        val n =
          when (s) {
            is DownloadState.Downloading -> progressNotification("${s.spec.label}: ${(s.fraction * 100).toInt()} %", (s.fraction * 1000).toInt(), false)
            is DownloadState.Verifying -> progressNotification("Verifying checksum…", 0, true)
            DownloadState.WaitingForWifi -> progressNotification("Waiting for Wi-Fi…", 0, true)
            else -> null
          }
        if (n != null) runCatching { NotificationManagerCompat.from(this@ModelDownloadService).notify(Notifications.ID_DOWNLOAD, n) }
      }
    }
    job = app.scope.launch {
      try {
        app.downloader.download(spec, app.settings.wifiOnly)
      } finally {
        watcher?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    watcher?.cancel()
    super.onDestroy()
  }

  private fun progressNotification(text: String, permille: Int, indeterminate: Boolean): Notification {
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val cancel = PendingIntent.getService(this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOAD)
      .setSmallIcon(R.drawable.ic_stat_voicex)
      .setContentTitle("VoiceX")
      .setContentText(text)
      .setProgress(1000, permille, indeterminate)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(open)
      .addAction(0, "Cancel", cancel)
      .build()
  }

  companion object {
    const val EXTRA_MODEL = "model"
    const val ACTION_CANCEL = "com.voicex.app.CANCEL_DOWNLOAD"

    fun start(context: Context, spec: ModelSpec) {
      context.startForegroundService(Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_MODEL, spec.id))
    }
  }
}
