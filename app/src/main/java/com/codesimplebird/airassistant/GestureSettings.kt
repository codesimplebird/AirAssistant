package com.codesimplebird.airassistant

import android.content.Context
import android.util.Log

/**
 * 手势操作设置（进程内共享 + SharedPreferences 持久化）。
 *
 * - gestureEnabled：是否允许手势控制手机（默认开）
 * - swipeCooldownMs：滑动冷却间隔，上一次滑动后该时间内忽略新检测，
 *   防止挥手后收手回滑触发反向滑动（默认 1s，范围 0.5~3s）
 */
object GestureSettings {

    private const val PREFS_NAME = "gesture_settings"
    private const val KEY_GESTURE_ENABLED = "gesture_enabled"
    private const val KEY_SWIPE_COOLDOWN_MS = "swipe_cooldown_ms"
    private const val KEY_SHOW_HINT = "show_hint"
    private const val KEY_HINT_IN_BACKGROUND = "hint_in_background"
    private const val KEY_HINT_DURATION_MS = "hint_duration_ms"
    private const val KEY_FLOATING_WINDOW = "floating_window"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_SWIPE_X_PERCENT = "swipe_x_percent"
    private const val KEY_SWIPE_START_Y_PERCENT = "swipe_start_y_percent"
    private const val KEY_SWIPE_END_Y_PERCENT = "swipe_end_y_percent"
    private const val KEY_SWIPE_DURATION_MS = "swipe_duration_ms"
    private const val KEY_CAMERA_PREVIEW = "camera_preview"
    private const val KEY_RIGHT_WAVE_ENABLED = "right_wave_enabled"
    private const val KEY_LEFT_WAVE_ENABLED = "left_wave_enabled"
    private const val KEY_RIGHT_WAVE_ACTION = "right_wave_action"
    private const val KEY_LEFT_WAVE_ACTION = "left_wave_action"
    private const val KEY_PINCH_ENABLED = "pinch_enabled"
    private const val KEY_PINCH_ACTION = "pinch_action"
    private const val KEY_PERFORMANCE_MODE = "performance_mode"
    private const val KEY_WAVE_MIN_DISTANCE = "wave_min_distance"
    private const val KEY_WAVE_MIN_SPEED = "wave_min_speed"
    private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
    private const val KEY_CLICK_X_PX = "click_x_px"
    private const val KEY_CLICK_Y_PX = "click_y_px"
    private const val KEY_LONGPRESS_X_PX = "longpress_x_px"
    private const val KEY_LONGPRESS_Y_PX = "longpress_y_px"
    private const val KEY_LONGPRESS_DURATION_MS = "longpress_duration_ms"
    private const val KEY_POSITION_UNIT = "position_unit"
    /** 旧版本（百分比存储）迁移用 key */
    private const val KEY_CLICK_X_PERCENT_LEGACY = "click_x_percent"
    private const val KEY_CLICK_Y_PERCENT_LEGACY = "click_y_percent"

    const val DEFAULT_GESTURE_ENABLED = true
    const val DEFAULT_SWIPE_COOLDOWN_MS = 1000L
    const val MIN_SWIPE_COOLDOWN_MS = 500L
    const val MAX_SWIPE_COOLDOWN_MS = 3000L
    const val COOLDOWN_STEP_MS = 500L

    const val DEFAULT_SHOW_HINT = true
    const val DEFAULT_HINT_IN_BACKGROUND = false
    const val DEFAULT_HINT_DURATION_MS = 2500L
    const val MIN_HINT_DURATION_MS = 1500L
    const val MAX_HINT_DURATION_MS = 5000L
    const val HINT_DURATION_STEP_MS = 500L
    const val DEFAULT_FLOATING_WINDOW = false
    const val DEFAULT_VIBRATION = false

    const val PERFORMANCE_AUTO = 0
    const val PERFORMANCE_POWER_SAVING = 1
    const val PERFORMANCE_STANDARD = 2
    const val PERFORMANCE_HIGH_RESPONSE = 3
    const val DEFAULT_PERFORMANCE_MODE = PERFORMANCE_AUTO

    /** Android PowerManager thermal status: SEVERE and above. */
    private const val THERMAL_STATUS_SEVERE = 3

    /** 滑动 X 位置（屏幕宽度百分比，居中=50） */
    const val DEFAULT_SWIPE_X_PERCENT = 50
    const val MIN_SWIPE_X_PERCENT = 5
    const val MAX_SWIPE_X_PERCENT = 95
    const val SWIPE_POSITION_STEP = 5

    /** 滑动起点 Y（屏幕底部百分比，向上/下滑的起始侧） */
    const val DEFAULT_SWIPE_START_Y_PERCENT = 85
    const val MIN_SWIPE_START_Y_PERCENT = 60
    const val MAX_SWIPE_START_Y_PERCENT = 95

    /** 滑动终点 Y（屏幕顶部百分比，向上/下滑的结束侧） */
    const val DEFAULT_SWIPE_END_Y_PERCENT = 15
    const val MIN_SWIPE_END_Y_PERCENT = 5
    const val MAX_SWIPE_END_Y_PERCENT = 40

    /** 滑动时长 ms（越小越快） */
    const val DEFAULT_SWIPE_DURATION_MS = 400L
    const val MIN_SWIPE_DURATION_MS = 100L
    const val MAX_SWIPE_DURATION_MS = 1000L
    const val SWIPE_DURATION_STEP_MS = 50L

    const val DEFAULT_CAMERA_PREVIEW = true
    const val DEFAULT_RIGHT_WAVE_ENABLED = true
    const val DEFAULT_LEFT_WAVE_ENABLED = true
    const val DEFAULT_PINCH_ENABLED = false
    /** 挥手操作：0=上滑 1=下滑 2=点击 3=长按 */
    const val ACTION_SWIPE_UP = 0
    const val ACTION_SWIPE_DOWN = 1
    const val ACTION_CLICK = 2
    const val ACTION_LONG_PRESS = 3
    const val DEFAULT_RIGHT_WAVE_ACTION = ACTION_SWIPE_UP
    const val DEFAULT_LEFT_WAVE_ACTION = ACTION_SWIPE_DOWN
    /** 捏合操作默认值：点击 */
    const val DEFAULT_PINCH_ACTION = ACTION_CLICK
    /** 挥手幅度（归一化位移，越大越迟钝） */
    const val DEFAULT_WAVE_MIN_DISTANCE = 0.20f
    const val MIN_WAVE_MIN_DISTANCE = 0.10f
    const val MAX_WAVE_MIN_DISTANCE = 0.30f
    const val WAVE_MIN_DISTANCE_STEP = 0.05f
    /** 挥手速度阈值（/ms，越小越灵敏） */
    const val DEFAULT_WAVE_MIN_SPEED = 0.0006f
    const val MIN_WAVE_MIN_SPEED = 0.0003f
    const val MAX_WAVE_MIN_SPEED = 0.0009f
    const val WAVE_MIN_SPEED_STEP = 0.0001f
    /** 捏合识别阈值（归一化距离，越小越难触发） */
    const val DEFAULT_PINCH_THRESHOLD = 0.35f
    const val MIN_PINCH_THRESHOLD = 0.25f
    const val MAX_PINCH_THRESHOLD = 0.45f
    const val PINCH_THRESHOLD_STEP = 0.05f
    /** 位置单位：0=百分比 1=像素坐标 */
    const val UNIT_PERCENT = 0
    const val UNIT_PIXEL = 1
    const val DEFAULT_POSITION_UNIT = UNIT_PERCENT
    /** 点击/长按位置默认值（像素，1080x2340 屏幕下 50% = 540/1170） */
    const val DEFAULT_CLICK_X_PX = 540
    const val DEFAULT_CLICK_Y_PX = 1170
    const val DEFAULT_LONGPRESS_X_PX = 540
    const val DEFAULT_LONGPRESS_Y_PX = 1170
    /** 像素模式步进 */
    const val POSITION_STEP_PX = 50
    const val DEFAULT_LONGPRESS_DURATION_MS = 800L
    const val MIN_LONGPRESS_DURATION_MS = 400L
    const val MAX_LONGPRESS_DURATION_MS = 1500L
    const val LONGPRESS_DURATION_STEP_MS = 100L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var gestureEnabled: Boolean = DEFAULT_GESTURE_ENABLED

    @Volatile
    var swipeCooldownMs: Long = DEFAULT_SWIPE_COOLDOWN_MS

    @Volatile
    var showHint: Boolean = DEFAULT_SHOW_HINT

    @Volatile
    var hintInBackground: Boolean = DEFAULT_HINT_IN_BACKGROUND

    @Volatile
    var hintDurationMs: Long = DEFAULT_HINT_DURATION_MS

    @Volatile
    var floatingWindowEnabled: Boolean = DEFAULT_FLOATING_WINDOW

    @Volatile
    var vibrationEnabled: Boolean = DEFAULT_VIBRATION

    /** 性能模式：自动 / 省电 / 标准 / 高响应 */
    @Volatile
    var performanceMode: Int = DEFAULT_PERFORMANCE_MODE

    /** 由前台服务更新的系统温度等级，低版本保持 0。 */
    @Volatile
    private var thermalStatus: Int = 0

    @Volatile
    var swipeXPercent: Int = DEFAULT_SWIPE_X_PERCENT

    @Volatile
    var swipeStartYPercent: Int = DEFAULT_SWIPE_START_Y_PERCENT

    @Volatile
    var swipeEndYPercent: Int = DEFAULT_SWIPE_END_Y_PERCENT

    @Volatile
    var swipeDurationMs: Long = DEFAULT_SWIPE_DURATION_MS

    /** 前台相机画面开关（关闭后前台显示占位，后台服务继续识别） */
    @Volatile
    var cameraPreviewEnabled: Boolean = DEFAULT_CAMERA_PREVIEW

    /** 右挥开关 */
    @Volatile
    var rightWaveEnabled: Boolean = DEFAULT_RIGHT_WAVE_ENABLED

    /** 左挥开关 */
    @Volatile
    var leftWaveEnabled: Boolean = DEFAULT_LEFT_WAVE_ENABLED

    /** 捏合手势开关；捏合释放后执行一次操作 */
    @Volatile
    var pinchEnabled: Boolean = DEFAULT_PINCH_ENABLED

    /** 捏合操作（0=上滑 1=下滑 2=点击 3=长按） */
    @Volatile
    var pinchAction: Int = DEFAULT_PINCH_ACTION

    /** 挥手幅度阈值（归一化位移） */
    @Volatile
    var waveMinDistance: Float = DEFAULT_WAVE_MIN_DISTANCE

    /** 挥手速度阈值（/ms） */
    @Volatile
    var waveMinSpeed: Float = DEFAULT_WAVE_MIN_SPEED

    /** 捏合识别阈值（归一化距离） */
    @Volatile
    var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD

    /** 右挥操作（0=上滑 1=下滑 2=点击 3=长按） */
    @Volatile
    var rightWaveAction: Int = DEFAULT_RIGHT_WAVE_ACTION

    /** 左挥操作（0=上滑 1=下滑 2=点击 3=长按） */
    @Volatile
    var leftWaveAction: Int = DEFAULT_LEFT_WAVE_ACTION

    /** 点击 X 位置（像素） */
    @Volatile
    var clickXPx: Int = DEFAULT_CLICK_X_PX

    /** 点击 Y 位置（像素） */
    @Volatile
    var clickYPx: Int = DEFAULT_CLICK_Y_PX

    /** 长按 X 位置（像素） */
    @Volatile
    var longPressXPx: Int = DEFAULT_LONGPRESS_X_PX

    /** 长按 Y 位置（像素） */
    @Volatile
    var longPressYPx: Int = DEFAULT_LONGPRESS_Y_PX

    /** 位置单位：0=百分比 1=像素坐标 */
    @Volatile
    var positionUnit: Int = DEFAULT_POSITION_UNIT

    /** 长按保持时长 ms */
    @Volatile
    var longPressDurationMs: Long = DEFAULT_LONGPRESS_DURATION_MS

    /** 从 SharedPreferences 加载（App 启动与服务创建时调用，幂等） */
    fun load(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gestureEnabled = prefs.getBoolean(KEY_GESTURE_ENABLED, DEFAULT_GESTURE_ENABLED)
        swipeCooldownMs = prefs.getLong(KEY_SWIPE_COOLDOWN_MS, DEFAULT_SWIPE_COOLDOWN_MS)
        showHint = prefs.getBoolean(KEY_SHOW_HINT, DEFAULT_SHOW_HINT)
        hintInBackground = prefs.getBoolean(KEY_HINT_IN_BACKGROUND, DEFAULT_HINT_IN_BACKGROUND)
        hintDurationMs = prefs.getLong(KEY_HINT_DURATION_MS, DEFAULT_HINT_DURATION_MS)
        floatingWindowEnabled = prefs.getBoolean(KEY_FLOATING_WINDOW, DEFAULT_FLOATING_WINDOW)
        vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, DEFAULT_VIBRATION)
        swipeXPercent = prefs.getInt(KEY_SWIPE_X_PERCENT, DEFAULT_SWIPE_X_PERCENT)
        swipeStartYPercent = prefs.getInt(KEY_SWIPE_START_Y_PERCENT, DEFAULT_SWIPE_START_Y_PERCENT)
        swipeEndYPercent = prefs.getInt(KEY_SWIPE_END_Y_PERCENT, DEFAULT_SWIPE_END_Y_PERCENT)
        swipeDurationMs = prefs.getLong(KEY_SWIPE_DURATION_MS, DEFAULT_SWIPE_DURATION_MS)
        cameraPreviewEnabled = prefs.getBoolean(KEY_CAMERA_PREVIEW, DEFAULT_CAMERA_PREVIEW)
        rightWaveEnabled = prefs.getBoolean(KEY_RIGHT_WAVE_ENABLED, DEFAULT_RIGHT_WAVE_ENABLED)
        leftWaveEnabled = prefs.getBoolean(KEY_LEFT_WAVE_ENABLED, DEFAULT_LEFT_WAVE_ENABLED)
        pinchEnabled = prefs.getBoolean(KEY_PINCH_ENABLED, DEFAULT_PINCH_ENABLED)
        pinchAction = prefs.getInt(KEY_PINCH_ACTION, DEFAULT_PINCH_ACTION)
        waveMinDistance = prefs.getFloat(
            KEY_WAVE_MIN_DISTANCE, DEFAULT_WAVE_MIN_DISTANCE
        ).coerceIn(MIN_WAVE_MIN_DISTANCE, MAX_WAVE_MIN_DISTANCE)
        waveMinSpeed = prefs.getFloat(
            KEY_WAVE_MIN_SPEED, DEFAULT_WAVE_MIN_SPEED
        ).coerceIn(MIN_WAVE_MIN_SPEED, MAX_WAVE_MIN_SPEED)
        pinchThreshold = prefs.getFloat(
            KEY_PINCH_THRESHOLD, DEFAULT_PINCH_THRESHOLD
        ).coerceIn(MIN_PINCH_THRESHOLD, MAX_PINCH_THRESHOLD)
        performanceMode = prefs.getInt(
            KEY_PERFORMANCE_MODE, DEFAULT_PERFORMANCE_MODE
        ).coerceIn(PERFORMANCE_AUTO, PERFORMANCE_HIGH_RESPONSE)
        rightWaveAction = prefs.getInt(KEY_RIGHT_WAVE_ACTION, DEFAULT_RIGHT_WAVE_ACTION)
        leftWaveAction = prefs.getInt(KEY_LEFT_WAVE_ACTION, DEFAULT_LEFT_WAVE_ACTION)
        positionUnit = prefs.getInt(KEY_POSITION_UNIT, DEFAULT_POSITION_UNIT)
        longPressDurationMs = prefs.getLong(
            KEY_LONGPRESS_DURATION_MS, DEFAULT_LONGPRESS_DURATION_MS
        )
        // 旧版本按百分比存储：迁移为像素值
        val w = screenWidthPx()
        val h = screenHeightPx()
        val legacyX = prefs.getInt(KEY_CLICK_X_PERCENT_LEGACY, -1)
        val legacyY = prefs.getInt(KEY_CLICK_Y_PERCENT_LEGACY, -1)
        clickXPx = if (prefs.contains(KEY_CLICK_X_PX)) prefs.getInt(KEY_CLICK_X_PX, DEFAULT_CLICK_X_PX)
        else if (legacyX >= 0) legacyX * w / 100 else DEFAULT_CLICK_X_PX
        clickYPx = if (prefs.contains(KEY_CLICK_Y_PX)) prefs.getInt(KEY_CLICK_Y_PX, DEFAULT_CLICK_Y_PX)
        else if (legacyY >= 0) legacyY * h / 100 else DEFAULT_CLICK_Y_PX
        longPressXPx = if (prefs.contains(KEY_LONGPRESS_X_PX))
            prefs.getInt(KEY_LONGPRESS_X_PX, DEFAULT_LONGPRESS_X_PX) else clickXPx
        longPressYPx = if (prefs.contains(KEY_LONGPRESS_Y_PX))
            prefs.getInt(KEY_LONGPRESS_Y_PX, DEFAULT_LONGPRESS_Y_PX) else clickYPx
        Log.d(
            "GestureSettings",
            "loaded: enabled=$gestureEnabled cooldownMs=$swipeCooldownMs " +
                    "showHint=$showHint hintBg=$hintInBackground duration=$hintDurationMs " +
                    "floating=$floatingWindowEnabled vibration=$vibrationEnabled " +
                    "swipeX=$swipeXPercent startY=$swipeStartYPercent endY=$swipeEndYPercent " +
                    "swipeDur=$swipeDurationMs rightAction=$rightWaveAction leftAction=$leftWaveAction " +
                    "unit=$positionUnit click=($clickXPx,$clickYPx) long=($longPressXPx,$longPressYPx)"
        )
    }

    /** 屏幕宽度像素（无 context 时回退默认值） */
    fun screenWidthPx(): Int =
        appContext?.resources?.displayMetrics?.widthPixels ?: 1080

    /** 屏幕高度像素（无 context 时回退默认值） */
    fun screenHeightPx(): Int =
        appContext?.resources?.displayMetrics?.heightPixels ?: 2340

    /** 按当前单位显示位置值：百分比模式返回 %，像素模式返回 px */
    fun displayValue(px: Int, axisMaxPx: Int): String {
        return if (positionUnit == UNIT_PIXEL) {
            "$px"
        } else {
            "${(px * 100f / axisMaxPx).toInt()}%"
        }
    }

    /** 按当前单位步进调整位置值（百分比模式 ±5%，像素模式 ±50px） */
    fun stepPosition(currentPx: Int, axisMaxPx: Int, delta: Int): Int {
        val step = if (positionUnit == UNIT_PIXEL) {
            POSITION_STEP_PX
        } else {
            axisMaxPx * SWIPE_POSITION_STEP / 100
        }
        return (currentPx + delta * step).coerceIn(0, axisMaxPx)
    }

    fun updateGestureEnabled(enabled: Boolean) {
        gestureEnabled = enabled
        save()
    }

    fun updateSwipeCooldownMs(ms: Long) {
        swipeCooldownMs = ms.coerceIn(MIN_SWIPE_COOLDOWN_MS, MAX_SWIPE_COOLDOWN_MS)
        save()
    }

    fun updateShowHint(enabled: Boolean) {
        showHint = enabled
        save()
    }

    fun updateHintInBackground(enabled: Boolean) {
        hintInBackground = enabled
        save()
    }

    fun updateHintDurationMs(ms: Long) {
        hintDurationMs = ms.coerceIn(MIN_HINT_DURATION_MS, MAX_HINT_DURATION_MS)
        save()
    }

    fun updateFloatingWindowEnabled(enabled: Boolean) {
        floatingWindowEnabled = enabled
        save()
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        save()
    }

    fun updatePerformanceMode(mode: Int) {
        performanceMode = mode.coerceIn(PERFORMANCE_AUTO, PERFORMANCE_HIGH_RESPONSE)
        save()
    }

    fun updateThermalStatus(status: Int) {
        thermalStatus = status
    }

    /** 当前分析帧步长：相机约 30fps 时，2≈15fps、3≈10fps、6≈5fps。 */
    fun analysisStride(hasHand: Boolean): Int {
        if (thermalStatus >= THERMAL_STATUS_SEVERE) {
            return if (hasHand) 3 else 8
        }
        return when (performanceMode) {
            PERFORMANCE_POWER_SAVING -> if (hasHand) 3 else 8
            PERFORMANCE_HIGH_RESPONSE -> if (hasHand) 1 else 4
            else -> if (hasHand) 2 else 6
        }
    }

    /** 温度过高或手动省电时，分析流使用更低分辨率。 */
    fun useLowAnalysisResolution(): Boolean =
        performanceMode == PERFORMANCE_POWER_SAVING ||
            thermalStatus >= THERMAL_STATUS_SEVERE

    fun updateSwipeXPercent(percent: Int) {
        swipeXPercent = percent.coerceIn(MIN_SWIPE_X_PERCENT, MAX_SWIPE_X_PERCENT)
        save()
    }

    fun updateSwipeStartYPercent(percent: Int) {
        swipeStartYPercent = percent.coerceIn(MIN_SWIPE_START_Y_PERCENT, MAX_SWIPE_START_Y_PERCENT)
        save()
    }

    fun updateSwipeEndYPercent(percent: Int) {
        swipeEndYPercent = percent.coerceIn(MIN_SWIPE_END_Y_PERCENT, MAX_SWIPE_END_Y_PERCENT)
        save()
    }

    fun updateSwipeDurationMs(ms: Long) {
        swipeDurationMs = ms.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        save()
    }

    fun updateCameraPreviewEnabled(enabled: Boolean) {
        cameraPreviewEnabled = enabled
        save()
    }

    fun updateRightWaveEnabled(enabled: Boolean) {
        rightWaveEnabled = enabled
        save()
    }

    fun updateLeftWaveEnabled(enabled: Boolean) {
        leftWaveEnabled = enabled
        save()
    }

    fun updatePinchEnabled(enabled: Boolean) {
        pinchEnabled = enabled
        save()
    }

    fun updatePinchAction(action: Int) {
        pinchAction = action.coerceIn(0, 3)
        save()
    }

    fun updateWaveMinDistance(value: Float) {
        waveMinDistance = value.coerceIn(MIN_WAVE_MIN_DISTANCE, MAX_WAVE_MIN_DISTANCE)
        save()
    }

    fun updateWaveMinSpeed(value: Float) {
        waveMinSpeed = value.coerceIn(MIN_WAVE_MIN_SPEED, MAX_WAVE_MIN_SPEED)
        save()
    }

    fun updatePinchThreshold(value: Float) {
        pinchThreshold = value.coerceIn(MIN_PINCH_THRESHOLD, MAX_PINCH_THRESHOLD)
        save()
    }

    fun updateRightWaveAction(action: Int) {
        rightWaveAction = action.coerceIn(0, 3)
        save()
    }

    fun updateLeftWaveAction(action: Int) {
        leftWaveAction = action.coerceIn(0, 3)
        save()
    }

    fun updateClickXPx(px: Int) {
        clickXPx = px.coerceIn(0, screenWidthPx())
        save()
    }

    fun updateClickYPx(px: Int) {
        clickYPx = px.coerceIn(0, screenHeightPx())
        save()
    }

    fun updateLongPressXPx(px: Int) {
        longPressXPx = px.coerceIn(0, screenWidthPx())
        save()
    }

    fun updateLongPressYPx(px: Int) {
        longPressYPx = px.coerceIn(0, screenHeightPx())
        save()
    }

    fun updatePositionUnit(unit: Int) {
        positionUnit = unit.coerceIn(0, 1)
        save()
    }

    fun updateLongPressDurationMs(ms: Long) {
        longPressDurationMs = ms.coerceIn(
            MIN_LONGPRESS_DURATION_MS, MAX_LONGPRESS_DURATION_MS
        )
        save()
    }

    private fun save() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_GESTURE_ENABLED, gestureEnabled)
            ?.putLong(KEY_SWIPE_COOLDOWN_MS, swipeCooldownMs)
            ?.putBoolean(KEY_SHOW_HINT, showHint)
            ?.putBoolean(KEY_HINT_IN_BACKGROUND, hintInBackground)
            ?.putLong(KEY_HINT_DURATION_MS, hintDurationMs)
            ?.putBoolean(KEY_FLOATING_WINDOW, floatingWindowEnabled)
            ?.putBoolean(KEY_VIBRATION, vibrationEnabled)
            ?.putInt(KEY_SWIPE_X_PERCENT, swipeXPercent)
            ?.putInt(KEY_SWIPE_START_Y_PERCENT, swipeStartYPercent)
            ?.putInt(KEY_SWIPE_END_Y_PERCENT, swipeEndYPercent)
            ?.putLong(KEY_SWIPE_DURATION_MS, swipeDurationMs)
            ?.putBoolean(KEY_CAMERA_PREVIEW, cameraPreviewEnabled)
            ?.putBoolean(KEY_RIGHT_WAVE_ENABLED, rightWaveEnabled)
            ?.putBoolean(KEY_LEFT_WAVE_ENABLED, leftWaveEnabled)
            ?.putBoolean(KEY_PINCH_ENABLED, pinchEnabled)
            ?.putInt(KEY_PINCH_ACTION, pinchAction)
            ?.putFloat(KEY_WAVE_MIN_DISTANCE, waveMinDistance)
            ?.putFloat(KEY_WAVE_MIN_SPEED, waveMinSpeed)
            ?.putFloat(KEY_PINCH_THRESHOLD, pinchThreshold)
            ?.putInt(KEY_PERFORMANCE_MODE, performanceMode)
            ?.putInt(KEY_RIGHT_WAVE_ACTION, rightWaveAction)
            ?.putInt(KEY_LEFT_WAVE_ACTION, leftWaveAction)
            ?.putInt(KEY_CLICK_X_PX, clickXPx)
            ?.putInt(KEY_CLICK_Y_PX, clickYPx)
            ?.putInt(KEY_LONGPRESS_X_PX, longPressXPx)
            ?.putInt(KEY_LONGPRESS_Y_PX, longPressYPx)
            ?.putInt(KEY_POSITION_UNIT, positionUnit)
            ?.putLong(KEY_LONGPRESS_DURATION_MS, longPressDurationMs)
            ?.apply()
    }
}
