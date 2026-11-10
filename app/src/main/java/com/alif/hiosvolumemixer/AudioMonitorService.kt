package com.alif.hiosvolumemixer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class AudioMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            worker.execute {
                val prefs = getSharedPreferences("mixer", MODE_PRIVATE)
                val selected = prefs.getStringSet("selected_apps", emptySet()).orEmpty()
                val active = AudioSessionDetector.getActivePackages(applicationContext)
                selected.intersect(active.toSet()).forEach { pkg ->
                    val volume = prefs.getInt("volume_$pkg", 100).coerceIn(0, 100) / 100f
                    AudioSessionDetector.setPackageVolume(applicationContext, pkg, volume)
                }
                val display = if (active.isEmpty()) "Belum ada aplikasi aktif mengeluarkan audio."
                    else active.joinToString("\n")
                val status = when {
                    selected.isEmpty() -> "Pilih aplikasi terlebih dahulu."
                    active.isEmpty() -> "Mixer aktif • menunggu sesi audio"
                    else -> "Mixer aktif • ${active.size} aplikasi memakai audio"
                }
                getSharedPreferences("monitor", MODE_PRIVATE).edit()
                    .putString("status", status)
                    .putString("sessions", display)
                    .apply()
            }
            handler.postDelayed(this, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                "audio_monitor", "Pemantauan audio", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notification: Notification = NotificationCompat.Builder(this, "audio_monitor")
            .setContentTitle("HiOS Volume Mixer")
            .setContentText("Menerapkan volume independen aplikasi")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
        startForeground(41, notification)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        worker.shutdownNow()
        getSharedPreferences("monitor", MODE_PRIVATE).edit()
            .putString("status", "Pemantauan dihentikan")
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}