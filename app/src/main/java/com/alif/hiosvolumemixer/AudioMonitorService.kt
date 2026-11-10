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

class AudioMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Thread {
                val dump = AudioSessionDetector.readDump()
                val sessions = AudioSessionDetector.parseActiveSessions(dump, packageManager)
                val display = if (sessions.isEmpty()) "Belum ada aplikasi yang terdeteksi mengeluarkan audio."
                else sessions.joinToString("\n\n") { "• ${it.packageName}\n  ${it.state}" }
                val access = when {
                    dump.isBlank() -> "Tidak dapat membaca status audio. Periksa izin Shizuku/root."
                    else -> "Pemantauan aktif • prioritas sesi audio foreground & background"
                }
                getSharedPreferences("monitor", MODE_PRIVATE).edit()
                    .putString("status", access)
                    .putString("sessions", display)
                    .apply()
            }.start()
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
            .setContentText("Memantau aplikasi yang mengeluarkan audio")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
        startForeground(41, notification)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        getSharedPreferences("monitor", MODE_PRIVATE).edit()
            .putString("status", "Pemantauan dihentikan")
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
