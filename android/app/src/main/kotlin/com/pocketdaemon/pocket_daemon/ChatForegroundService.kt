package com.pocketdaemon.pocket_daemon

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class ChatForegroundService : Service() {

    companion object {
        private const val TAG = "ChatForegroundService"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_DISCONNECT = "com.pocketdaemon.pocket_daemon.ACTION_DISCONNECT_CHAT"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "Disconnect action received from notification")
            PocketDaemonApp.instance?.sendAction(ACTION_DISCONNECT)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground started")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ChatForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, PocketDaemonApp.CHANNEL_ACTIVE_CHAT).apply {
            setContentTitle("PocketDaemon")
            setContentText("Chat session active")
            setSmallIcon(android.R.drawable.ic_btn_speak_now)
            setContentIntent(contentIntent)
            setOngoing(true)
            addAction(Notification.Action.Builder(
                null, "Disconnect", disconnectIntent
            ).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }.build()
    }
}
