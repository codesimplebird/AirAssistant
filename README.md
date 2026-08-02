# 隔空助手 Air Assistant

> 不触屏也能操作手机 —— 用前置摄像头识别手势，通过无障碍服务替你滑动、点击、长按。

基于 Google MediaPipe Hand Landmarker 的 Android 隔空手势控制应用。举起手对着前置摄像头：**左右挥手**滚动页面，**拇指与食指捏合**触发点击/长按/滑动。

## 功能特性

- **左右挥手**：识别右挥/左挥，可分别绑定「上滑 / 下滑 / 点击 / 长按」
- **捏合手势**：拇指与食指捏合释放触发操作，可绑定任意动作
- **全屏坐标可调**：滑动起点/终点/X 位置、点击/长按坐标均支持百分比或像素单位
- **后台持续识别**：App 退到后台由前台服务接管摄像头继续识别（悬浮窗可选预览）
- **性能模式**：自动 / 省电 / 标准 / 高响应，温度过高时自动降档降温
- **提示反馈**：识别成功/失败气泡、震动反馈（可关）、中英文切换
- **无网络依赖**：全部识别在本地完成，不联网、不上传任何数据

## 下载

APK 请到 [Releases](https://github.com/yourname/AirAssistant/releases) 页面下载。

> 无障碍服务类应用无法上架主流应用商店（Google Play / 华为 / 小米等均有严格审核），
> 因此通过 GitHub Releases 直接分发安装包。安装后请在系统「无障碍」中手动开启本应用。

## 快速开始

1. 安装 APK，授予相机权限
2. 系统设置 → 无障碍 → 开启「隔空助手」
3. 打开应用，举起手面对前置摄像头
4. 右挥手 = 上滑、左挥手 = 下滑（可在设置中修改）

### 手势说明

| 手势 | 默认动作 | 可配置 |
|---|---|---|
| 右挥手 | 上滑 | 上滑 / 下滑 / 点击 / 长按 |
| 左挥手 | 下滑 | 上滑 / 下滑 / 点击 / 长按 |
| 捏合（拇指+食指） | 点击 | 上滑 / 下滑 / 点击 / 长按 |

## 构建

### 环境要求

- JDK 17（Android Studio 自带 JDK 25 无法编译本项目 Kotlin 工具链）
- Android SDK（compileSdk 34）

### 命令行构建

```bash
# 需先设置 JAVA_HOME 指向 JDK 17
gradlew.bat app:assembleDebug --offline
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

首次构建会自动下载模型文件 `hand_landmarker.task` 到 assets（无需手动操作）。

### Release 签名

正式发布需要签名密钥：

1. 生成 keystore（**务必备份，丢失后无法发布升级包**）：
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias air -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 复制 `keystore.properties.example` 为 `keystore.properties` 并填写密码
   （该文件已被 .gitignore 排除，不会入库）

## 权限说明

| 权限 | 用途 |
|---|---|
| CAMERA | 前置摄像头手势识别（核心功能） |
| 无障碍服务 | 派发滑动/点击/长按系统手势（核心功能） |
| SYSTEM_ALERT_WINDOW | 可选：后台悬浮窗预览 |
| POST_NOTIFICATIONS | Android 13+ 前台服务通知 |

## 兼容性

- **Android 7.0（API 24）及以上**，推荐 Android 10+ 获得最佳后台体验
- 需要前置摄像头；部分厂商系统（MIUI/鸿蒙/ColorOS）会限制后台服务，请在系统设置中允许自启动/加入电池白名单
- 详细说明见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)

## 隐私

所有手势识别均在设备本地完成，不联网、不收集、不上传任何数据。
摄像头画面仅用于实时识别，不保存、不录制。详见 [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)。

## 免责声明

- 本应用通过无障碍服务模拟用户操作，请仅在你信任的设备上使用
- 手势识别存在误触发可能，请勿在输入支付密码等敏感场景使用
- 持续使用相机识别会加速耗电并导致设备发热

## 文档

- [架构与路线图](ARCHITECTURE_AND_ROADMAP.md)
- [项目总览（英文）](docs/PROJECT_OVERVIEW_en.md)
- [AI 快速上下文](docs/AI_CONTEXT_zh.md)
- [更新日志](CHANGELOG.md)

## 许可证

[Apache License 2.0](LICENSE)

本项目基于 Google [MediaPipe Hand Landmarker Android 示例](https://github.com/google-ai-edge/mediapipe-samples) 二次开发。
