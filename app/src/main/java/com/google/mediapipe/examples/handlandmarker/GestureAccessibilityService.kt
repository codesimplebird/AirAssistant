package com.google.mediapipe.examples.handlandmarker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GestureAccessibilityService : AccessibilityService() {

    /** 手势派发必须切回主线程：dispatchGesture 是同步 binder 调用，
     * 直接放在 visionExecutor（推理线程）会阻塞后续所有帧推理 */
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {

        /**
         * 必须 @Volatile：onServiceConnected/onDestroy 在系统 binder 线程回调，
         * 而 isConnected() 与手势派发在 visionExecutor 线程读取，
         * 普通 var 在弱内存模型下无可见性保证。
         */
        @Volatile
        var instance: GestureAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun swipeUp() {
            // 用局部引用，避免检查后、调用前 instance 被 onDestroy 置空
            val svc = instance
            if (svc == null) {
                Log.w("HandTest", "swipeUp FAILED: AccessibilityService not enabled!")
                return
            }
            Log.d("HandTest", "Executing swipeUp")
            svc.dispatchSwipe(true)
        }

        fun swipeDown() {
            val svc = instance
            if (svc == null) {
                Log.w("HandTest", "swipeDown FAILED: AccessibilityService not enabled!")
                return
            }
            Log.d("HandTest", "Executing swipeDown")
            svc.dispatchSwipe(false)
        }

        /** 单击（手势触发点击操作） */
        fun click() {
            val svc = instance
            if (svc == null) {
                Log.w("HandTest", "click FAILED: AccessibilityService not enabled!")
                return
            }
            Log.d("HandTest", "Executing click")
            svc.dispatchTap(120)
        }

        /** 长按（手势触发长按操作） */
        fun longPress() {
            val svc = instance
            if (svc == null) {
                Log.w("HandTest", "longPress FAILED: AccessibilityService not enabled!")
                return
            }
            Log.d("HandTest", "Executing longPress")
            svc.dispatchLongPress()
        }

    }

    /** 由推理线程调用；内部切主线程执行实际手势 */
    private fun dispatchSwipe(up: Boolean) {
        mainHandler.post { performSwipe(up) }
    }

    /** 由推理线程调用；内部切主线程执行点击 */
    private fun dispatchTap(durationMs: Long) {
        mainHandler.post { performTap(durationMs) }
    }

    /** 由推理线程调用；内部切主线程执行长按（长按位置/时长可配置） */
    private fun dispatchLongPress() {
        mainHandler.post {
            performTapAt(
                GestureSettings.longPressXPx.toFloat(),
                GestureSettings.longPressYPx.toFloat(),
                GestureSettings.longPressDurationMs
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("GestureService", "Accessibility Connected")
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
    }

    override fun onInterrupt() {
    }

    /**
     * 用户在系统设置中关闭无障碍服务时，系统解绑服务。
     * 必须清空 instance，否则 isConnected() 假阳性：
     * 界面显示"已生效"但 dispatchGesture 实际失败。
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("GestureService", "Accessibility Unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Log.d("GestureService", "Accessibility Destroyed")
        super.onDestroy()
    }

    /**
     * 屏幕内滑动：起点/终点/X 位置/时长全部取自 GestureSettings（可配置），
     * 按实际屏幕尺寸换算，适配不同分辨率与横竖屏。
     */
    private fun performSwipe(
        up: Boolean
    ) {

        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * GestureSettings.swipeXPercent / 100f
        val yStart = metrics.heightPixels * GestureSettings.swipeStartYPercent / 100f
        val yEnd = metrics.heightPixels * GestureSettings.swipeEndYPercent / 100f
        val duration = GestureSettings.swipeDurationMs

        val path = Path()

        if (up) {
            // 从下往上滑
            path.moveTo(x, yStart)
            path.lineTo(x, yEnd)
        } else {
            // 从上往下滑
            path.moveTo(x, yEnd)
            path.lineTo(x, yStart)
        }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )

    }

    /**
     * 屏幕点击：保持按压 durationMs，位置取自点击 X/Y 配置（像素）。
     */
    private fun performTap(durationMs: Long) {
        performTapAt(
            GestureSettings.clickXPx.toFloat(),
            GestureSettings.clickYPx.toFloat(),
            durationMs
        )
    }

    /**
     * 在指定像素坐标执行点击/长按。
     */
    private fun performTapAt(x: Float, y: Float, durationMs: Long) {
        val path = Path()
        path.moveTo(x, y)

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMs
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

}
