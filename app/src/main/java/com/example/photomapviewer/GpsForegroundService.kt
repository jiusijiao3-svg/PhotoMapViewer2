package com.example.photomapviewer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// --- フォアグラウンドサービス (スワイプキル対応 & API 34安全対策) ---
class GpsForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType", "NewApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "gps_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "GPS高精度測位", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PhotoMapViewer")
            .setContentText("GPS計測をバックグラウンドで継続しています")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } catch (e: Exception) {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    // スワイプしてタスクキルされた際のクリーンアップ処理
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putBoolean("gps_enabled", false).apply()
        stopSelf()
    }
}
