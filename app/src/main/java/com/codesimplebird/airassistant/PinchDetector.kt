package com.codesimplebird.airassistant

import kotlin.math.sqrt

/** Detects a thumb/index pinch using hand-size-normalized landmark distance. */
class PinchDetector(
    var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private val releaseThreshold: Float = DEFAULT_RELEASE_THRESHOLD,
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val nowProvider: () -> Long = { android.os.SystemClock.uptimeMillis() }
) {
    enum class State {
        NONE,
        PINCHING,
        RELEASED,
        DEBOUNCED
    }

    data class Result(
        val state: State,
        val normalizedDistance: Float
    )

    private var closeFrames = 0
    private var pinching = false
    private var lastTriggerTime = 0L

    fun detect(thumbTipX: Float, thumbTipY: Float, indexTipX: Float, indexTipY: Float,
               palmWidth: Float): Result {
        if (palmWidth <= 0f) return Result(State.NONE, Float.POSITIVE_INFINITY)

        val dx = indexTipX - thumbTipX
        val dy = indexTipY - thumbTipY
        val distance = sqrt(dx * dx + dy * dy)
        val normalizedDistance = distance / palmWidth
        val now = nowProvider()

        if (!pinching) {
            if (normalizedDistance <= pinchThreshold) {
                closeFrames++
                if (closeFrames >= confirmFrames) {
                    pinching = true
                    closeFrames = 0
                    return Result(State.PINCHING, normalizedDistance)
                }
            } else {
                closeFrames = 0
            }
            return Result(State.NONE, normalizedDistance)
        }

        if (normalizedDistance < releaseThreshold) {
            return Result(State.PINCHING, normalizedDistance)
        }

        pinching = false
        closeFrames = 0
        if (lastTriggerTime > 0L && now - lastTriggerTime < debounceMs) {
            return Result(State.DEBOUNCED, normalizedDistance)
        }
        lastTriggerTime = now
        return Result(State.RELEASED, normalizedDistance)
    }

    /** Reset when the hand disappears so a partial pinch cannot click later. */
    fun reset() {
        closeFrames = 0
        pinching = false
    }

    companion object {
        /** Initial values for 4/8 tip distance divided by 5/17 palm width. */
        const val DEFAULT_PINCH_THRESHOLD = 0.35f
        const val DEFAULT_RELEASE_THRESHOLD = 0.50f
        const val DEFAULT_CONFIRM_FRAMES = 3
        const val DEFAULT_DEBOUNCE_MS = 700L
    }
}
