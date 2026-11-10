package com.alif.hiosvolumemixer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("monitor", MODE_PRIVATE)
            status.text = prefs.getString("status", "Pemantauan belum dimulai")
            list.text = prefs.getString("sessions", "Belum ada sesi audio terdeteksi.")
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
        }
        val title = TextView(this).apply {
            text = "HiOS Volume Mixer"
            textSize = 26f
            setTextColor(0xFF202124.toInt())
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        list = TextView(this).apply {
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        fun button(label: String, action: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { action() } }

        root.addView(title)
        root.addView(status)
        root.addView(button("Mulai deteksi audio") {
            ContextCompat.startForegroundService(this, Intent(this, AudioMonitorService::class.java))
        })
        root.addView(button("Minta izin Shizuku") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(2001)
            else status.text = "Shizuku belum berjalan. Aktifkan Shizuku terlebih dahulu."
        })
        root.addView(button("Periksa akses root") {
            Thread {
                val result = AudioSessionDetector.runRootProbe()
                runOnUiThread { status.text = result }
            }.start()
        })
        root.addView(list)
        setContentView(root)
        handler.post(refresh)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}
