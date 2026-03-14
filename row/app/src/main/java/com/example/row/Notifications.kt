package com.example.row

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {

    const val CHANNEL_ONGOING = "syncrow_recording"
    const val CHANNEL_EVENTS = "syncrow_events_v2"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Quiet channel for foreground service (required by Android)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "SyncRow Recording",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        // HEADS-UP channel for start/end/sync info
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "SyncRow Updates",
                NotificationManager.IMPORTANCE_HIGH   // <-- THIS is the key
            ).apply {
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }
}
