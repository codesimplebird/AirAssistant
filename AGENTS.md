# 项目工作约定（Android 手势 App 开发）

## 需求理解（最重要）

- 收到需求后**先复述理解**（说明将如何实现、改动哪些文件/UI），关键歧义用 question 工具确认后再动手
- **只实现用户明确要求的内容**：不自行扩展功能、不"顺手"重构无关代码、不改动用户没提的 UI/逻辑
- UI/交互类需求先确认三要素：**在哪里、什么形态、什么行为**
- 用户说"先别操作/先理解"时，只输出理解与计划，不执行任何改动

## 实施规范

- 修改前先读相关文件（XML/Kotlin），遵循现有命名与风格（id 后缀 _r/_l、@color/brand_orange 等）
- 每步改动后构建验证：`gradlew.bat app:assembleDebug --offline`（android 目录）
- 布局/交互改动必须真机验证后再收尾
- XML 文件保持 UTF-8 无 BOM

## 真机验证

- adb：`%ANDROID_HOME%\platform-tools\adb.exe`（本机 SDK platform-tools）
- 详细流程见 skill `android-device-debug`（uiautomator 定位、禁止底部 swipe、prefs 验证等）
- 验证结束恢复测试前默认值（右挥=上滑、左挥=下滑、单位=百分比、两开关开）

## 收尾

- 完成并验证后提交 git（commit message 简洁描述改动），向用户汇报验证结果
- 未经用户要求不提交、不推送
