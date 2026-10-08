package com.minibrain.eval

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.minibrain.MiniBrainApp
import com.minibrain.data.repo.IndexingState
import com.minibrain.debug.GrantFolderActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * エミュレータで検索評価を回す（ADR-045）。手で動かさず scripts/eval-emulator.sh から呼ぶ。
 *
 * 前提（スクリプトが用意する）:
 * - /sdcard/<folder> に評価用ノート
 * - filesDir/models にモデル 3 点、filesDir/eval/queries.json に評価セット
 *
 * 引数（am instrument -e）:
 * - folder: ノートのフォルダ（/sdcard からの相対。既定 Documents/minibrain-eval）
 * - gpu   : true なら LLM を GPU で試す（既定は CPU。エミュレータの GPU では意味がなく、落ちることもある）
 * - cases : カンマ区切りの id。指定したケースだけ流す
 * - dump  : true なら各ケースの候補（RRF 後・Reranker 前）と最終結果を filesDir/eval/dump.md に書く
 * - answer: true なら AgentPipeline で回答まで生成し、facts と照合する。回答の全文は filesDir/eval/answers.md（ADR-048）
 * - llm   : filesDir/models の LLM のファイル名（既定はアプリと同じ E2B）。E4B などを比べるため（ADR-056）
 * - spec  : true / false で LiteRT-LM の投機的デコードを明示的に有効 / 無効にする（既定はライブラリ任せ）
 *
 * 結果は filesDir/eval/report.md に書き、進み具合は logcat の EmulatorEval タグに出す。
 */
@RunWith(AndroidJUnit4::class)
class EmulatorEvalTest {

    private val args = InstrumentationRegistry.getArguments()
    private val app = ApplicationProvider.getApplicationContext<MiniBrainApp>()

    @OptIn(ExperimentalApi::class)
    @Test
    fun runEval() = runBlocking {
        val folder = args.getString("folder") ?: "Documents/minibrain-eval"
        val treeUri = persistedTree(folder) ?: grantFolder(folder)
        log("tree=$treeUri")

        val evalDir = File(app.filesDir, "eval")
        val queries = File(evalDir, "queries.json")
        assertTrue("評価セットがありません: $queries", queries.exists())
        val only = args.getString("cases")?.split(',')?.map { it.trim() }?.toSet()
        val cases = queries.inputStream().use(EvalRunner::load).filter { only == null || it.id in only }

        val container = app.container
        val models = container.modelDownloader
        container.embedderService.initialize(models.embedderModelFile, models.tokenizerModelFile)
        val llmFile = args.getString("llm")?.let { File(models.llmFile().parentFile, it) } ?: models.llmFile()
        args.getString("spec")?.let { ExperimentalFlags.enableSpeculativeDecoding = it == "true" }
        val specSupported = runCatching { Capabilities(llmFile.absolutePath).use { it.hasSpeculativeDecodingSupport() } }.getOrNull()
        container.llmService.initialize(llmFile, forceCpu = args.getString("gpu") != "true")
        log("models ready llm=${llmFile.name} specSupported=$specSupported spec=${ExperimentalFlags.enableSpeculativeDecoding}")

        val repo = container.documentRepository
        repo.indexFolder(treeUri)
        when (val state = repo.indexingState.value) {
            is IndexingState.Done -> log("indexed files=${state.fileCount} chunks=${state.chunkCount}")
            else -> error("インデックスに失敗しました: $state")
        }

        val tree = treeUri.toString()
        val indexedPaths = container.database.documentDao().getMinimalByTree(tree).map { it.relativePath }
        val unknownPaths = EvalMetrics.findUnknownPaths(cases, indexedPaths)
        if (args.getString("dump") == "true") {
            File(evalDir, "dump.md").writeText(dump(cases, tree))
            log("dumped ${cases.size} cases")
        }
        val withAnswer = args.getString("answer") == "true"
        val agent = if (withAnswer) container.agentPipeline else null
        val result = EvalRunner(container.searchPipeline, agent).run(tree, cases) { current, total ->
            cases.getOrNull(current)?.let { log("[${current + 1}/$total] ${it.id} ${it.query}") }
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date())
        val kind = if (withAnswer) "回答評価" else "検索評価"
        val llmNote = if (llmFile != models.llmFile()) "・${llmFile.name}" else ""
        val report = EvalReport.toMarkdown(result, "$kind $stamp（${queries.name}・エミュレータ$llmNote）", unknownPaths)
        File(evalDir, "report.md").writeText(report)
        if (withAnswer) {
            File(evalDir, "answers.md").writeText(EvalReport.answersToMarkdown(result, "回答 $stamp（${queries.name}）"))
            log("facts=${EvalReport.fmt(result.factRecall)} accuracy=${EvalReport.fmt(result.answerAccuracy)}")
        }
        log("done recall=${EvalReport.fmt(result.recallAtK)} mrr=${EvalReport.fmt(result.mrr)}")
        container.llmService.close()
    }

    // Reranker で落ちた理由を調べるため、候補の順位・出どころ・チャンクの重なりを書き出す
    private suspend fun dump(cases: List<EvalCase>, tree: String): String = buildString {
        for (case in cases) {
            val expected = case.expectedRelativePaths.map { it.lowercase() }.toSet()
            val r = app.container.searchPipeline.search(case.query, tree)
            fun mark(path: String?) = if (path?.lowercase() in expected) "★" else " "
            appendLine("## ${case.id} ${case.query}")
            appendLine("候補 ${r.candidates.size} 件（Reranker に渡るのは先頭 20 件）")
            r.candidates.forEachIndexed { i, c ->
                appendLine("  ${mark(c.relativePath)} [$i] ${c.source} ${c.relativePath} > ${c.headingPath.takeLast(40)}")
            }
            appendLine("最終 ${r.citations.size} 件（ファイル ${r.citations.mapNotNull { it.relativePath }.distinct().size} 種類）")
            r.citations.forEachIndexed { i, c ->
                appendLine("  ${mark(c.relativePath)} ($i) ${c.source} ${c.relativePath} > ${c.headingPath.takeLast(40)}")
            }
            appendLine()
        }
    }

    private fun persistedTree(folder: String): Uri? = app.contentResolver.persistedUriPermissions
        .map { it.uri }
        .firstOrNull { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() == "primary:$folder" }

    // フォルダ選択画面を開き、UiAutomator で「このフォルダを使用」→「許可」を押す。権限はアプリを入れ直すまで残る
    private fun grantFolder(folder: String): Uri {
        val initial = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$folder")
        app.startActivity(
            Intent(app, GrantFolderActivity::class.java).setData(initial).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        clickWhenShown(device, "(?i)use this folder|このフォルダを使用")
        clickWhenShown(device, "(?i)allow|許可")
        repeat(50) {
            persistedTree(folder)?.let { return it }
            Thread.sleep(200)
        }
        error("フォルダの権限を取れませんでした: $folder")
    }

    private fun clickWhenShown(device: UiDevice, textPattern: String) {
        val button = device.wait(Until.findObject(By.text(Pattern.compile(textPattern))), UI_TIMEOUT_MS)
        checkNotNull(button) { "ボタンが見つかりません: $textPattern" }.click()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "EmulatorEval"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val UI_TIMEOUT_MS = 20_000L
    }
}
