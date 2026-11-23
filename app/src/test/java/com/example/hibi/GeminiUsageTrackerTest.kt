package com.example.hibi

import com.example.hibi.data.GeminiUsageTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiUsageTrackerTest {

    @Test
    fun calculateCostUsd_flashDefaults() {
        // 1M input ($0.30) + 1M output+thinking ($2.50) = $2.80
        val cost = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-2.5-flash",
            promptTokens = 1_000_000L,
            candidatesTokens = 600_000L,
            thinkingTokens = 400_000L,
        )
        assertEquals(2.80, cost, 0.0001)
    }

    @Test
    fun calculateCostUsd_withCachedInput() {
        // 500k uncached ($0.15) + 500k cached ($0.015) + 0 output = $0.165
        val cost = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-2.5-flash",
            promptTokens = 1_000_000L,
            candidatesTokens = 0L,
            thinkingTokens = 0L,
            cachedTokens = 500_000L,
        )
        assertEquals(0.165, cost, 0.0001)
    }

    @Test
    fun calculateCostUsd_hydrogenDefaults() {
        // 1M input ($1.50) + 1M output+thinking ($7.50) = $9.00
        val cost = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-4-hydrogen",
            promptTokens = 1_000_000L,
            candidatesTokens = 500_000L,
            thinkingTokens = 500_000L,
        )
        assertEquals(9.00, cost, 0.0001)
    }

    @Test
    fun calculateCostUsd_neonAndFlash3x() {
        val neon = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-4-neon",
            promptTokens = 1_000_000L,
            candidatesTokens = 500_000L,
            thinkingTokens = 500_000L,
        )
        assertEquals(18.00, neon, 0.0001)

        val flash38 = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-3.8-flash",
            promptTokens = 1_000_000L,
            candidatesTokens = 500_000L,
            thinkingTokens = 500_000L,
        )
        assertEquals(4.50, flash38, 0.0001)

        val flash35Lite = GeminiUsageTracker.calculateCostUsd(
            model = "gemini-3.5-flash-lite",
            promptTokens = 1_000_000L,
            candidatesTokens = 600_000L,
            thinkingTokens = 400_000L,
        )
        assertEquals(2.80, flash35Lite, 0.0001)
    }

    @Test
    fun formatCost_formatsZeroAndSmallValues() {
        assertEquals("¥0", GeminiUsageTracker.formatCostJpy(0.0))
        assertEquals("$0.00", GeminiUsageTracker.formatCostUsd(0.0))
        assertEquals("¥159", GeminiUsageTracker.formatCostJpy(1.0))
        assertEquals("¥160", GeminiUsageTracker.formatCostJpy(1.0, 160.0))
    }
}
