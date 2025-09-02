# Universal Clipboard Android App

This is the Android companion app for Universal Clipboard.

Features:
- Foreground clipboard sync service
- LAN WebSocket client to desktop
- QR pairing and key storage (scaffold)

## Build
1. Open the `android/` folder in Android Studio (Giraffe or newer).
2. Let Gradle sync. If asked, install Android SDK 34.
3. Run on a device with USB debugging enabled.

## Notes
- The service requires a persistent notification (foreground service) on Android 8+.
- Clipboard listening is paused when the app is in the background on some OEMs.
- Ensure the phone and desktop are on the same Wi‑Fi for LAN sync.
