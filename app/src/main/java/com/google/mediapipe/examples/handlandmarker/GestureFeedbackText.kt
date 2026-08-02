package com.google.mediapipe.examples.handlandmarker

import android.content.Context

/**
 * 挥手检测结果提示文案（前台 TextView 与后台 Toast 共用）。
 */
object GestureFeedbackText {

    fun build(
        context: Context,
        direction: WaveDetector.Direction,
        effective: Boolean,
        reason: String
    ): String {
        val dirText = when (direction) {
            WaveDetector.Direction.RIGHT -> context.getString(R.string.feedback_right_wave)
            WaveDetector.Direction.LEFT -> context.getString(R.string.feedback_left_wave)
            WaveDetector.Direction.NONE -> return ""
        }
        return buildString {
            append(if (effective) "✅ " else "⚠️ ")
            append(dirText)
            when {
                effective -> append("  ").append(context.getString(R.string.feedback_applied))
                reason == "cooldown" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_cooldown))
                    .append("）")
                reason == "disabled" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_disabled))
                    .append("）")
                reason == "accessibility_off" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_accessibility))
                    .append("）")
            }
        }
    }

    fun buildPinch(context: Context, effective: Boolean, reason: String): String {
        val actionText = when (GestureSettings.pinchAction) {
            GestureSettings.ACTION_SWIPE_UP -> context.getString(R.string.feedback_pinch_up)
            GestureSettings.ACTION_SWIPE_DOWN -> context.getString(R.string.feedback_pinch_down)
            GestureSettings.ACTION_LONG_PRESS -> context.getString(R.string.feedback_pinch_longpress)
            else -> context.getString(R.string.feedback_pinch_click)
        }
        return buildString {
            append(if (effective) "✅ " else "⚠️ ")
            append(actionText)
            when {
                effective -> append("  ").append(context.getString(R.string.feedback_applied))
                reason == "cooldown" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_cooldown))
                    .append("）")
                reason == "disabled" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_disabled))
                    .append("）")
                reason == "accessibility_off" -> append("（")
                    .append(context.getString(R.string.feedback_not_applied_accessibility))
                    .append("）")
            }
        }
    }
}
