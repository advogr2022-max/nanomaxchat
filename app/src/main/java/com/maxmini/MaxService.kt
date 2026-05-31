package com.maxmini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class MaxService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("maxmini", "MAX Lite", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)

            val notification = Notification.Builder(this, "maxmini")
                .setContentTitle("MAX Lite")
                .setContentText("Сервер работает на порту 8085")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build()
            startForeground(1, notification)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
