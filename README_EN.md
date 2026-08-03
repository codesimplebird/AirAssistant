# Air Assistant

[中文](README.md) | [English](README_EN.md)

> Control your phone without touching the screen. Use the front camera to recognize hand gestures and let the accessibility service perform swipes, clicks, and long presses for you.

Air Assistant is an Android hands-free gesture control app based on Google's MediaPipe Hand Landmarker. Point your hand at the front camera: **left and right waves** scroll pages, while a **thumb-and-index-finger pinch** triggers clicks, long presses, or swipes.

## Features

- **Left and right waves**: Detect right and left waves and bind them to swipe up, swipe down, click, or long press
- **Pinch gesture**: Trigger an action when the thumb and index finger pinch and release
- **Full-screen coordinates**: Configure swipe start/end points, X positions, click coordinates, and long-press coordinates in percentage or pixel units
- **Background recognition**: A foreground service takes over camera recognition when the app moves to the background, with an optional floating preview
- **Performance modes**: Auto, battery saving, standard, and high response; automatically reduces workload when the device is too hot
- **Adjustable sensitivity**: Configure wave distance, wave speed, and pinch recognition threshold in Advanced Settings
- **Settings panel**: A slide-in panel with collapsible sections, color-coded cards, and inline help
- **Feedback**: Success/failure prompts and optional vibration feedback, with Chinese and English UI support
- **No network dependency**: All recognition runs locally on the device; no data is uploaded

## Download

Download the APK from the [Releases](https://github.com/codesimplebird/AirAssistant/releases) page.

> Accessibility-service apps often cannot be distributed through mainstream app stores because of strict review requirements. Install the APK and manually enable Air Assistant in the system Accessibility settings.

## Quick Start

1. Install the APK and grant camera permission.
2. Open system settings, go to Accessibility, and enable Air Assistant.
3. Open the app and face the front camera with your hand.
4. By default, a right wave swipes up and a left wave swipes down. These actions can be changed in Settings.

### Gesture Reference

| Gesture | Default action | Configurable actions |
|---|---|---|
| Right wave | Swipe up | Swipe up / swipe down / click / long press |
| Left wave | Swipe down | Swipe up / swipe down / click / long press |
| Pinch (thumb + index finger) | Click | Swipe up / swipe down / click / long press |

## Build

### Requirements

- JDK 17. The Kotlin toolchain used by this project is not compatible with the JDK 25 bundled with some Android Studio installations.
- Android SDK with compileSdk 34

### Command-Line Build

```bash
# Set JAVA_HOME to JDK 17 first.
gradlew.bat app:assembleDebug --offline
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The hand landmark model is downloaded automatically into the assets directory on the first build.

### Release Signing

A signing key is required for official releases. Back up the key securely because it is required for all future updates.

```bash
cd app
keytool -genkeypair -v -keystore release.keystore -alias air -keyalg RSA -keysize 2048 -validity 10000
cd ..
```

Copy `keystore.properties.example` to `keystore.properties` and fill in the passwords. The properties file and keystore are excluded by `.gitignore`.

## Permissions

| Permission | Purpose |
|---|---|
| CAMERA | Front-camera hand gesture recognition |
| Accessibility service | Dispatch system swipes, clicks, and long presses |
| SYSTEM_ALERT_WINDOW | Optional floating preview while running in the background |
| POST_NOTIFICATIONS | Foreground-service notification on Android 13+ |

## Compatibility

- **Android 7.0 (API 24) and newer**; Android 10+ is recommended for the best background behavior
- Requires a front-facing camera. MIUI, HarmonyOS, ColorOS, and other vendor systems may restrict background services; allow auto-start and disable battery optimization when necessary
- See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for details

## Privacy

All hand gesture recognition runs locally on the device. No data is collected, uploaded, or transmitted. Camera frames are used only for real-time recognition and are not stored or recorded. See [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

## Disclaimer

- This app uses an accessibility service to simulate user actions. Use it only on devices you trust.
- Gesture recognition may trigger unintended actions. Do not use it while entering payment passwords or other sensitive information.
- Continuous camera recognition increases battery usage and may cause the device to heat up.

## Documentation

- [Architecture and Roadmap](ARCHITECTURE_AND_ROADMAP.md)
- [Project Overview](docs/PROJECT_OVERVIEW_en.md)
- [AI Context](docs/AI_CONTEXT_en.md)
- [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE)

This project is based on Google's [MediaPipe Hand Landmarker Android example](https://github.com/google-ai-edge/mediapipe-samples).
