package com.google.mediapipe.examples.handlandmarker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceSettingsTest {

    @Test
    fun `performance modes select expected analysis stride`() {
        val originalMode = GestureSettings.performanceMode
        try {
            GestureSettings.updateThermalStatus(0)

            GestureSettings.updatePerformanceMode(GestureSettings.PERFORMANCE_AUTO)
            assertEquals(2, GestureSettings.analysisStride(true))
            assertEquals(6, GestureSettings.analysisStride(false))

            GestureSettings.updatePerformanceMode(GestureSettings.PERFORMANCE_POWER_SAVING)
            assertEquals(3, GestureSettings.analysisStride(true))
            assertEquals(8, GestureSettings.analysisStride(false))

            GestureSettings.updatePerformanceMode(GestureSettings.PERFORMANCE_HIGH_RESPONSE)
            assertEquals(1, GestureSettings.analysisStride(true))
            assertEquals(4, GestureSettings.analysisStride(false))
        } finally {
            GestureSettings.updateThermalStatus(0)
            GestureSettings.updatePerformanceMode(originalMode)
        }
    }

    @Test
    fun `severe thermal status overrides selected mode`() {
        val originalMode = GestureSettings.performanceMode
        try {
            GestureSettings.updatePerformanceMode(GestureSettings.PERFORMANCE_HIGH_RESPONSE)
            GestureSettings.updateThermalStatus(3)

            assertEquals(3, GestureSettings.analysisStride(true))
            assertEquals(8, GestureSettings.analysisStride(false))
            assertTrue(GestureSettings.useLowAnalysisResolution())
        } finally {
            GestureSettings.updateThermalStatus(0)
            GestureSettings.updatePerformanceMode(originalMode)
        }
    }
}
