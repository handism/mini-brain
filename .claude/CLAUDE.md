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
UI (Compose) → ViewModel → AgentPipeline → SearchPipeline → (QueryExpander / LlmReranker)
                                         → RagPipeline    → (BM25 / ベクトル / RRF)
                                         → Repository     → Room
```

依存性注入は `MiniBrainApp` での手動シングルトン。Hilt は使わない。

### 検索フロー（AgentPipeline — Search First）

```
質問
↓ QueryClassifier.classify()
  GENERAL_KNOWLEDGE → RAG スキップ → LLM 直接回答
  TEMPORAL_SUMMARIZATION / MEMORY_SEARCH → 以下へ

↓ SearchPipeline.search()
  1. QueryExpander（LLM）: クエリを 3〜8 件に展開
  2. Parallel Retrieval:
     ├─ 展開クエリ × BM25（FTS4）            : 並行実行
     ├─ 展開クエリ × Metadata Search         : fileName/path/tags/documentDate を検索
     │                                       + fileName(拡張子除く 3 字以上) が query の substring に含まれる逆引き
     │                                       snippet 先頭に `[日付: YYYY-MM-DD]` を埋め込む
     └─ 元クエリ × Vector（Embedding）       : ベクトル類似で意味的近傍を拾う
  3. Candidate Merge: 重複排除（docId+headingPath キー）→ 上位 50 件
  4. LlmReranker（LLM）: 候補をスコアリングし上位 10 件を選択
     ※ 日付クエリ（いつ・何月 等）は日付情報を含む候補を優先するよう追記

↓ CoverageCheck（LLM）: query + top5 candidates を見て回答可能かを判定
  ※ 日付クエリ かつ snippet 先頭が `[日付:` で始まる候補があれば LLM を呼ばずに即 canAnswer=true で短絡
  canAnswer=true  → 回答生成へ
  canAnswer=false → ExplorerStrategy 決定 → ReAct ループへ
    EXPAND_TIME   : missing に date/visit/time → read_file で全文を読んで日付メタを確認するヒント（timeline_search は最終手段）
    EXPAND_TOPIC  : それ以外 → read_file/grep を hint に追加

↓ citations が空 OR CoverageCheck 失敗の場合 → ReAct ループ（フォールバック）
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
  優先度: READ_FILE > GREP > METADATA > VECTOR > RRF > GLOB > FOLDER
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

| 変更内容                             | 対象ファイル                                          |
| ------------------------------------ | ----------------------------------------------------- |
| Search First フロー全体              | `ai/search/SearchPipeline.kt`                         |
| クエリ展開                           | `ai/search/QueryExpander.kt`                          |
| LLM 再採点                           | `ai/search/LlmReranker.kt`                            |
| Coverage Check・Explorer Strategy    | `ai/agent/CoverageChecker.kt`                         |
| パイプライン統合・フォールバック制御 | `ai/agent/AgentPipeline.kt`                           |
| ReAct ループ（フォールバック）       | `ai/agent/AgentPipeline.kt`（runReActLoop）           |
| Planner プロンプト（DSL形式）        | `ai/agent/PlannerPrompt.kt`                           |
| クエリ種別分類                       | `ai/agent/QueryClassifier.kt`                         |
| ツール実装（glob/grep 等）           | `ai/agent/tools/ToolExecutor.kt`                      |
| glob パターン変換                    | `ai/agent/tools/GlobMatcher.kt`                       |
| 引用統合・優先度・budget             | `ai/agent/CitationIntegrator.kt`                      |
| 日付解決・期間解決                   | `ai/agent/DateResolver.kt`                            |
| 検索トレースイベント                 | `ai/agent/AgentTraceEvent.kt`                         |
| 回答プロンプト                       | `ai/agent/AgentPipeline.kt`（buildAnswerPrompt）      |
| LLM 生成パラメータ・要約             | `ai/llm/LlmService.kt`                                |
| チャンク分割ロジック                 | `data/md/MarkdownChunker.kt`                          |
| ファイルメタ抽出                     | `data/md/MarkdownMetaExtractor.kt`                    |
| インデックス処理                     | `data/repo/DocumentRepository.kt`                     |
| チャット履歴管理                     | `data/repo/ChatRepository.kt`                         |
| チャット UI                          | `ui/screens/ChatScreen.kt` + `ui/vm/ChatViewModel.kt` |

## ドキュメント更新ルール

コードに変更を加える際は、以下の 3 ファイルを **常に最新の状態に保つ**。コード変更とドキュメント更新は同じ作業の一部であり、別タスクではない。

| ファイル | 更新が必要な変更 |
| --- | --- |
| `ADR.md` | アーキテクチャ判断・検索フロー再編・新ツール追加・既存判断の置き換えなど、設計意図を残すべき変更。新規 ADR を末尾に追記し、置き換えられた既存 ADR は「廃止」または「部分置き換え」とステータスを更新する |
| `.claude/CLAUDE.md` | 検索フロー図・ビルドコマンド・依存・DB スキーマ・「よく変更するファイル」表・注意事項など、Claude や開発者が実装中に参照するファクト。コードと食い違ったら即修正 |
| `README.md` | ユーザー向けの特徴・アーキテクチャ概要・画面構成・モデル情報など、外部から見える振る舞い |

運用ルール:

- 同一 PR / 同一コミットに含める。「あとで直す」は禁止
- アーキテクチャに影響する変更は、必ず ADR を追加するか既存 ADR を更新する（置き換える場合は旧 ADR を「廃止」「部分置き換え」にステータス変更し新 ADR へリンク）
- CLAUDE.md と README.md の検索フロー図・アーキテクチャ図は同じ意味を保つ（表現の粒度は差があってよい）
- コミット前に `git diff` で 3 ファイルがコード状態と整合しているかを確認する

## 注意事項

- `ChunkDao.getAll()` は全チャンクをメモリにロードする。数万チャンクになる場合は ANN（HNSW 等）への置換を検討
- `EmbedderService` と `LlmService` の初期化はバックグラウンドスレッド（`Dispatchers.Default`）で行う
- `EmbedderService.embed()` は内部で `Mutex` を使ってシリアライズされているため、並列呼び出しは安全だがスループットは出ない
- **LiteRT-LM は単一スレッド**: `QueryExpander` と `LlmReranker` は逐次実行が必須。並行 LLM 呼び出しは不可
- クラウド API の追加は禁止（プライバシー要件）
- `AgentPipeline.run()` は `QueryClassifier.classify()` → `SearchPipeline.search()` の順に実行する。`GENERAL_KNOWLEDGE` の場合は RAG をスキップして LLM 直接回答する
- `SearchPipeline` が空 **または** `CoverageChecker.canAnswer == false` の場合に `runReActLoop()` を呼び出す
- `PlannerPrompt` は `org.json` を使わず DSL key:value 形式で出力・パースする（JVM ユニットテスト互換性のため JSON は使わない）
- `buildPlannerHint` は期間クエリ（`resolveDateRange`）→ 日付クエリ（YYYYMMDD 8桁 DB 検索）→ ファイル名一致の順で解析し hint を構築する（ReAct ループ用）
- `QueryClassifier` の `GENERAL_KNOWLEDGE_PATTERNS` に `について教えて` は含まない（個人ノートでも多用されるため MEMORY_SEARCH に倒す）
- `QueryExpander` のプロンプトには「固有名詞を助詞・疑問詞を取り除いた単独名詞として 1 件以上含める」必須ルールを書いておく（日本語助詞でトークン化されない問題への対策）
- `SearchPipeline.metadataSearch` はトークン一致と「fileName(拡張子除く) を query が substring として含む」逆引きの OR で候補抽出する（日本語形態素解析を使わずに固有名詞ファイルを拾う）
- DB マイグレーション: 既存 `documents` レコードの `headings` / `first_para` / `tags` / `documentDate` は次回差分インデックス時に自動補完される。強制補完は Settings → 再インデックスで可能
