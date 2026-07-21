package com.minibrain.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp

class RagPipelineTest {

    @Test
    fun testFreshnessBoost() {
        val today = LocalDate.of(2024, 1, 1)

        // Null document date should return 0f
        assertEquals(0f, RagPipeline.freshnessBoost(null, today), 0.0f)

        // Same day document should return max boost
        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX, RagPipeline.freshnessBoost(today, today), 0.0001f)

        // Future document date should be coerced to 0 days, returning max boost
        val future = LocalDate.of(2024, 1, 2)
        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX, RagPipeline.freshnessBoost(future, today), 0.0001f)

        // Past dates should exponentially decay
        val past30 = today.minusDays(30)
        val expected30 = (RagPipeline.FRESHNESS_BOOST_MAX * exp(-30f / RagPipeline.FRESHNESS_DECAY_DAYS)).toFloat()
        assertEquals(expected30, RagPipeline.freshnessBoost(past30, today), 0.00001f)

        val past90 = today.minusDays(90)
        val expected90 = (RagPipeline.FRESHNESS_BOOST_MAX * exp(-90f / RagPipeline.FRESHNESS_DECAY_DAYS)).toFloat()
        assertEquals(expected90, RagPipeline.freshnessBoost(past90, today), 0.00001f)

        val past365 = today.minusDays(365)
        val expected365 = (RagPipeline.FRESHNESS_BOOST_MAX * exp(-365f / RagPipeline.FRESHNESS_DECAY_DAYS)).toFloat()
        assertEquals(expected365, RagPipeline.freshnessBoost(past365, today), 0.00001f)
    }
}
