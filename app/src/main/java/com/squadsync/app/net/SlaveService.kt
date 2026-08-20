package com.squadsync.app.net

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.squadsync.app.R
import com.squadsync.app.model.AppPrefs
import com.squadsync.app.ui.MainActivity

/**
 * Foreground service that owns the WS server lifecycle on a slave device so the
 * OS keeps the process alive while other apps are foregrounded. It also registers
 * the service via NSD so masters can discover it without typing IP addresses.
 */
class SlaveService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.squadsync.app.START_SLAVE"
        const val ACTION_STOP = "com.squadsync.app.STOP_SLAVE"
        const val EXTRA_PORT = "port"

        fun start(ctx: Context, port: Int) {
            val i = Intent(ctx, SlaveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SlaveService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val server by lazy { SlaveServer(this) }
    private val nsd by lazy { NsdController(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                server.stop()
                nsd.unregister()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 7878) ?: 7878
                startForeground(NOTIF_ID, buildNotification())
                server.start(port)
                nsd.register(port)
                AppPrefs.lastWsPort = port
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, AppPrefs.roomCode))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        server.stop()
        nsd.unregister()
        super.onDestroy()
    }
}