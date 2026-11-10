package com.alif.hiosvolumemixer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.max

class OverlayMixerController(private val service: AccessibilityService) {
    private val prefs = service.getSharedPreferences("mixer", Context.MODE_PRIVATE)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = Executors.newSingleThreadExecutor()
    private var root: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val accent get() = prefs.getInt("accent_color", Color.rgb(112, 91, 190))
    private val handler = Handler(Looper.getMainLooper())
    private var systemSlider: VerticalVolumeSlider? = null
    private val autoHide = Runnable { hide() }
    fun refreshSystemVolume() {
        val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        systemSlider?.let { it.maxValue = maxV; it.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
    }
    private fun resetAutoHide() {
        handler.removeCallbacks(autoHide)
        val seconds = prefs.getInt("popup_timeout", 5)
        if (seconds > 0 && root != null) handler.postDelayed(autoHide, seconds * 1000L)
    }
    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
    private fun bg(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }

    fun hide() {
        handler.removeCallbacks(autoHide)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        overlayParams = null
        systemSlider = null
    }
    fun show() { if (root == null) drawMixer() }
    fun destroy() { hide(); worker.shutdownNow() }

    private fun attach(card: LinearLayout) {
        hide()
        if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(service)) {
            return
        }
        val opacity = prefs.getInt("popup_opacity", 92).coerceIn(55, 100)
        val alpha = (opacity * 255 / 100) shl 24
        card.background = GradientDrawable().apply {
            setColor(alpha or 0x00ECECF5)
            cornerRadius = dp(24).toFloat()
            setStroke(
                dp(prefs.getInt("outline_width", 1).coerceIn(0, 3)),
                prefs.getInt("outline_color", 0xFFB8B5C2.toInt())
            )
        }
        card.elevation = dp(18).toFloat()
        card.isClickable = true
        card.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hide()
                true
            } else false
        }
        val params = WindowManager.LayoutParams(
            dp(310),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = prefs.getInt("popup_x", dp(10)).coerceAtLeast(0)
            val statusId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
            val topInset = if (statusId > 0) service.resources.getDimensionPixelSize(statusId) else dp(24)
            y = topInset + prefs.getInt("popup_y", dp(110)).coerceAtLeast(0)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val blur = prefs.getInt("popup_blur", 18).coerceIn(0, 60)
                if (blur > 0) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = blur
                }
            }
        }
        try {
            wm.addView(card, params)
            root = card
            overlayParams = params
            installDragHandle(card, params)
            resetAutoHide()
        } catch (_: Exception) {
            root = null
            overlayParams = null
        }
    }

    private fun installDragHandle(card: LinearLayout, params: WindowManager.LayoutParams) {
        val handle = card.findViewWithTag<View>("popup_drag_handle") ?: return
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val screen = service.resources.displayMetrics
                    params.x = (startX - dx).coerceIn(0, (screen.widthPixels - dp(80)).coerceAtLeast(0))
                    params.y = (startY + dy).toInt().coerceIn(0, (screen.heightPixels - dp(100)).coerceAtLeast(0))
                    try { wm.updateViewLayout(card, params) } catch (_: Exception) {}
                    resetAutoHide()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val statusId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
                    val topInset = if (statusId > 0) service.resources.getDimensionPixelSize(statusId) else dp(24)
                    prefs.edit().putInt("popup_x", params.x).putInt("popup_y", (params.y - topInset).coerceAtLeast(0)).apply()
                    true
                }
                else -> true
            }
        }
    }

    private fun panel() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    private fun header(title: String) = LinearLayout(service).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(service).apply {
            text = "⠿   $title"; textSize = 18f; setTextColor(0xFF24232A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            tag = "popup_drag_handle"
            setPadding(dp(4), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(0, -2, 1f))

    }

    private fun drawMixer() {
        val box = panel()
        box.addView(header("Volume"))
        box.addView(TextView(service).apply {
            text = "Geser ke atas atau bawah untuk mengatur volume"
            textSize = 11f; setTextColor(0xFF77737F.toInt()); setPadding(0, dp(3), 0, dp(10))
        })
        val row = LinearLayout(service).apply { gravity = Gravity.CENTER }
        row.addView(volumeColumn(null), LinearLayout.LayoutParams(dp(76), dp(190)))
        prefs.getStringSet("selected_apps", emptySet()).orEmpty().take(3).forEach {
            row.addView(volumeColumn(it), LinearLayout.LayoutParams(dp(76), dp(190)))
        }
        box.addView(row)
        box.addView(TextView(service).apply {
            text = "+  Tambah aplikasi"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = bg(accent)
            setPadding(0, dp(11), 0, dp(11)); setOnClickListener { handler.removeCallbacks(autoHide); drawPicker() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        attach(box)
    }

    private fun volumeColumn(pkg: String?): View {
        val col = LinearLayout(service).apply { orientation = 1; gravity = Gravity.CENTER; setPadding(dp(3), dp(4), dp(3), dp(2)) }
        val maxV = if (pkg == null) audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) else 100
        val initial = if (pkg == null) audio.getStreamVolume(AudioManager.STREAM_MUSIC) else prefs.getInt("volume_$pkg", 100).coerceIn(0, 100)
        val label = TextView(service).apply {
            text = "${initial * 100 / maxV}%"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF34313C.toInt())
        }
        col.addView(label)
        val slider = VerticalVolumeSlider(service).apply {
            maxValue = maxV; value = initial; tint = accent
            if (pkg == null) systemSlider = this
            onValueChanged = { v ->
                resetAutoHide()
                label.text = "${v * 100 / maxV}%"
                if (pkg == null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                else prefs.edit().putInt("volume_$pkg", v).apply()
            }
            onFinished = { v ->
                if (pkg != null) worker.execute { AudioSessionDetector.setPackageVolume(service, pkg, v / 100f) }
            }
        }
        col.addView(slider, LinearLayout.LayoutParams(dp(48), dp(118)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        if (pkg == null) {
            col.addView(TextView(service).apply { text = "Sistem"; textSize = 11f; setTextColor(0xFF625E69.toInt()) })
        } else {
            val info = try { service.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
            col.addView(ImageView(service).apply {
                setImageDrawable(if (info != null) service.packageManager.getApplicationIcon(info) else service.getDrawable(android.R.drawable.sym_def_app_icon))
            }, LinearLayout.LayoutParams(dp(27), dp(27)))
            col.addView(TextView(service).apply {
                text = if (info != null) service.packageManager.getApplicationLabel(info).toString() else pkg
                textSize = 10f; setTextColor(0xFF625E69.toInt()); gravity = Gravity.CENTER; maxLines = 1
            })
        }
        return col
    }

    private fun drawPicker() {
        val apps = RecentApps.query(service)
        val selected = prefs.getStringSet("selected_apps", emptySet()).orEmpty().toMutableSet()
        val box = panel()
        box.addView(header("Aplikasi terbaru"))
        box.addView(TextView(service).apply {
            text = "Pilih aplikasi yang ingin dimasukkan ke mixer"
            textSize = 11f; setTextColor(0xFF77737F.toInt()); setPadding(0, dp(3), 0, dp(8))
        })
        val scroll = ScrollView(service)
        val list = LinearLayout(service).apply { orientation = 1 }
        if (apps.isEmpty()) list.addView(TextView(service).apply {
            text = if (RecentApps.hasAccess(service)) "Belum ada aplikasi terbaru." else "Izinkan Akses penggunaan untuk melihat aplikasi terbaru."
            setTextColor(0xFF55515F.toInt()); setPadding(0, dp(16), 0, dp(16))
        })
        apps.forEach { app ->
            val pkg = app.packageName
            val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
            row.addView(ImageView(service).apply { setImageDrawable(service.packageManager.getApplicationIcon(app)) },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(TextView(service).apply {
                text = service.packageManager.getApplicationLabel(app).toString()
                textSize = 14f; setTextColor(0xFF34313C.toInt()); setPadding(dp(10), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val check = CheckBox(service).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = pkg in selected
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(pkg) else selected.remove(pkg) }
            }
            row.addView(check); row.setOnClickListener { check.isChecked = !check.isChecked }; list.addView(row)
        }
        scroll.addView(list); box.addView(scroll, LinearLayout.LayoutParams(-1, dp(290)))
        val actions = LinearLayout(service).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(TextView(service).apply {
                text = "Batal"; setTextColor(0xFF625E69.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { drawMixer() }
            })
            addView(TextView(service).apply {
                text = "Simpan"; setTextColor(accent); typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(10), dp(4), dp(10))
                setOnClickListener { prefs.edit().putStringSet("selected_apps", selected).apply(); drawMixer() }
            })
        }
        box.addView(actions); attach(box)
    }
}

private class VerticalVolumeSlider(context: Context) : View(context) {
    var maxValue = 100
        set(v) { field = max(1, v); invalidate() }
    var value = 100
        set(v) { field = v.coerceIn(0, maxValue); invalidate() }
    var tint = Color.BLUE
    var onValueChanged: ((Int) -> Unit)? = null
    var onFinished: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var dragging = false
    private fun dp(v: Float) = v * density

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val top = dp(9f); val bottom = height - dp(9f); val r = dp(5f)
        paint.color = 0xFFD6D2DF.toInt()
        canvas.drawRoundRect(cx-r, top, cx+r, bottom, r, r, paint)
        val y = bottom - (bottom-top) * value / maxValue
        paint.color = tint
        canvas.drawRoundRect(cx-r, y, cx+r, bottom, r, r, paint)
        canvas.drawCircle(cx, y, dp(10f), paint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; update(e.y); return true }
            MotionEvent.ACTION_MOVE -> { if (dragging) update(e.y); return true }
            MotionEvent.ACTION_UP -> { if (dragging) { update(e.y); dragging = false; onFinished?.invoke(value); performClick() }; return true }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return true
    }
    private fun update(y: Float) {
        val top = dp(9f); val bottom = height - dp(9f)
        val fraction = (1f - (y-top)/(bottom-top).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val next = (fraction * maxValue).toInt()
        if (next != value) { value = next; onValueChanged?.invoke(value); invalidate() }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
