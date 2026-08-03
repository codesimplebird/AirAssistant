package com.codesimplebird.airassistant

import android.os.SystemClock

/**
 * 挥手方向检测（滑动窗口 + 位移/速度阈值）。
 *
 * 阈值为可配置项（构造参数带默认值）：
 * - windowMs=300：轨迹保留窗口
 * - minDistance=0.20：触发所需最小归一化位移（介于 0.15 灵敏与 0.25 迟钝之间）
 * - minSpeed=0.0006（/ms）：触发所需最小速度
 */
class WaveDetector(
    private val windowMs: Long = 300L,
    var minDistance: Float = 0.20f,
    var minSpeed: Float = 0.0006f,
    /** 时间源，测试时注入可控时钟 */
    private val nowProvider: () -> Long = { SystemClock.uptimeMillis() }
) {

    enum class Direction {
        LEFT,
        RIGHT,
        NONE
    }

    enum class State {
        NONE,
        DETECTED,
        DEBOUNCED
    }

    data class Point(
        val x: Float,
        val time: Long
    )

    data class WaveResult(
        val direction: Direction,
        val state: State
    )

    private val history = ArrayDeque<Point>()

    private var lastTriggerTime = 0L

    /** 触发去抖间隔（ms）。由 GestureSettings 动态设置，防止收手回滑误触发 */
    var debounceMs: Long = 800L

    fun detect(palmX: Float): WaveResult {
        val now = nowProvider()

        // 保存当前点
        history.add(Point(palmX, now))

        // 只保留 windowMs 内数据
        while (history.isNotEmpty() && now - history.first().time > windowMs) {
            history.removeFirst()
        }

        // 数据太少
        if (history.size < 3) {
            return WaveResult(Direction.NONE, State.NONE)
        }

        val first = history.first()
        val last = history.last()

        val distance = last.x - first.x
        val duration = last.time - first.time

        if (duration <= 0) {
            return WaveResult(Direction.NONE, State.NONE)
        }

        // 速度
        val speed = kotlin.math.abs(distance) / duration.toFloat()

        /*
          判断挥手:
          移动距离 > minDistance
          速度 > minSpeed
        */
        val direction = when {
            distance > minDistance && speed > minSpeed -> Direction.RIGHT
            distance < -minDistance && speed > minSpeed -> Direction.LEFT
            else -> Direction.NONE
        }

        if (direction == Direction.NONE) {
            return WaveResult(Direction.NONE, State.NONE)
        }

        // 防止连续触发：冷却期内返回 DEBOUNCED，供界面提示"未生效：间隔未到"。
        // lastTriggerTime == 0 表示从未触发过，跳过冷却判断
        if (lastTriggerTime > 0L && now - lastTriggerTime < debounceMs) {
            return WaveResult(direction, State.DEBOUNCED)
        }

        lastTriggerTime = now
        history.clear()
        return WaveResult(direction, State.DETECTED)
    }

    /** 清空轨迹与冷却（手消失 / 捏合 / 方向被关闭时调用）。 */
    fun reset() {
        history.clear()
        lastTriggerTime = 0L
    }
}
