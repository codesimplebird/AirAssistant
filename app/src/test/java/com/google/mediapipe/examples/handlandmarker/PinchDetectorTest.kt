package com.google.mediapipe.examples.handlandmarker

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchDetectorTest {

    private class FakeClock {
        var now = 0L
    }

    @Test
    fun `three close frames start pinch and release triggers once`() {
        val clock = FakeClock()
        val detector = PinchDetector(nowProvider = { clock.now })

        clock.now = 0L
        assertEquals(PinchDetector.State.NONE, detector.detect(0f, 0f, 0.3f, 0f, 1f).state)
        clock.now = 30L
        assertEquals(PinchDetector.State.NONE, detector.detect(0f, 0f, 0.3f, 0f, 1f).state)
        clock.now = 60L
        assertEquals(PinchDetector.State.PINCHING, detector.detect(0f, 0f, 0.3f, 0f, 1f).state)
        clock.now = 100L
        assertEquals(PinchDetector.State.RELEASED, detector.detect(0f, 0f, 0.6f, 0f, 1f).state)
    }

    @Test
    fun `distance in hysteresis zone keeps pinch active`() {
        val clock = FakeClock()
        val detector = PinchDetector(nowProvider = { clock.now })
        repeat(3) { detector.detect(0f, 0f, 0.3f, 0f, 1f) }

        assertEquals(PinchDetector.State.PINCHING, detector.detect(0f, 0f, 0.4f, 0f, 1f).state)
    }

    @Test
    fun `second release during debounce is ignored`() {
        val clock = FakeClock()
        val detector = PinchDetector(nowProvider = { clock.now })
        repeat(3) { detector.detect(0f, 0f, 0.3f, 0f, 1f) }
        clock.now = 100L
        assertEquals(PinchDetector.State.RELEASED, detector.detect(0f, 0f, 0.6f, 0f, 1f).state)
        repeat(3) { detector.detect(0f, 0f, 0.3f, 0f, 1f) }
        clock.now = 500L
        assertEquals(PinchDetector.State.DEBOUNCED, detector.detect(0f, 0f, 0.6f, 0f, 1f).state)
    }

    @Test
    fun `invalid palm width does not trigger`() {
        val detector = PinchDetector()
        assertEquals(
            PinchDetector.State.NONE,
            detector.detect(0f, 0f, 0f, 0f, 0f).state
        )
    }
}
