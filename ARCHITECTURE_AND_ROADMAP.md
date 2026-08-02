# 架构与路线图

> 面向维护者与贡献者的技术文档。历史决策与设计约束见下文「关键设计决策」。

## 总体架构

```
┌─────────────────────────────── 应用进程 ───────────────────────────────┐
│                                                                        │
│  ┌──────────────┐    ┌─────────────────────┐    ┌──────────────────┐  │
│  │ MainActivity │◄──►│ HandGestureService  │◄──►│ GestureSettings  │  │
│  │ (主界面/设置) │    │ (前台服务·后台识别)  │    │ (单例+持久化)     │  │
│  └──────┬───────┘    └─────────┬───────────┘    └──────────────────┘  │
│         │                      │                                       │
│  ┌──────▼────────┐   ┌─────────▼───────────┐                          │
│  │ CameraFragment│   │ LandmarkerManager   │  ┌────────────────────┐  │
│  │ (前台相机预览) │   │ 共享模型单例+推理线程 │  │ HandLandmarker    │  │
│  └──────┬────────┘   └─────────┬───────────┘  │ Helper（推理封装）  │  │
│         │                      │              └─────────┬──────────┘  │
│         └──────────┬───────────┘                        │             │
│              CameraX ImageAnalysis                      │             │
│                    │                                    │             │
│                    ▼                                    ▼             │
│            ┌──────────────────────────────────────────────────────┐  │
│            │  WaveDetector(挥手)  PinchDetector(捏合)              │  │
│            └───────────────────────────────┬──────────────────────┘  │
│                                            ▼                         │
│            ┌──────────────────────────────────────────────────────┐  │
│            │  GestureAccessibilityService（无障碍手势派发）        │  │
│            │  dispatchSwipe / dispatchTap / dispatchLongPress     │  │
│            └──────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

## 模块职责

| 模块 | 职责 | 关键点 |
|---|---|---|
| `MainActivity` | 主界面、设置对话框、前后台协调 | onStop→keepCameraAlive / onResume→releaseCamera；appInForeground 标记 |
| `CameraFragment` | 前台 CameraX Preview+ImageAnalysis | 分析流限 480x360（省电/高温 320x240）+ 动态跳帧 |
| `HandGestureService` | 后台前台服务（foregroundServiceType=camera） | 只绑 ImageAnalysis 不绑 Preview；悬浮窗；温度监听；动态帧率 |
| `LandmarkerManager` | 共享模型单例 + 唯一推理线程 | GPU 线程亲和；结果转发给 activeListener；lastFrameHadHand |
| `HandLandmarkerHelper` | MediaPipe 推理封装 + 手势判定入口 | 每帧：捏合检测→挥手检测；复用 Bitmap 缓冲 |
| `WaveDetector` | 挥手方向识别 | 300ms 窗口，位移>0.20、速度>0.0006/ms，可注入时钟（可测试） |
| `PinchDetector` | 捏合识别 | 指尖距/掌宽归一化，0.35/0.50 迟滞，3 帧确认，700ms 去抖 |
| `GestureSettings` | 全部配置（单例 + SharedPreferences） | 性能模式/温控/手势映射/坐标/单位 |
| `GestureAccessibilityService` | 无障碍手势派发 | dispatchGesture，坐标按 DisplayMetrics 换算 |
| `CrashHandler`/`AppLog` | 崩溃恢复与日志 | 异常退出提示 + 日志导出 |

## 数据流

```
CameraX 帧 → HandLandmarkerHelper.detectLiveStream()
  → 21 landmarks
  → 掌心 x = 平均(lm0,5,9,13,17) → WaveDetector
  → 拇指尖 lm4 / 食指尖 lm8 / 掌宽 lm5-lm17 → PinchDetector
  → 手势事件 → GestureAccessibilityService → 系统手势
```

## 前后台摄像头互斥

- 前台：`CameraFragment` 持有 Preview+ImageAnalysis
- 退后台：`MainActivity.onStop()` → `keepCameraAlive()`（仅 ImageAnalysis，无 Surface 依赖）
- 回前台：`MainActivity.onResume()` → `releaseCamera()` → Fragment 重绑
- 同一时刻只有一个摄像头绑定者（`keepCameraRequested` 竞态保护）

## 性能设计

- 单一推理线程（`visionExecutor`，中低优先级），前后台共用模型
- 分析分辨率：标准 480x360，省电/高温 320x240（`FALLBACK_RULE_CLOSEST_LOWER`）
- 动态跳帧：无手 5fps / 有手 15fps（省电 3~4/10fps，高响应 7.5/30fps）
- 温度自适应：Android 10+ `PowerManager` 温控监听，SEVERE 以上自动降档
- 每帧复用 Bitmap 缓冲，避免频繁分配

## 关键设计决策

1. **操作选择用 4 段分段**（上滑|下滑|点击|长按），下拉框方案曾被否决还原
2. **参数区归属各手势**：每个手势开关下方 = 操作分段 + 可折叠 Params 参数区，内容随操作联动
3. **点击/长按坐标按像素存储**，单位（百分比/像素）仅影响显示与步进
4. **滑动参数全局一份**，右挥/左挥/捏合三处 UI 共用并同步
5. **通知栏无按钮**（曾致死机，已废弃）；主界面顶部透明小开关条替代
6. **GPU delegate 自动回退 CPU**；模型创建必须走单线程（线程亲和 + SIGBUS 防护）
7. **禁止单独 setTargetResolution**（部分机型选成超大分辨率），必须用 ResolutionSelector+4:3
8. **方向开关关闭 = 完全忽略**（不提示、不震动、不参与冷却）
9. **悬浮窗生命周期**：前台移除、后台重建，由 `appInForeground` 标记协调
10. 命名规范：id 后缀 `_r`/`_l`/`_p`（右挥/左挥/捏合），品牌橙 `@color/brand_orange`

## 已知限制

- 摄像头固定前置
- 手势阈值需按机型微调（归一化后大部分设备可用）
- 厂商后台限制（MIUI 等）可能杀死前台服务
- 无障碍服务上架应用商店需专门合规声明
- 横屏/刘海屏未充分实测

## 路线图

### 近期（v1.x）

- [ ] 手势阈值/灵敏度设置项（当前为常量）
- [ ] 更多手势（握拳、张掌、双指）
- [ ] 真机回归矩阵（Android 7/10/13/14/15）
- [ ] 前台相机 16:9 机型适配验证

### 中期（v2.x）

- [ ] 自定义手势组合（如：捏合+移动=拖动）
- [ ] 摄像头热切换与多摄设备适配
- [ ] 性能模式按应用场景自动切换
- [ ] 屏幕方向适配完善

### 后期（v3.x）

- [ ] 独立手势算法模块（脱离 MediaPipe 示例结构）
- [ ] 无障碍合规与商店上架
- [ ] 多语言与本地化完善
