package com.alif.hiosvolumemixer

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyAccessibilityService : AccessibilityService() {
    private var mixer: OverlayMixerController? = null
    override fun onServiceConnected() { super.onServiceConnected(); mixer = OverlayMixerController(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()?.lowercase().orEmpty()
        // The shade/Quick Settings belongs to SystemUI. Hide our panel as soon as
        // the user opens it so notifications and QS tiles remain unobstructed.
        if (pkg == "com.android.systemui" &&
            (cls.contains("shade") || cls.contains("quicksettings") ||
             cls.contains("notificationpanel") || cls.contains("statusbarwindow"))) {
            mixer?.hide()
            return
        }
        // Do not leave the mixer floating over the launcher after Home is pressed.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val home = packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(
                    android.content.Intent.CATEGORY_HOME
                ), 0
            )?.activityInfo?.packageName
            if (!home.isNullOrBlank() && pkg == home) mixer?.hide()
        }
    }
    override fun onInterrupt() { mixer?.hide() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            mixer?.hide()
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            if (event.repeatCount == 0) mixer?.show()
            android.os.Handler(mainLooper).postDelayed({ mixer?.refreshSystemVolume() }, 120)
        }
        return true
    }
    override fun onDestroy() { mixer?.destroy(); mixer = null; super.onDestroy() }
}
