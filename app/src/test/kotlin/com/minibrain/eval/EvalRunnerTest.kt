package com.minibrain.eval

import android.content.Context
import android.content.res.AssetManager
import com.minibrain.ai.agent.AgentPipeline
import com.minibrain.ai.agent.AgentResult
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.ai.search.SearchPipelineResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayInputStream

class EvalRunnerTest {

    private val searchPipeline: SearchPipeline = mockk()
    private lateinit var evalRunner: EvalRunner

    @Before
    fun setUp() {
        // Plant a fake Timber tree to capture logs and prevent MockKException for static methods
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                println("[$tag] $message")
            }
        })
        evalRunner = EvalRunner(searchPipeline)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }


    private fun cit(path: String) = Citation(
        headingPath = path,
        snippet = "",
        relativePath = path,
        source = SourceType.UNKNOWN,
    )

    @Test
    fun `run evaluates cases and computes metrics correctly`() = runTest {
        val treeUri = "content://dummy"
        val case1 = EvalCase("c1", "q1", listOf("a.md"))
        val case2 = EvalCase("c2", "q2", listOf("b.md", "c.md"))
        val cases = listOf(case1, case2)

        // Mock SearchPipeline.search responses
        coEvery { searchPipeline.search("q1", treeUri) } returns SearchPipelineResult(
            citations = listOf(cit("a.md"), cit("x.md")),
            traceEvents = emptyList()
        )
        coEvery { searchPipeline.search("q2", treeUri) } returns SearchPipelineResult(
            citations = listOf(cit("x.md"), cit("b.md"), cit("c.md")),
            traceEvents = emptyList()
        )

        val result = evalRunner.run(treeUri, cases, k = 2)

        assertEquals(2, result.cases)
        assertEquals(2, result.k)

        // case1: q1 -> top 2 are a.md, x.md. expected a.md.
        // P@2 = 1/2 = 0.5, R@2 = 1/1 = 1.0, MRR = 1/1 = 1.0
        val res1 = result.perCase.find { it.id == "c1" }!!
        assertEquals(0.5, res1.precisionAtK, 1e-9)
        assertEquals(1.0, res1.recallAtK, 1e-9)
        assertEquals(1.0, res1.reciprocalRank, 1e-9)

        // case2: q2 -> top 2 are x.md, b.md. expected b.md, c.md.
        // P@2 = 1/2 = 0.5, R@2 = 1/2 = 0.5, MRR = 1/2 = 0.5
        val res2 = result.perCase.find { it.id == "c2" }!!
        assertEquals(0.5, res2.precisionAtK, 1e-9)
        assertEquals(0.5, res2.recallAtK, 1e-9)
        assertEquals(0.5, res2.reciprocalRank, 1e-9)

        // overall
        assertEquals(0.5, result.precisionAtK, 1e-9)
        assertEquals(0.75, result.recallAtK, 1e-9)
        assertEquals(0.75, result.mrr, 1e-9)
    }

    @Test
    fun `run handles exceptions and continues evaluation`() = runTest {
        val treeUri = "content://dummy"
        val case1 = EvalCase("c1", "q1", listOf("a.md"))
        val case2 = EvalCase("c2", "q2", listOf("b.md"))
        val cases = listOf(case1, case2)

        // c1 fails
        coEvery { searchPipeline.search("q1", treeUri) } throws RuntimeException("search failed")
        // c2 succeeds
        coEvery { searchPipeline.search("q2", treeUri) } returns SearchPipelineResult(
            citations = listOf(cit("b.md")),
            traceEvents = emptyList()
        )

        val result = evalRunner.run(treeUri, cases, k = 1)

        assertEquals(2, result.cases)

        // c1 failed -> empty result, so 0 metrics
        val res1 = result.perCase.find { it.id == "c1" }!!
        assertEquals(0.0, res1.precisionAtK, 1e-9)
        assertEquals(0.0, res1.recallAtK, 1e-9)
        assertEquals(0.0, res1.reciprocalRank, 1e-9)
        assertTrue(res1.hitPaths.isEmpty())
        assertEquals(listOf("a.md"), res1.missedPaths)

        // c2 succeeded -> perfect match
        val res2 = result.perCase.find { it.id == "c2" }!!
        assertEquals(1.0, res2.precisionAtK, 1e-9)
        assertEquals(1.0, res2.recallAtK, 1e-9)
        assertEquals(1.0, res2.reciprocalRank, 1e-9)

        // overall metrics: avg of 0 and 1 -> 0.5
        assertEquals(0.5, result.precisionAtK, 1e-9)
    }

    @Test
    fun `loadFromAssets parses JSON correctly`() {
        val json = """
            [
              {
                "id": "c1",
                "query": "find a",
                "expected": ["a.md"]
              },
              {
                "id": "c2",
                "query": "find bc",
                "expected": ["b.md", "c.md"]
              }
            ]
        """.trimIndent()

        val mockContext: Context = mockk()
        val mockAssetManager: AssetManager = mockk()

        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("eval/queries.sample.json") } returns ByteArrayInputStream(json.toByteArray())

        val cases = EvalRunner.loadFromAssets(mockContext, "eval/queries.sample.json")

        assertEquals(2, cases.size)
        assertEquals(EvalCase("c1", "find a", listOf("a.md")), cases[0])
        assertEquals(EvalCase("c2", "find bc", listOf("b.md", "c.md")), cases[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `loadFromAssets throws exception on missing id or query`() {
        val json = """
            [
              {
                "query": "find missing id",
                "expected": ["a.md"]
              }
            ]
        """.trimIndent()

        val mockContext: Context = mockk()
        val mockAssetManager: AssetManager = mockk()

        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("eval/queries.sample.json") } returns ByteArrayInputStream(json.toByteArray())

        EvalRunner.loadFromAssets(mockContext, "eval/queries.sample.json")
    }

    @Test
    fun `run invokes onProgress callback correctly`() = runTest {
        val treeUri = "content://dummy"
        val case1 = EvalCase("c1", "q1", listOf("a.md"))
        val case2 = EvalCase("c2", "q2", listOf("b.md"))
        val cases = listOf(case1, case2)

        coEvery { searchPipeline.search(any(), any()) } returns SearchPipelineResult(
            citations = emptyList(),
            traceEvents = emptyList()
        )

        val progressLog = mutableListOf<Pair<Int, Int>>()
        evalRunner.run(treeUri, cases, k = 1, onProgress = { current, total ->
            progressLog += current to total
        })

        assertEquals(
            listOf(0 to 2, 1 to 2, 2 to 2),
            progressLog
        )
    }

    @Test
    fun `候補と失敗理由を結果に残す`() = runTest {
        val treeUri = "content://dummy"
        coEvery { searchPipeline.search("q1", treeUri) } returns SearchPipelineResult(
            citations = listOf(cit("x.md")),
            traceEvents = emptyList(),
            candidates = listOf(cit("x.md"), cit("a.md")),
        )
        coEvery { searchPipeline.search("q2", treeUri) } throws RuntimeException("boom")

        val result = evalRunner.run(
            treeUri,
            listOf(EvalCase("c1", "q1", listOf("a.md")), EvalCase("c2", "q2", listOf("b.md"))),
            k = 1,
        )

        val c1 = result.perCase.single { it.id == "c1" }
        assertEquals(1.0, c1.candidateRecall, 1e-9)
        assertEquals(listOf("a.md"), c1.droppedByRerank)
        assertEquals("boom", result.perCase.single { it.id == "c2" }.error)
    }

    @Test
    fun `load はストリームから読める`() {
        val json = """[{"id": "c1", "query": "q", "expected": ["a.md"], "note": "無視される"}]"""
        val cases = EvalRunner.load(ByteArrayInputStream(json.toByteArray()))
        assertEquals(listOf(EvalCase("c1", "q", listOf("a.md"))), cases)
    }

    @Test
    fun `agentPipeline を渡すと回答を生成し、検索の指標は SearchPipeline の結果から取る`() = runTest {
        val treeUri = "content://dummy"
        val agentPipeline: AgentPipeline = mockk()
        val search = SearchPipelineResult(citations = listOf(cit("a.md")), traceEvents = emptyList())
        coEvery { agentPipeline.run("q1", treeUri) } returns AgentResult(
            citations = listOf(cit("other.md")),
            answerFlow = flowOf("2025年", "4月です"),
            search = search,
        )
        val case = EvalCase("c1", "q1", listOf("a.md"), facts = listOf("2025年4月|2025-04", "清澄白河"))

        val result = EvalRunner(searchPipeline, agentPipeline).run(treeUri, listOf(case))

        val c = result.perCase.single()
        assertEquals(1.0, c.recallAtK, 1e-9)
        assertEquals("2025年4月です", c.answer)
        assertEquals(listOf("清澄白河"), c.missedFacts)
        assertEquals(0.5, result.factRecall, 1e-9)
    }

    @Test
    fun `facts を読む`() {
        val json = """[{"id": "c1", "query": "q", "expected": ["a.md"], "facts": ["A|a", "B"]}]"""
        val cases = EvalRunner.load(ByteArrayInputStream(json.toByteArray()))
        assertEquals(listOf("A|a", "B"), cases.single().facts)
    }
}
