package com.google.mediapipe.examples.handlandmarker

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveDetectorTest {

    private class FakeClock {
        var now = 0L
    }

    private fun detector(clock: FakeClock) = WaveDetector(nowProvider = { clock.now })

    /** 按 (time, x) 序列喂点，返回每次 detect 的结果 */
    private fun feed(
        detector: WaveDetector,
        clock: FakeClock,
        points: List<Pair<Long, Float>>
    ): List<WaveDetector.WaveResult> = points.map { (t, x) ->
        clock.now = t
        detector.detect(x)
    }

    @Test
    fun `right wave triggers DETECTED RIGHT`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(d, clock, listOf(0L to 0.2f, 30L to 0.35f, 60L to 0.5f))

        assertEquals(WaveDetector.State.NONE, results[0].state)
        assertEquals(WaveDetector.State.NONE, results[1].state)
        assertEquals(WaveDetector.Direction.RIGHT, results[2].direction)
        assertEquals(WaveDetector.State.DETECTED, results[2].state)
    }

    @Test
    fun `left wave triggers DETECTED LEFT`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(d, clock, listOf(0L to 0.7f, 30L to 0.55f, 60L to 0.4f))

        assertEquals(WaveDetector.Direction.LEFT, results[2].direction)
        assertEquals(WaveDetector.State.DETECTED, results[2].state)
    }

    @Test
    fun `fewer than 3 points returns NONE`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(d, clock, listOf(0L to 0.2f, 30L to 0.35f))

        assertEquals(WaveDetector.State.NONE, results[1].state)
    }

    @Test
    fun `displacement below threshold returns NONE`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(d, clock, listOf(0L to 0.2f, 30L to 0.25f, 60L to 0.3f))

        assertEquals(WaveDetector.State.NONE, results[2].state)
    }

    @Test
    fun `speed below threshold returns NONE`() {
        val clock = FakeClock()
        val d = detector(clock)
        // 0.3 位移 / 700ms = 0.00043/ms < 0.0005 阈值
        val results = feed(d, clock, listOf(0L to 0.2f, 350L to 0.35f, 700L to 0.5f))

        assertEquals(WaveDetector.State.NONE, results[2].state)
    }

    @Test
    fun `points outside window are pruned`() {
        val clock = FakeClock()
        val d = detector(clock)
        // 首个点 331-0=331ms > 300ms 窗口，被剔除，剩余不足 3 点
        val results = feed(d, clock, listOf(0L to 0.2f, 301L to 0.35f, 331L to 0.5f))

        assertEquals(WaveDetector.State.NONE, results[2].state)
    }

    @Test
    fun `second trigger within debounce returns DEBOUNCED`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(
            d, clock,
            listOf(
                // 第一次右挥，t=60 DETECTED
                0L to 0.2f, 30L to 0.35f, 60L to 0.5f,
                // 收手回滑左移（t=150），仍在 800ms 冷却内
                90L to 0.3f, 120L to 0.15f, 150L to 0.0f
            )
        )

        assertEquals(WaveDetector.State.DETECTED, results[2].state)
        assertEquals(WaveDetector.Direction.LEFT, results[5].direction)
        assertEquals(WaveDetector.State.DEBOUNCED, results[5].state)
    }

    @Test
    fun `trigger after debounce period fires again`() {
        val clock = FakeClock()
        val d = detector(clock)
        val results = feed(
            d, clock,
            listOf(
                0L to 0.2f, 30L to 0.35f, 60L to 0.5f,          // DETECTED RIGHT @60
                1000L to 0.5f, 1030L to 0.35f, 1060L to 0.2f   // 冷却已过，LEFT
            )
        )

        assertEquals(WaveDetector.State.DETECTED, results[2].state)
        assertEquals(WaveDetector.Direction.LEFT, results[5].direction)
        assertEquals(WaveDetector.State.DETECTED, results[5].state)
    }
}
