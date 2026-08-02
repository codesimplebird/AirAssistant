# AI 快速上下文 — Hand Landmarker Android（中文）

> 面向 AI 代理的高密度速览，阅读成本优先。人类阅读版见 `PROJECT_OVERVIEW_zh.md`。
> 代码与文档基准：2026-07-31，工作区含未提交定制代码。

## 项目定位

MediaPipe Tasks 官方 Android 手部关键点示例（Kotlin）+ 自研"隔空手势无障碍控制"：
前置摄像头识别左右挥手，经无障碍服务派发系统级上滑/下滑，实现不触屏滚动页面。

## 技术栈

- Kotlin；AGP 8.13.2；minSdk 24 / targetSdk 34 / compileSdk 34；ViewBinding
- CameraX 1.4.2（core/camera2/lifecycle/view）；MediaPipe `tasks-vision:0.10.29`；
  Navigation 2.5.3；Material 1.7.0
- namespace/applicationId：`com.google.mediapipe.examples.handlandmarker`

## 关键文件（一句话角色）

| 文件 | 角色 |
| --- | --- |
| `MainActivity.kt` | 入口；前后台切换协调服务摄像头（onStop→keepCameraAlive / onResume→releaseCamera）；启动前台服务 + 权限引导 |
| `MainViewModel.kt` | 推理参数状态（官方原有） |
| `HandLandmarkerHelper.kt` | MediaPipe 推理封装；`returnLivestreamResult()` 内接 WaveDetector 并触发无障碍手势（定制点） |
| `WaveDetector.kt` | 挥手方向识别：300ms 滑动窗口，位移>0.20、速度>0.0006/ms；返回 `WaveResult`（DETECTED/DEBOUNCED/NONE）；`debounceMs` 由 `GestureSettings.swipeCooldownMs` 驱动 |
| `HandGestureService.kt` | 前台服务（foregroundServiceType=camera）；只绑 ImageAnalysis（标准 480x360，省电/高温 320x240）、不绑 Preview；按性能模式动态跳帧；悬浮窗预览默认关闭；静态 `instance` 供 Activity 调用 |
| `LandmarkerManager.kt` | 共享模型单例 + 唯一 visionExecutor 推理线程（前台/后台共用）；结果转发给当前活跃监听者；维护 lastFrameHadHand 供动态帧率使用 |
| `GestureSettings.kt` | 手势设置（SharedPreferences 持久化）：滑动操作总开关、滑动间隔和性能模式（自动/省电/标准/高响应）；自动模式在系统温度较高时降档 |
| `LocaleHelper.kt` | 应用内中英文切换（SharedPreferences 持久化）；经 `MainActivity.attachBaseContext` 包装 + `recreate()` 生效 |
| `GestureAccessibilityService.kt` | 无障碍服务；静态 swipeUp/swipeDown 用 GestureDescription 派发固定坐标滑动 |
| `OverlayView.kt` | 前台预览上绘制 21 关键点与连线（官方原有） |
| `fragment/CameraFragment.kt` | 前台 CameraX Preview+ImageAnalysis；onResume 重绑摄像头 |
| `fragment/PermissionsFragment.kt` | 权限引导（官方原有）；相册 fragment 已在 UI 重构中移除 |
| `res/xml/accessibility_service_config.xml` | 无障碍配置：typeAllMask + canPerformGestures |
| `download_tasks.gradle` | 构建时下载 `hand_landmarker.task` 到 assets |

## 数据流

```
CameraX ImageAnalysis → HandLandmarkerHelper(LIVE_STREAM) → 21 landmarks
→ 掌心x = (lm0+lm5+lm9+lm13+lm17)/5 → WaveDetector.detect(palmX)
→ RIGHT → swipeUp() | LEFT → swipeDown()
→ dispatchGesture → 系统滚动
```

## 前后台摄像头互斥规则

- 前台：`CameraFragment` 持有 Preview+ImageAnalysis（Fragment 生命周期）
- 退后台：`MainActivity.onStop()` → `HandGestureService.keepCameraAlive()`（仅 ImageAnalysis，无 Surface 依赖，后台稳定）
- 回前台：`MainActivity.onResume()` → `service.releaseCamera()` → `CameraFragment.onResume()` 重绑
- 同一时刻只有一个摄像头绑定者

## 界面与行为要点

- Delegate 默认 GPU（`MainViewModel` 与 `HandLandmarkerHelper` 默认值）；
  模型/设备不支持 GPU 时自动回退 CPU
- 参数面板底部的"Exit"按钮与返回键都会**完全退出**：先停 `HandGestureService`
  （释放摄像头/悬浮窗/模型）再关闭界面。不要只调用 `finish()`，否则服务会继续在后台运行

## 关键常量

| 常量 | 值 |
| --- | --- |
| 挥手窗口 / 去抖 | 300 ms / 800 ms |
| 挥手位移 / 速度阈值 | 0.20（归一化）/ 0.0006 per ms |
| 掌心关键点 | 0, 5, 9, 13, 17 |
| 分析分辨率 | 标准 480×360；省电或高温 320×240；FALLBACK_RULE_CLOSEST_LOWER，KEEP_ONLY_LATEST，RGBA_8888，前置摄像头 |
| 分析帧率 | 自动/标准：空闲约 5fps（步长 6）/ 有手约 15fps（步长 2）；省电：约 3~4/10fps（步长 8/3）；高响应：约 7.5/30fps（步长 4/1）；严重温度自动降至步长 8/3 |
| 滑动间隔 | 默认 1000ms，范围 500~3000ms（步进 500ms），界面"滑动间隔"行；滑动后冷却期内忽略新挥手 |
| 滑动总开关 | 默认开启；关闭后仍检测/记录挥手，但不派发任何滑动 |
| 挥手反馈 | 双槽位（`activity_main.xml`，Activity 层级不被遮挡）：生效 → 顶部 `gesture_feedback_success`（绿色、短停留）；未生效 → 底部 `gesture_feedback_failure`（按原因变色：冷却橙/已关闭灰/无障碍红、长停留）。右上角常驻 `accessibility_status` 状态角标（绿=已开启/红=未开启，onResume/焦点变化时刷新）。文案带 ✅/⚠️ 图标（`GestureFeedbackText`）。震动区分：生效 1 下、未生效 2 下；DEBOUNCED 通知节流 400ms |
| 无障碍快捷入口 | 底部面板"无障碍设置"按钮直达系统无障碍页（MIUI 重装/更新后会重置无障碍服务开启状态，装完需重新开启） |
| 高级设置 | 底部面板可展开区：挥手检测提示（默认开）、后台显示提示（Toast 浮在其他应用上，默认关）、提示时长（1.5~5s，默认 2.5s）、后台悬浮窗（默认关）、震动反馈（默认关）、语言。悬浮窗改为运行时设置（`GestureSettings.floatingWindowEnabled`），不再是编译期常量 |
| 悬浮窗 | 180×240 @ (100,300)，可拖动 |
| 滑动坐标 | x=500；上滑 y 1600→400，下滑 y 400→1600（硬编码） |
| 通知 | id=1001，channel `hand_gesture_channel` |
| 模型 | `assets/hand_landmarker.task`，float16 ≈7.8MB |

## 权限

`CAMERA`、`SYSTEM_ALERT_WINDOW`（悬浮窗需系统设置手动授权）、
`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`POST_NOTIFICATIONS`（Android 13+）；
无障碍服务需用户在系统设置中手动开启。

## 构建 / 测试

- 需 JDK 17（如 Temurin 17）；Android Studio 自带 JDK 25 会导致 Kotlin 1.7.10 编译 ICE，
  命令行构建前设置 `JAVA_HOME` 指向 JDK 17
- `./gradlew assembleDebug`（自动先下载模型，`download_tasks.gradle`，overwrite=false）
- `./gradlew connectedDebugAndroidTest`（官方仪器测试：image/video/live_stream 结果范围断言）
- 必须真机 + 开发者模式；模拟器无摄像头不可用

## 已知问题（改动前必读）

1. 挥手阈值与滑动坐标全部硬编码 → 横屏/平板/刘海屏会失灵
2. `WaveDetector` 无单元测试（纯 Kotlin，易测）
3. 手势仅左右挥手，映射写死：RIGHT=上滑、LEFT=下滑
4. 前台服务受 Android 14/15 服务类型与省电策略限制，长时间后台可能被回收
5. 无障碍服务上架 Google Play 需专门声明（Accessibility Service Declaration）
6. 模型文件未跟踪（untracked）；建议沿用官方构建期下载，勿提交大文件
7. `HandLandmarkerHelper.detectLiveStream()` 内部会 close imageProxy；
   `HandGestureService` 先复制像素字节再调用，勿重复读 buffer
8. 服务内不要单独用 `setTargetResolution(640,480)`：部分机型 CameraX 会选成超大传感器尺寸
   （如 1940x1940），导致 15MB/帧位图分配、GC 风暴、推理线程饿死、结果永不回调。
   必须用 `setTargetAspectRatio(RATIO_4_3)`（与 CameraFragment 一致）并打印帧尺寸核对。
9. 模型创建已用全局锁（`CREATION_LOCK`）串行化：两个线程并发
   `HandLandmarker.createFromOptions` 会在冷启动时触发 SIGBUS 原生崩溃，
   因此服务改为收到第一帧时才懒加载模型。
10. CameraX 1.4.2 的 `ImageAnalysis.Builder` 没有 `setTargetFrameRate`，限帧只能靠
    analyzer 跳帧实现（跳过的帧必须 close imageProxy）。
11. 所有模型创建/推理必须走 `LandmarkerManager.visionExecutor`（单线程），
    GPU delegate 要求"创建线程 = 使用线程"，禁止在主线程创建共享模型。
12. 帧计数器不要一帧自增两次（如跳帧判断前后各一次）——会导致所有帧被跳过，
    后台推理静默失效而前台正常。

## Git 状态（未提交定制）

- 新增：`WaveDetector.kt`、`HandGestureService.kt`、`GestureAccessibilityService.kt`、
  `accessibility_service_config.xml`、`assets/hand_landmarker.task`（未跟踪）
- 修改：`AndroidManifest.xml`、`HandLandmarkerHelper.kt`、`MainActivity.kt`、
  `CameraFragment.kt`、`build.gradle`（AGP 8.13.2）；`HandGestureService.kt`
  （共享模型、320x240、动态帧率、悬浮窗默认关闭）；`MainViewModel.kt`（GPU 默认）；
  `LandmarkerManager.kt`（新增）
