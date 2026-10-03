package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationWindowTest {

    private fun call(i: Int) = ToolCall(i, AgentTool.Glob("*.md"))

    @Test
    fun `latest two observations stay full and older ones become compact`() {
        val observations = mutableListOf<Observation>()
        (1..4).forEach { addObservation(observations, call(it), "obs$it") }

        assertEquals(listOf(false, false, true, true), observations.map { it.full })
    }

    @Test
    fun `newest observation is always full`() {
        val observations = mutableListOf<Observation>()
        (1..3).forEach { i ->
            addObservation(observations, call(i), "obs$i")
            assertEquals(true, observations.last().full)
        }
    }
}
