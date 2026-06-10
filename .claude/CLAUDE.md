# CLAUDE.md

## プロジェクト概要

Mini Brain は Android 12+ 向けのオンデバイス エージェント型 RAG アプリ。
パッケージ名: `com.minibrain`
言語: Kotlin、UI: Jetpack Compose + Material 3

## ビルドコマンド

```bash
./gradlew assembleDebug          # デバッグビルド
./gradlew installDebug           # 実機インストール
./gradlew test                   # ユニットテスト
./gradlew lint                   # Lint チェック
```

## 重要な技術的制約

### LiteRT-LM（LLM 推論エンジン）

- 依存: `com.google.ai.edge.litertlm:litertlm-android:latest.release`
- **MediaPipe LLM Inference (`tasks-genai`) は deprecated — 絶対に使わない**
- モデル形式: `.litertlm`（旧 `.task` 形式は使わない）
- API:
  ```kotlin
  val engine = Engine(EngineConfig(modelPath = "...", backend = Backend.GPU()))
  engine.initialize()  // バックグラウンドスレッド必須
  engine.createConversation().use { conv ->
      conv.sendMessageAsync(prompt).collect { message ->
          val text = message.contents.contents
              .filterIsInstance<Content.Text>()
              .joinToString("") { it.text }
          // text を emit
      }
  }
  ```
- GPU 初期化失敗時は CPU フォールバック必須（`LlmService.kt` 参照）
- `app/build.gradle.kts` の `kotlinOptions` に `-Xskip-metadata-version-check` が必要:
  litertlm-android は Kotlin 2.3.x でコンパイルされているため、プロジェクト側（2.2.x）とのバージョンミスマッチをスキップする
- AndroidManifest に native lib 宣言が必要:
  ```xml
  <uses-native-library android:name="libvndksupport.so" android:required="false"/>
  <uses-native-library android:name="libOpenCL.so" android:required="false"/>
  ```

### MediaPipe TextEmbedder（Embedder）

- 依存: `com.google.mediapipe:tasks-text`（こちらは現役）
- モデル: Universal Sentence Encoder Multilingual（日本語対応）
- API:
  ```kotlin
  val result = textEmbedder.embed(text)
  val floatList = result.embeddingResult().embeddings().first().floatEmbedding()
  ```

### Room DB

- `FloatArray`（ベクトル）は `ByteArray` として保存
- 変換: `EmbedderService.floatArrayToBytes()` / `bytesToFloatArray()`
- コサイン類似度は全件メモリロードで計算（個人用途 = 数千チャンク以下を想定）
- DB バージョン: 5
  - v2→v3: `documents` テーブルに `headings`, `first_para`, `tags` カラムを追加
  - v3→v4: `documents` テーブルに `documentDate` カラムを追加（Recentness Ranking 用）
  - v4→v5: `folder_embeddings` テーブルを追加（Folder Embedding 用）

### Storage Access Framework (SAF)

- フォルダ選択: `ActivityResultContracts.OpenDocumentTree()`
- 永続アクセス権: `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
- ファイル読み込み: `DocumentFile.fromTreeUri()` → `ContentResolver.openInputStream()`

## アーキテクチャ

```
UI (Compose) → ViewModel → AgentPipeline → (LlmService / EmbedderService / Room)
                         → RagPipeline   → (general フォールバック)
                         → Repository   → Room
```

依存性注入は `MiniBrainApp` での手動シングルトン。Hilt は使わない。

### 検索フロー（AgentPipeline）

```
質問
↓ QueryClassifier.classify()
  GENERAL_KNOWLEDGE → RAG スキップ → LLM 直接回答
  TEMPORAL_SUMMARIZATION / MEMORY_SEARCH → 以下へ
↓ buildPlannerHint（DB 先行解析）
  ├─ 期間クエリ: resolveDateRange → DateRange を hint に注入 / timeline_search 推奨
  ├─ 日付クエリ: YYYYMMDD 8桁で DB 検索 → docId を hint に直接注入
  │              未一致なら YYYYMMDD* / YYYY/MM/DD* / YYYY-MM-DD* の glob パターンを hint に列挙
  └─ ファイル名一致: [d=ID] fileName を hint に追加
↓ ReAct ループ（最大 6 回）
  Planner LLM が以下から1ツールを DSL(key:value)形式で選択:
  ├─ glob(pattern)                   : ファイルパターン列挙
  ├─ list_dir(folder)                : フォルダ直下一覧
  ├─ read_file(docId|path)           : ファイル全文取得（3000字超は LLM 要約）
  ├─ grep(query, scope?)             : FTS4 キーワード検索
  ├─ vector_search(query, k)         : 意味類似検索
  ├─ rrf_search(query, k)            : BM25+ベクトル RRF 融合
  └─ timeline_search(start, end, k)  : documentDate 範囲フィルタ
  ↓ ToolExecutor 実行 → Observation 追加
  ↓ ACTION: finalize or 6 回到達で終了
  ↓ DSL パース失敗 2 回連続 → RRF 即時フォールバック
↓ CitationIntegrator（重複除去・優先度整列・トークン budget 1200）
  優先度: READ_FILE > GREP > VECTOR > RRF > GLOB > FOLDER
  citations 空 → RRF 強制フォールバック（セーフティネット）
↓ buildAnswerPrompt → LLM 回答生成（ストリーミング）
```

## モデルファイルのパス

```
context.filesDir/models/gemma-4-E2B-it.litertlm      # LLM（約 2.5 GB）
context.filesDir/models/universal_sentence_encoder_multilingual.tflite  # Embedder（約 280 MB）
```

モデルは `ModelDownloader` が Range リクエスト対応でダウンロードし、レジュームをサポートする。

## よく変更するファイル

| 変更内容                     | 対象ファイル                                              |
| ---------------------------- | --------------------------------------------------------- |
| ReAct ループ制御・hint 生成  | `ai/agent/AgentPipeline.kt`                               |
| Planner プロンプト（DSL形式）| `ai/agent/PlannerPrompt.kt`                               |
| クエリ種別分類               | `ai/agent/QueryClassifier.kt`                             |
| ツール実装（glob/grep 等）   | `ai/agent/tools/ToolExecutor.kt`                          |
| glob パターン変換            | `ai/agent/tools/GlobMatcher.kt`                           |
| 引用統合・優先度・budget     | `ai/agent/CitationIntegrator.kt`                          |
| 日付解決・期間解決           | `ai/agent/DateResolver.kt`                                |
| エージェントトレースイベント | `ai/agent/AgentTraceEvent.kt`                             |
| 回答プロンプト               | `ai/agent/AgentPipeline.kt`（buildAnswerPrompt）          |
| LLM 生成パラメータ・要約     | `ai/llm/LlmService.kt`                                    |
| チャンク分割ロジック         | `data/md/MarkdownChunker.kt`                              |
| ファイルメタ抽出             | `data/md/MarkdownMetaExtractor.kt`                        |
| インデックス処理             | `data/repo/DocumentRepository.kt`                         |
| チャット履歴管理             | `data/repo/ChatRepository.kt`                             |
| チャット UI                  | `ui/screens/ChatScreen.kt` + `ui/vm/ChatViewModel.kt`     |

## 注意事項

- `ChunkDao.getAll()` は全チャンクをメモリにロードする。数万チャンクになる場合は ANN（HNSW 等）への置換を検討
- `EmbedderService` と `LlmService` の初期化はバックグラウンドスレッド（`Dispatchers.Default`）で行う
- `EmbedderService.embed()` は内部で `Mutex` を使ってシリアライズされているため、並列呼び出しは安全だがスループットは出ない
- クラウド API の追加は禁止（プライバシー要件）
- `AgentPipeline.run()` は先に `QueryClassifier.classify()` を呼び、`GENERAL_KNOWLEDGE` の場合は RAG をスキップして LLM 直接回答する
- `AgentPipeline.run()` の ReAct ループ（最大 6 回）では各反復で Planner LLM がツールを選択し、最終的に `CitationIntegrator` で引用を統合して回答を生成する。citations が空の場合は RRF を強制実行するセーフティネットがある
- `PlannerPrompt` は `org.json` を使わず DSL key:value 形式で出力・パースする（JVM ユニットテスト互換性のため JSON は使わない）
- `buildPlannerHint` は期間クエリ（`resolveDateRange`）→ 日付クエリ（YYYYMMDD 8桁 DB 検索）→ ファイル名一致の順で解析し hint を構築する
- DB マイグレーション: 既存 `documents` レコードの `headings` / `first_para` / `tags` / `documentDate` は次回差分インデックス時に自動補完される。強制補完は Settings → 再インデックスで可能
