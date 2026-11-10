package com.alif.hiosvolumemixer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var appList: LinearLayout
    private val prefs by lazy { getSharedPreferences("mixer", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val refresh = object : Runnable {
        override fun run() {
            applySavedVolumes()
            handler.postDelayed(this, 3000)
        }
    }

    private fun selected(): MutableSet<String> =
        prefs.getStringSet("selected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun volume(packageName: String): Int = prefs.getInt("volume_$packageName", 100).coerceIn(0, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        buildUi()
        renderSelectedApps()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        val title = TextView(this).apply {
            text = "HiOS Volume Mixer"
            textSize = 26f
            setTextColor(0xFF202124.toInt())
        }
        status = TextView(this).apply {
            text = "Pilih aplikasi, lalu hubungkan Shizuku."
            textSize = 14f
            setPadding(0, 12, 0, 16)
        }
        fun button(label: String, action: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { action() } }

        content.addView(title)
        content.addView(status)
        content.addView(button("Pilih aplikasi") { showAppPicker() })
        content.addView(button("Hubungkan / minta izin Shizuku") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(2001)
            else status.text = "Shizuku belum berjalan. Aktifkan Shizuku terlebih dahulu."
        })
        content.addView(button("Aktifkan kontrol dari tombol volume") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(button("Mulai layanan pemantauan") {
            ContextCompat.startForegroundService(this, Intent(this, AudioMonitorService::class.java))
            status.text = "Layanan pemantauan dimulai."
        })
        content.addView(TextView(this).apply {
            text = "Volume aplikasi pilihan"
            textSize = 19f
            setPadding(0, 22, 0, 8)
            setTextColor(0xFF202124.toInt())
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appList)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun showAppPicker() {
        val apps = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != packageName }
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        } catch (_: Exception) { emptyList() }
        val labels = apps.map { "${packageManager.getApplicationLabel(it)}  (${it.packageName})" }.toTypedArray()
        val current = selected()
        val checked = BooleanArray(apps.size) { apps[it].packageName in current }
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi untuk mixer")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].packageName
                if (isChecked) current.add(pkg) else current.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putStringSet("selected_apps", current).apply()
                renderSelectedApps()
                applySavedVolumes()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderSelectedApps() {
        appList.removeAllViews()
        val chosen = selected()
        if (chosen.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "Belum ada aplikasi dipilih."
                textSize = 14f
                setPadding(0, 8, 0, 12)
            })
            return
        }
        chosen.sorted().forEach { pkg ->
            val label = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 12)
            }
            val heading = TextView(this).apply {
                text = "$label  •  ${volume(pkg)}%"
                textSize = 16f
                setTextColor(0xFF202124.toInt())
            }
            val slider = SeekBar(this).apply {
                max = 100
                progress = volume(pkg)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        heading.text = "$label  •  $value%"
                        if (fromUser) prefs.edit().putInt("volume_$pkg", value).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { applyOne(pkg) }
                })
            }
            row.addView(heading)
            row.addView(slider)
            appList.addView(row)
        }
    }

    private fun applyOne(pkg: String) {
        val value = volume(pkg) / 100f
        worker.execute {
            val result = AudioSessionDetector.setPackageVolume(this, pkg, value)
            runOnUiThread {
                status.text = if (result.startsWith("matched=")) "Pengaturan $pkg: $result" else result
            }
        }
    }

    private fun applySavedVolumes() {
        val apps = selected()
        if (apps.isEmpty() || !Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        worker.execute {
            val active = AudioSessionDetector.getActivePackages(this).toSet()
            if (active.isEmpty()) return@execute
            apps.intersect(active).forEach { pkg ->
                AudioSessionDetector.setPackageVolume(this, pkg, volume(pkg) / 100f)
            }
            runOnUiThread { status.text = "Shizuku aktif • ${active.size} aplikasi sedang memakai audio" }
        }
    }

    override fun onResume() {
        super.onResume()
        renderSelectedApps()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        worker.shutdownNow()
        super.onDestroy()
    }
}