# 项目总结（隔空助手 / Air Assistant）

> 本文档由历史开发会话总结生成，供新环境/新会话快速上手。项目为 MediaPipe Hand Landmarker 的 Android 示例改造的手势控制应用。

## 1. 项目概览

- **位置**：`mediapipe-samples/examples/hand_landmarker/android`
- **包名**：`com.google.mediapipe.examples.handlandmarker`
- **本质**：手机相机实时识别手掌，通过「右挥手/左挥手」等手势控制手机（滑动/点击/长按），服务常驻后台（无障碍服务 + 前台服务）
- **真机**：小米 10 Pro（1080x2340），Android 12（MIUI）
- **UI 风格（用户要求，不可逆）**：浅色极简（白底 + 灰阶 #1C1C1E/#8E8E93/#E5E5EA + 品牌橙 #FF7A00 点缀），**全部移除渐变**，状态栏可见（微信风格深色图标），底部面板白卡，无多余装饰

## 2. 构建与工具

- 构建：`gradlew.bat app:assembleDebug --offline`（android 目录）
- 安装：`adb install -r app\build\outputs\apk\debug\app-debug.apk`
- adb：`%ANDROID_HOME%\platform-tools\adb.exe`（本机 SDK platform-tools）
- 真机调试流程见 skill `android-device-debug`（项目 `.opencode/skills/`）
- 工作约定见项目根 `AGENTS.md`
- Android MCP（`.opencode/opencode.json`，本地配置不入库）：uv 启动本机 `android-mcp-server`（cmd /c 注入 adb 到 PATH；Windows 上 environment.PATH 插值有反斜杠 bug 不可用）

## 3. 源码架构（app/src/main/java/.../handlandmarker/）

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 主界面 + 设置对话框（含全部参数绑定/联动/动效） |
| `CameraFragment.kt` | 相机预览 + 底部面板（手势状态卡/快捷开关），面板展开状态记忆（SharedPreferences） |
| `GestureSettings.kt` | 全部手势配置（单例 + SharedPreferences 持久化，volatile 字段） |
| `GestureAccessibilityService.kt` | 无障碍服务：dispatchSwipe / dispatchTap(clickXY) / dispatchLongPress(longPressXY+duration) |
| `HandGestureService.kt` | 前台服务（静态通知，无按钮），常驻保活 |
| `HandLandmarkerHelper.kt` | 每帧手势识别 → 按方向 action 执行动作 |
| `WaveDetector.kt` | 挥手检测 |
| `LandmarkerManager.kt` | 模型单例（initFailed 保护 + 失败对话框） |
| `OverlayView.kt` | 手部骨架覆盖层（品牌橙：#FF7A00 线 / #FFA726 点 / #E65100 辉光） |
| `AppLog.kt` / `CrashHandler.kt` | 崩溃日志 + 异常退出提示 + 日志导出（FileProvider `androidx.core.content.FileProvider`，authority `${applicationId}.fileprovider`，file_paths files-path logs/） |
| `GestureFeedbackText.kt` | 反馈槽文案 |
| `LocaleHelper.kt` | 中英文切换（默认中文） |
| `MainViewModel.kt` / `PermissionsFragment.kt` | 主 VM / 权限页（权限永久拒绝 → 设置引导） |

## 4. 关键设计决策（重要，勿违背）

1. **操作选择器**：右挥/左挥各自 4 段分段（上滑|下滑|点击|长按）——**下拉框方案已被用户否决并还原**（30c3d80 曾改下拉框，e44400a 还原）
2. **参数区归属**：每个手势开关下方 = 自己的「操作分段 + 可折叠参数区（Params 标题，点击展开/收起，箭头旋转+淡入动效）」，参数内容**绑定该方向操作**（滑动→滑动参数；点击→单位+点击X/Y；长按→单位+长按X/Y/时长）；**手势开关关闭时该手势的操作+参数整体隐藏**（9fbb4a9）
3. **参数区视觉**：灰底圆角次级块（`bg_sub_group` #F3F3F6），与白色开关行（主设置）区分
4. **位置单位**：百分比/像素下拉框（`spinner_unit_r/_l`，两处共用一份配置、互相同步）；XY 内部按**像素**存储，旧百分比配置自动迁移；值刷新带脉冲动效（OvershootInterpolator）
5. **滑动参数**（滑动X位置/起点/终点/速度）为全局一份配置，已从顶部「滑动设置卡」移入各手势参数区
6. **通知栏交互按钮已废弃**（曾致手机死机，ad7a687 → 1844e7d 移除）：通知保持静态无按钮；手势/相机开关在主屏顶部**透明小开关条**（alpha 0.85、Switch 0.8x、11sp）
7. **相机缩放已取消**（用户明确放弃）：面板交互只旋转 chevron（收起▲ / 展开▼）
8. **保活**：设置内「后台保活卡」——电池优化白名单（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）+ MIUI 自启动引导
9. **崩溃响应**：CrashHandler + AppLog + 异常退出提示 + 加载 overlay + 重试对话框 + 诊断卡片 + 日志导出
10. **命名风格**：id 后缀 `_r`/`_l`（右/左挥两套参数区），`@color/brand_orange` 系品牌色，XML 必须 UTF-8 无 BOM

## 5. 手势配置数据模型（GestureSettings 要点）

- 开关：`rightWaveEnabled` / `leftWaveEnabled`
- 操作：`rightWaveAction` / `leftWaveAction`（0=上滑 1=下滑 2=点击 3=长按）
- 位置（像素存储）：`clickXPx/clickYPx`、`longPressXPx/longPressYPx`、`longPressDurationMs`（默认 800ms）
- 单位：`positionUnit`（0=百分比 1=像素）
- 滑动（全局）：`swipeXPercent`、`swipeStartYPercent`、`swipeEndYPercent`、`swipeDurationMs`
- 辅助：`showHint`、`hintBackground`、`hintDurationMs`、`floatingWindowEnabled`、`vibrationEnabled`
- prefs 文件：`shared_prefs/gesture_settings.xml`（run-as 可读验证）
- 默认值：右=上滑(0)、左=下滑(1)、单位=百分比(0)、两开关开

## 6. 设置对话框 UI 结构（dialog_settings.xml 当前版）

```
手势操作卡（bg_group_card 白卡）
├─ 右挥手势                 [开关]
│  └─ 灰底次级块（bg_sub_group）
│     ├─ 右挥操作 [上滑|下滑|点击|长按]（4 段分段）
│     └─ ▸ Params（折叠标题，点击展开/收起）
│        ├─ 滑动X位置/起点/终点/速度（右挥=滑动时）
│        ├─ 位置单位 [下拉]（点击/长按时）
│        ├─ 点击X/Y、长按X/Y、长按时长
├─ 左挥手势                 [开关]
│  └─ 同上（_l 后缀 id）
提示与反馈卡：提示开关/背景开关/提示时长
通用卡：悬浮窗/振动/语言
无障碍设置按钮 / 退出按钮
后台保活卡（电池白名单 + MIUI 自启动）
诊断卡（模型/相机/崩溃记录 + 导出日志）
```

## 7. 设备调试要点

- **禁止从屏幕底部向上 swipe**（y≈2090 起）= MIUI Home 手势，会退后台
- 设置对话框打开：主屏齿轮 tap (992,235)；关闭 keyevent 4
- uiautomator dump 会报 MIUI theme_compatibility.xml 错误但 dump 仍成功；解析 regex `text="([^"]{1,10})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"`
- 布局随参数显隐重排：每次 tap 前重新 dump 定位
- 设备偶发休眠：`svc power stayon true` + WAKEUP

## 8. git 历史摘要（本分支）

d717788 手势控制系统 → fbf7b62 P0/P1 修复 → 5ef3ca9 iOS 风格 UI → fae21d8 清理+打磨 → 0cf0b95 浅色极简+bug响应 → 2e89cf4 品牌色/面板记忆/引导插画 → 698fec6 状态栏安全/预览开关/可配置动作 → ad7a687 通知按钮（已废弃）→ 1844e7d 主屏透明开关条 → ea3f0cb 后台保活 → 3cc7bf6 参数区联动 → c1fbea0 方向独立绑定 → 03dfb69 点击/长按分离+单位 → 4ca24dc 单位下拉框+动效 → 30c3d80 操作下拉框（已还原）→ e44400a 每手势折叠参数区 → 9fbb4a9 操作/参数归位+开关联动 → a19fe25 skill+约定+MCP → 7454cc1 MCP Windows 修复
