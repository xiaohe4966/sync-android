package com.squadsync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.squadsync.app.model.AppPrefs

class SquadSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                getString(R.string.notification_channel_id),
                "SquadSync 后台服务",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }
}