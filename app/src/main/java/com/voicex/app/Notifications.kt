package com.voicex.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
  const val CHANNEL_DICTATION = "dictation"
  const val CHANNEL_DOWNLOAD = "download"
  const val ID_DICTATION = 1
  const val ID_DOWNLOAD = 2

  fun createChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_DICTATION, "Dictation", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while VoiceX is using the microphone"
        setShowBadge(false)
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_DOWNLOAD, "Model download", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Progress of the speech model download"
        setShowBadge(false)
      },
    )
  }
}
