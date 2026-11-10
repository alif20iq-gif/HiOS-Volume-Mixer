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
import androidx.core.view.WindowCompat
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
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        buildUi()
        renderSelectedApps()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(28))
            setBackgroundColor(0xFFF6F5F9.toInt())
        }
        content.addView(TextView(this).apply {
            text = "HiOS Volume Mixer"
            textSize = 27f
            setTextColor(0xFF24232A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Atur volume setiap aplikasi secara terpisah."
            textSize = 14f
            setTextColor(0xFF77737F.toInt())
            setPadding(0, dp(4), 0, dp(18))
        })
        status = TextView(this).apply {
            text = "Pilih aplikasi dan hubungkan Shizuku untuk memulai."
            textSize = 13f
            setTextColor(0xFF55515F.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEAE7F1.toInt()); cornerRadius = dp(14).toFloat()
            }
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        fun actionButton(label: String, primary: Boolean = false, action: () -> Unit) =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF51466F.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (primary) 0xFF705BBE.toInt() else 0xFFE9E5F2.toInt()
                )
                setOnClickListener { action() }
            }
        content.addView(actionButton("＋  Pilih aplikasi", true) { showAppPicker() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Hubungkan Shizuku") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(2001)
            else status.text = "Shizuku belum berjalan. Aktifkan Shizuku terlebih dahulu."
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Izinkan popup di atas aplikasi") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } else {
                status.text = "Izin popup sudah aktif."
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Aktifkan kontrol tombol volume") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Pengaturan tampilan & popup") { showSettings() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Mulai pemantauan audio") {
            ContextCompat.startForegroundService(this, Intent(this, AudioMonitorService::class.java))
            status.text = "Pemantauan audio sedang berjalan."
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(20) })

        content.addView(TextView(this).apply {
            text = "Aplikasi mixer"
            textSize = 19f
            setTextColor(0xFF24232A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(10))
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appList)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(0xFFF7F7FA.toInt())
        }
        fun addSetting(title: String, key: String, min: Int, max: Int, suffix: String, default: Int) {
            val label = TextView(this).apply {
                textSize = 14f
                setTextColor(0xFF20212A.toInt())
                setPadding(0, dp(10), 0, dp(2))
            }
            panel.addView(label)
            val seek = SeekBar(this).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF4F68B5.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFF4F68B5.toInt())
                this.max = max - min
                progress = (prefs.getInt(key, default).coerceIn(min, max) - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        label.text = "$title: ${value + min}$suffix"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        prefs.edit().putInt(key, bar?.progress?.plus(min) ?: default).apply()
                    }
                })
            }
            label.text = "$title: ${seek.progress + min}$suffix"
            panel.addView(seek)
        }
        addSetting("Tutup otomatis (0 = nonaktif)", "popup_timeout", 0, 30, " dtk", 5)
        addSetting("Transparansi panel", "popup_opacity", 55, 100, "%", 92)
        addSetting("Buram latar (Android 12+)", "popup_blur", 0, 60, "", 18)
        addSetting("Ketebalan outline", "outline_width", 0, 3, " px", 1)
        panel.addView(TextView(this).apply {
            text = "Warna aksen"
            textSize = 14f
            setTextColor(0xFF20212A.toInt())
            setPadding(0, dp(16), 0, dp(6))
        })
        val colors = listOf(
            "Ungu" to 0xFF705BBE.toInt(),
            "Biru" to 0xFF3478D4.toInt(),
            "Hijau" to 0xFF26856A.toInt(),
            "Merah muda" to 0xFFB64D83.toInt()
        )
        val colorRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER }
        colors.forEach { (name, color) ->
            val button = Button(this).apply {
                text = name
                isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                setOnClickListener { prefs.edit().putInt("accent_color", color).apply() }
            }
            colorRow.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        panel.addView(colorRow)
        AlertDialog.Builder(this, R.style.Theme_HiOSVolumeMixer_SettingsDialog)
            .setTitle("Pengaturan")
            .setView(ScrollView(this).apply {
                setBackgroundColor(0xFFF7F7FA.toInt())
                addView(panel)
            })
            .setPositiveButton("Selesai", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showAppPicker() {
        if (!RecentApps.hasAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Akses penggunaan diperlukan")
                .setMessage("Izinkan Akses penggunaan agar mixer hanya menampilkan aplikasi terbaru, bukan seluruh paket sistem.")
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        val apps = RecentApps.query(this)
        val current = selected()
        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Belum ada aplikasi terbaru")
                .setMessage("Buka aplikasi yang ingin dikontrol terlebih dahulu, lalu kembali ke mixer.")
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val scroll = ScrollView(this)
        apps.forEach { app ->
            val pkg = app.packageName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageDrawable(packageManager.getApplicationIcon(app))
            }, LinearLayout.LayoutParams(42, 42))
            val label = TextView(this).apply {
                text = packageManager.getApplicationLabel(app).toString()
                textSize = 16f
                setPadding(14, 0, 8, 0)
                setTextColor(0xFF202124.toInt())
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val check = android.widget.CheckBox(this).apply {
                isChecked = pkg in current
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF6750A4.toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) current.add(pkg) else current.remove(pkg)
                }
            }
            row.addView(check)
            row.setOnClickListener { check.isChecked = !check.isChecked }
            list.addView(row)
        }
        scroll.addView(list)
        AlertDialog.Builder(this)
            .setTitle("Aplikasi terbaru")
            .setView(scroll)
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
                text = "Belum ada aplikasi. Tambahkan dari daftar aplikasi terbaru."
                textSize = 13f
                setTextColor(0xFF77737F.toInt())
                setPadding(dp(14), dp(18), dp(14), dp(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat()
                }
            })
            return
        }
        chosen.sorted().forEach { pkg ->
            val info = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
            val label = if (info != null) packageManager.getApplicationLabel(info).toString() else pkg
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat()
                }
            }
            val top = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(android.widget.ImageView(this).apply {
                setImageDrawable(if (info != null) packageManager.getApplicationIcon(info) else getDrawable(android.R.drawable.sym_def_app_icon))
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            top.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF292631.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(this).apply {
                text = "${volume(pkg)}%"
                textSize = 14f
                setTextColor(0xFF705BBE.toInt())
                tag = "volume_value"
            })
            card.addView(top)
            val slider = SeekBar(this).apply {
                max = 100
                progress = volume(pkg)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        top.findViewWithTag<TextView>("volume_value")?.text = "$value%"
                        if (fromUser) prefs.edit().putInt("volume_$pkg", value).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { applyOne(pkg) }
                })
            }
            card.addView(slider)
            appList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun applyOne(pkg: String) {
        val value = volume(pkg) / 100f
        worker.execute {
            val result = AudioSessionDetector.setPackageVolume(this, pkg, value)
            runOnUiThread {
                status.text = when {
                    result.contains("applied=0") -> "Volume $pkg belum diterapkan ke sesi audio. Detail: $result"
                    result.contains("applied=") -> "Volume aplikasi diterapkan: $result"
                    else -> result
                }
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