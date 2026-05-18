package com.autovless.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.autovless.app.MainActivity

class AutoVlessVpnService : VpnService() {
    private var runtime: LibboxRuntime? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Запуск VPN..."))
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopVpn()
                    return START_NOT_STICKY
                }

                try {
                    val nextRuntime = LibboxRuntime(this)
                    nextRuntime.startVpn(this, config)
                    runtime = nextRuntime
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification("VPN подключен"))
                } catch (e: Throwable) {
                    stopVpn()
                    throw e
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun stopVpn() {
        runCatching { runtime?.close() }
        runtime = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "autovless_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AutoVLESS VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AutoVlessVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("AutoVLESS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.autovless.app.START_VPN"
        const val ACTION_STOP = "com.autovless.app.STOP_VPN"
        const val EXTRA_CONFIG = "config"
        private const val NOTIFICATION_ID = 2108

        fun start(context: Context, configContent: String) {
            val intent = Intent(context, AutoVlessVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, configContent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AutoVlessVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
