package com.alif.hiosvolumemixer

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyAccessibilityService : AccessibilityService() {
    private var mixer: OverlayMixerController? = null
    override fun onServiceConnected() { super.onServiceConnected(); mixer = OverlayMixerController(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
            mixer?.refreshSystemVolume()
        }
        return true
    }
    override fun onDestroy() { mixer?.destroy(); mixer = null; super.onDestroy() }
}
