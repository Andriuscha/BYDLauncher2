package com.ar.bydlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ar.bydlauncher.MainActivity
import com.ar.bydlauncher.R

/**
 * Anchor-service.
 *
 * Задача: держать процесс лаунчера живым, пока приложение в фоне
 * (пользователь ушёл в Яндекс.Навигатор, климат, настройки BYD).
 *
 * НЕ содержит бизнес-логики. BMS-цикл, GPS, погода и запись поездок
 * остаются в MainActivity и продолжают работать, пока процесс жив —
 * а его как раз и держит этот сервис.
 *
 * START_STICKY: если система всё же убьёт процесс, сервис перезапустится сам.
 * MainActivity поднимется системой при возврате на HOME (категория HOME).
 */
class BydBackgroundService : Service() {

    companion object {
        private const val TAG = "BydBackgroundService"

        private const val CHANNEL_ID = "byd_bg_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.ar.bydlauncher.action.START"
        const val ACTION_STOP = "com.ar.bydlauncher.action.STOP"

        fun start(context: Context) {
            val i = Intent(context, BydBackgroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, BydBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}, startId=$startId")

        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bg_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bg_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bg_notif_title))
            .setContentText(getString(R.string.bg_notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}