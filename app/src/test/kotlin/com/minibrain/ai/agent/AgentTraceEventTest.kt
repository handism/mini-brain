package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTraceEventTest {

    @Test
    fun testPlannerDecisionEvent() {
        val event = PlannerDecisionEvent(step = 1, decision = "think")
        assertEquals(1, event.step)
        assertEquals("think", event.decision)
    }

    @Test
    fun testToolCallEvent() {
        val event = ToolCallEvent(step = 2, toolName = "search", arguments = "query")
        assertEquals(2, event.step)
        assertEquals("search", event.toolName)
        assertEquals("query", event.arguments)
    }

    @Test
    fun testObservationEvent() {
        val event = ObservationEvent(step = 3, summary = "found it")
        assertEquals(3, event.step)
        assertEquals("found it", event.summary)
    }

    @Test
    fun testFinalAnswerEvent() {
        val event = FinalAnswerEvent(answerLength = 42)
        assertEquals(42, event.answerLength)
    }

    @Test
    fun testQueryExpansionEvent() {
        val queries = listOf("q1", "q2")
        val event = QueryExpansionEvent(queries = queries)
        assertEquals(queries, event.queries)
    }

    @Test
    fun testHyDeGeneratedEvent() {
        val event = HyDeGeneratedEvent(hypothetical = "fake answer")
        assertEquals("fake answer", event.hypothetical)
    }

    @Test
    fun testMetadataSearchHitEvent() {
        val event = MetadataSearchHitEvent(hitCount = 5)
        assertEquals(5, event.hitCount)
    }

    @Test
    fun testBM25SearchHitEvent() {
        val event = BM25SearchHitEvent(query = "q", hitCount = 10)
        assertEquals("q", event.query)
        assertEquals(10, event.hitCount)
    }

    @Test
    fun testGrepSearchHitEvent() {
        val event = GrepSearchHitEvent(query = "regex", hitCount = 3)
        assertEquals("regex", event.query)
        assertEquals(3, event.hitCount)
    }

    @Test
    fun testVectorSearchHitEvent() {
        val event = VectorSearchHitEvent(query = "vec", hitCount = 7)
        assertEquals("vec", event.query)
        assertEquals(7, event.hitCount)
    }

    @Test
    fun testCandidateMergeEvent() {
        val event = CandidateMergeEvent(totalCount = 20)
        assertEquals(20, event.totalCount)
    }

    @Test
    fun testRerankEvent() {
        val event = RerankEvent(before = 20, after = 5)
        assertEquals(20, event.before)
        assertEquals(5, event.after)
    }

    @Test
    fun testCoverageCheckEvent() {
        val missing = listOf("info1")
        val event = CoverageCheckEvent(canAnswer = false, missingInformation = missing)
        assertEquals(false, event.canAnswer)
        assertEquals(missing, event.missingInformation)
    }

    @Test
    fun testExplorerStrategyEvent() {
        val event = ExplorerStrategyEvent(strategy = "strat", reason = "because")
        assertEquals("strat", event.strategy)
        assertEquals("because", event.reason)
    }
}
