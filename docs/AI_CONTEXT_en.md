# AI Quick Context — Hand Landmarker Android (English)

> High-density briefing for AI agents; prioritize reading cost. Human version:
> `PROJECT_OVERVIEW_en.md`. Baseline: 2026-07-31; working tree contains uncommitted
> custom code.

## What this is

Official MediaPipe Tasks Android hand-landmark demo (Kotlin) + custom "air-gesture
accessibility" feature: front camera detects left/right waving, an
AccessibilityService dispatches system-level swipe up/down, so users can scroll
without touching the screen.

## Stack

- Kotlin; AGP 8.13.2; minSdk 24 / targetSdk 34 / compileSdk 34; ViewBinding
- CameraX 1.4.2 (core/camera2/lifecycle/view); MediaPipe `tasks-vision:0.10.29`;
  Navigation 2.5.3; Material 1.7.0
- namespace/applicationId: `com.google.mediapipe.examples.handlandmarker`

## Key files (one-line role)

| File | Role |
| --- | --- |
| `MainActivity.kt` | Entry; coordinates service camera on background/foreground (onStop→keepCameraAlive / onResume→releaseCamera); starts foreground service + permission flows |
| `MainViewModel.kt` | Holds inference settings (stock) |
| `HandLandmarkerHelper.kt` | MediaPipe inference wrapper; `returnLivestreamResult()` hooks WaveDetector and triggers accessibility gestures (custom) |
| `WaveDetector.kt` | Wave-direction detection: 300ms sliding window, distance>0.15, speed>0.0005/ms; returns `WaveResult` (DETECTED/DEBOUNCED/NONE); `debounceMs` is driven by `GestureSettings.swipeCooldownMs` |
| `HandGestureService.kt` | Foreground service (foregroundServiceType=camera); binds ImageAnalysis only (ResolutionSelector -> 320x240, no Preview/Surface); dynamic frame rate (5fps idle / 15fps hand); floating-window preview disabled by default; static `instance` for Activity calls |
| `LandmarkerManager.kt` | Shared singleton model + single visionExecutor used by both foreground and background; routes results to active listener; tracks lastFrameHadHand for dynamic fps |
| `GestureSettings.kt` | Runtime + persisted gesture settings (SharedPreferences): Swipe Control master switch (default ON) and swipe cooldown interval (default 1000ms, 500-3000ms) |
| `LocaleHelper.kt` | In-app zh/en language switch persisted in SharedPreferences; applied via `MainActivity.attachBaseContext` + `recreate()` |
| `GestureAccessibilityService.kt` | AccessibilityService; static swipeUp/swipeDown dispatch fixed-coordinate gestures |
| `OverlayView.kt` | Draws 21 landmarks + connections on foreground preview (stock) |
| `fragment/CameraFragment.kt` | Foreground CameraX Preview+ImageAnalysis; rebinds camera onResume |
| `fragment/PermissionsFragment.kt` | Permission prompts (stock); gallery fragment was removed in the redesign |
| `res/xml/accessibility_service_config.xml` | Accessibility config: typeAllMask + canPerformGestures |
| `download_tasks.gradle` | Downloads `hand_landmarker.task` into assets at build time |

## Data flow

```
CameraX ImageAnalysis → HandLandmarkerHelper(LIVE_STREAM) → 21 landmarks
→ palmX = (lm0+lm5+lm9+lm13+lm17)/5 → WaveDetector.detect(palmX)
→ RIGHT → swipeUp() | LEFT → swipeDown()
→ dispatchGesture → system scroll
```

## Foreground/background camera ownership

- Foreground: `CameraFragment` owns Preview+ImageAnalysis (Fragment lifecycle)
- To background: `MainActivity.onStop()` → `HandGestureService.keepCameraAlive()`
  (ImageAnalysis only; no Surface dependency; stable in background)
- To foreground: `MainActivity.onResume()` → `service.releaseCamera()` →
  `CameraFragment.onResume()` rebinds
- Exactly one camera binder at a time

## UI & behavior notes

- Default delegate is GPU (`MainViewModel` + `HandLandmarkerHelper` default);
  auto-falls back to CPU if the model/device rejects GPU.
- "Exit" button in the bottom sheet and the back key perform a FULL exit:
  `HandGestureService` is stopped (camera/overlay/model released) and the
  activity finishes. Never use `finish()` alone, or the service keeps running.

## Key constants

| Constant | Value |
| --- | --- |
| Wave window / debounce | 300 ms / 800 ms |
| Distance / speed thresholds | 0.15 (normalized) / 0.0005 per ms |
| Palm landmarks | 0, 5, 9, 13, 17 |
| Background resolution | 320×240 (target 480×360, FALLBACK_RULE_CLOSEST_LOWER), KEEP_ONLY_LATEST, RGBA_8888, front camera |
| Background frame rate | Dynamic: 5fps idle (stride 6) / 15fps hand present (stride 2) via analyzer frame skipping |
| Swipe cooldown | Default 1000ms, range 500-3000ms (step 500ms), UI row "Swipe Interval"; gates wave re-triggering after a swipe |
| Gesture master switch | Default ON; when OFF, waves are detected/logged but no swipe is dispatched |
| Gesture feedback UI | Dual slots in `activity_main.xml` (drawn above all fragments): success -> `gesture_feedback_success` top/green/short stay; failure -> `gesture_feedback_failure` bottom/color-coded (cooldown orange, disabled gray, accessibility red)/long stay. Plus persistent `accessibility_status` badge (top-right, green/red) refreshed on resume/focus. Icons ✅/⚠️ in `GestureFeedbackText`. Vibration: success = 1 buzz, failure = 2 buzzes. DEBOUNCED notifications throttled to 400ms |
| Accessibility shortcut | "Accessibility Settings" button in the bottom sheet jumps to system accessibility settings (installs/reinstalls reset the service enablement on MIUI; re-enable after each install) |
| Advanced Settings | Expandable bottom-sheet section: Show Detection Hint (default ON), Show Hint in Background (Toast over other apps, default OFF), Hint Duration (1.5-5s, default 2.5s), Background Floating Preview (default OFF), Vibration Feedback (default OFF), Language. Floating-window enablement is now a runtime setting (`GestureSettings.floatingWindowEnabled`), not a compile-time const |
| Overlay window | 180×240 @ (100,300), draggable |
| Swipe coordinates | x=500; up: y 1600→400, down: y 400→1600 (hard-coded) |
| Notification | id=1001, channel `hand_gesture_channel` |
| Model | `assets/hand_landmarker.task`, float16 ≈7.8MB |

## Permissions

`CAMERA`, `SYSTEM_ALERT_WINDOW` (manual grant via system settings),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `POST_NOTIFICATIONS` (13+);
AccessibilityService must be enabled manually in system settings.

## Build / test

- Requires JDK 17 (e.g. Temurin 17); the Android Studio bundled JDK 25 breaks Kotlin 1.7.10 (ICE). Set `JAVA_HOME` to the JDK 17 path before CLI builds.
- `./gradlew assembleDebug` (model auto-downloads first; `download_tasks.gradle`, overwrite=false)
- `./gradlew connectedDebugAndroidTest` (stock instrumented tests: image/video/live_stream result ranges)
- Requires a physical device + developer mode; emulator camera is unusable

## Known issues (read before changing code)

1. Wave thresholds and swipe coordinates are hard-coded → broken on landscape/tablet/notch
2. `WaveDetector` has no unit tests (pure Kotlin, easy to test)
3. Only left/right waving; mapping is fixed: RIGHT=swipe up, LEFT=swipe down
4. Foreground service faces Android 14/15 service-type and battery restrictions;
   long background runs may be killed
5. Play Store requires an Accessibility Service Declaration for such services
6. Model file is untracked; keep using build-time download instead of committing the binary
7. `HandLandmarkerHelper.detectLiveStream()` closes the imageProxy internally;
   `HandGestureService` copies pixel bytes first — never read the buffer twice
8. Do NOT use `setTargetResolution(640,480)` alone in the service: on some devices
   CameraX picks a huge sensor size (e.g. 1940x1940) that starves inference. Use
   `setTargetAspectRatio(RATIO_4_3)` (matches CameraFragment) and log the frame size.
9. Model creation is serialized via a global lock (`CREATION_LOCK`); two concurrent
   `HandLandmarker.createFromOptions` calls can SIGBUS-crash the process at cold start.
   The service therefore creates its helper lazily on the first background frame.
10. CameraX 1.4.2 `ImageAnalysis.Builder` has NO `setTargetFrameRate`; frame limiting
    is done by skipping frames in the analyzer (always close the skipped imageProxy).
11. All model creation/detection must run on `LandmarkerManager.visionExecutor`
    (single thread) because the GPU delegate requires creator thread == user thread.
    Never create the shared helper on the main thread.
12. Do not increment a frame counter twice per frame (e.g. before and inside the
    stride check) — it makes every frame skipped and silently kills background
    inference while foreground still works.

## Git status (uncommitted custom code)

- Added: `WaveDetector.kt`, `HandGestureService.kt`, `GestureAccessibilityService.kt`,
  `accessibility_service_config.xml`, `assets/hand_landmarker.task` (untracked)
- Modified: `AndroidManifest.xml`, `HandLandmarkerHelper.kt`, `MainActivity.kt`,
  `CameraFragment.kt`, `build.gradle` (AGP 8.13.2); `HandGestureService.kt`
  (shared model, 320x240, dynamic fps, overlay off by default);
  `MainViewModel.kt` (GPU default); `LandmarkerManager.kt` (new)
