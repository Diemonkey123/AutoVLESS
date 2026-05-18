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
import com.autovless.app.util.DiagnosticsLogger
import java.net.HttpURLConnection
import java.net.URL

class AutoVlessVpnService : VpnService() {
    private var runtime: LibboxRuntime? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val manager = getSystemService(NotificationManager::class.java)
                return try {
                    startForeground(NOTIFICATION_ID, buildNotification("Запуск VPN..."))

                    val config = intent.getStringExtra(EXTRA_CONFIG)
                    if (config.isNullOrBlank()) {
                        DiagnosticsLogger.log(this, "VPN", "START_ABORTED empty config")
                        broadcastStatus("Ошибка: пустой VPN-конфиг")
                        stopVpn()
                        START_NOT_STICKY
                    } else {
                        runCatching { runtime?.close() }
                        runtime = null

                        val nextRuntime = LibboxRuntime(this)
                        nextRuntime.startVpn(this, config)
                        runtime = nextRuntime

                        DiagnosticsLogger.log(this, "VPN", "runtime started; self-test skipped because own package is excluded to prevent core socket loop")
                        broadcastStatus("VPN подключен")
                        manager.notify(NOTIFICATION_ID, buildNotification("VPN подключен"))
                        START_STICKY
                    }
                } catch (e: Throwable) {
                    DiagnosticsLogger.log(this, "VPN", "START_ERROR ${e.stackTraceToString()}")
                    broadcastStatus("Ошибка запуска VPN: ${e.message ?: e.javaClass.simpleName}")
                    runCatching { runtime?.close() }
                    runtime = null
                    runCatching { manager.notify(NOTIFICATION_ID, buildNotification("Ошибка запуска VPN")) }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                    }
                    stopSelf(startId)
                    START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun runVpnSelfTestAsync(manager: NotificationManager) {
        Thread {
            try {
                Thread.sleep(900)
                DiagnosticsLogger.log(this, "VPN", "SELF_TEST_TRY https://www.google.com/generate_204")
                val started = System.nanoTime()
                val connection = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "AutoVLESS-VPN-SelfTest")
                val code = connection.responseCode
                connection.disconnect()
                val ms = ((System.nanoTime() - started) / 1_000_000.0).toInt()
                DiagnosticsLogger.log(this, "VPN", "SELF_TEST_RESULT code=$code ms=$ms")
                val text = if (code in 200..299 || code == 204) "VPN подключен, интернет OK" else "VPN подключен"
                broadcastStatus(text)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            } catch (e: Throwable) {
                // This method is kept for diagnostics, but normal 1.5.9 startup skips it
                // because the app process is excluded from Android VPN to prevent libbox
                // outbound sockets from looping back into the tunnel.
                val message = e.message ?: e.javaClass.simpleName
                DiagnosticsLogger.log(this, "VPN", "SELF_TEST_FAIL $message")
                val text = "VPN запущен, но интернет через туннель не проходит"
                broadcastStatus(text)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }.start()
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

    private fun broadcastStatus(text: String) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, text)
        sendBroadcast(intent)
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
        const val ACTION_STATUS = "com.autovless.app.VPN_STATUS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATUS = "status"
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
