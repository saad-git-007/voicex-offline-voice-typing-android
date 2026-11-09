package com.voicex.app.dictation

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.voicex.app.AppLog
import androidx.core.app.NotificationCompat
import com.voicex.app.Notifications
import com.voicex.app.R
import com.voicex.app.VoiceXApp

/**
 * Foreground service of type `microphone`, alive only while VoiceX records. It holds the ongoing "Listening"
 * notification (with Stop and Cancel) that Android requires; the recording itself is driven by
 * [DictationController].
 */
class DictationService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val controller = (application as VoiceXApp).dictation
    when (intent?.action) {
      ACTION_START -> {
        try {
          startForeground(Notifications.ID_DICTATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
          // Android 14+: a microphone FGS needs the app to be "in use" (Risk 1). SecurityException or
          // ForegroundServiceStartNotAllowedException land here.
          AppLog.w(TAG, "startForeground(microphone) refused: $e")
          stopSelf()
          controller.onForegroundRefused(e)
          return START_NOT_STICKY
        }
        controller.onForeground()
      }
      ACTION_STOP -> controller.stop()
      ACTION_CANCEL -> controller.cancel()
      else -> stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun notification(): Notification {
    fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, DictationService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, Notifications.CHANNEL_DICTATION)
      .setSmallIcon(R.drawable.ic_stat_voicex)
      .setContentTitle("VoiceX")
      .setContentText("Listening… tap the dot to finish")
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Stop", action(ACTION_STOP, 1))
      .addAction(0, "Cancel", action(ACTION_CANCEL, 2))
      .build()
  }

  companion object {
    private const val TAG = "micservice"
    const val ACTION_START = "com.voicex.app.START"
    const val ACTION_STOP = "com.voicex.app.STOP"
    const val ACTION_CANCEL = "com.voicex.app.CANCEL"

    fun stop(context: Context) {
      context.stopService(Intent(context, DictationService::class.java))
    }
  }
}
