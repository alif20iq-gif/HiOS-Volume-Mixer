# HiOS Volume Mixer

Standalone Android app prototype for monitoring audio playback sessions on TECNO HiOS.

## Current prototype
- Foreground service keeps the monitor running after the app UI is backgrounded.
- Attempts to inspect Android's audio playback state using `dumpsys audio`.
- Supports Shizuku permission and a root-shell fallback.
- Shows detected package/UID sessions where the device exposes a recognizable active playback state.

## Important limitations
Android does not expose a universal public API for one third-party app to independently change another app's volume. This first build is a detection prototype, not yet a completed per-app volume controller or SystemUI volume-panel replacement.
The exact `dumpsys audio` format and visibility of playback details vary by Android version and vendor ROM. TECNO HiOS must be tested on-device; an empty result does not prove that no app is playing audio.

## Build
GitHub Actions builds a debug APK on every push to `main` and on manual dispatch.

## Planned work
1. Validate playback-session parsing on TECNO HiOS / Android 16.
2. Improve package attribution and distinguish playing, paused, and idle sessions.
3. Investigate safe per-app volume control backends for supported root/Shizuku configurations.
4. Design a volume-panel companion UI. True SystemUI integration is ROM/root dependent.
