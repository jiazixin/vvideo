package dev.vvideo.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

// Only the separate test APK needs this: some OEMs freeze a background
// instrumentation process even while it controls a foreground target app.
class BenchmarkKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = "vvideo-benchmark"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                channel,
                "VVideo device validation",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        startForeground(
            1, Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("VVideo device validation")
                .setContentText("Measuring the foreground app; ends with the test")
                .setOngoing(true).build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
