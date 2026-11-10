package com.alif.hiosvolumemixer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

class VolumePopupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mixer", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.25f)
        window.setLayout(dp(340), WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        window.attributes = window.attributes.apply { x = dp(10) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xFFF7F7FA.toInt())
        }
        panel.addView(TextView(this).apply {
            text = "Volume sistem"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
        })
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val systemMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val systemSlider = SeekBar(this).apply {
            this.max = systemMax
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        panel.addView(systemSlider)
        panel.addView(TextView(this).apply {
            text = "Volume aplikasi"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(14), 0, dp(4))
        })

        val selected = prefs.getStringSet("selected_apps", emptySet()).orEmpty().sorted()
        if (selected.isEmpty()) {
            panel.addView(TextView(this).apply { text = "Pilih aplikasi melalui HiOS Volume Mixer." })
        } else {
            selected.forEach { pkg ->
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                val key = "volume_$pkg"
                val current = prefs.getInt(key, 100).coerceIn(0, 100)
                val heading = TextView(this).apply {
                    text = "$label • $current%"
                    textSize = 14f
                    setTextColor(0xFF202124.toInt())
                }
                var selectedVolume = current
                val slider = SeekBar(this).apply {
                    this.max = 100
                    progress = current
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                            selectedVolume = value
                            heading.text = "$label • $value%"
                            if (fromUser) prefs.edit().putInt(key, value).apply()
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {
                            val finalVolume = selectedVolume / 100f
                            worker.execute {
                                AudioSessionDetector.setPackageVolume(this@VolumePopupActivity, pkg, finalVolume)
                            }
                        }
                    })
                }
                panel.addView(heading)
                panel.addView(slider)
            }
        }
        setContentView(panel)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}