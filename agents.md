# agents.md (AI Agent Rules)

このファイルは、AI コーディングエージェント（Google DeepMind の **Antigravity**、Anthropic の **Claude Code** など）向けに、プロジェクトの技術仕様、ビルド・実行コマンド、設計の制約、および注意事項をまとめたものです。
エージェントはコードの変更、テストの実行、リファクタリングなどを行う際に、常にこのファイルを最優先で参照してください。

> ルート直下の `CLAUDE.md` はこのファイルへの symlink です。実体は 1 つなので、どちらを編集しても同じ内容が更新されます。

---

## 1. プロジェクト概要
- **プロジェクト名**: Mini Brain
- **目的**: Android 12+ 向けのオンデバイス エージェント型 RAG アプリ。
- **パッケージ名**: `com.minibrain`
- **主要言語**: Kotlin
- **UI フレームワーク**: Jetpack Compose + Material 3
- **依存性注入 (DI)**: [AppContainer.kt](app/src/main/kotlin/com/minibrain/di/AppContainer.kt) での手動DIコンテナ (`MiniBrainApp.container`) 管理（Hilt や Koin などの DI フレームワークは不使用）。

---

## 2. ビルド & 開発コマンド
Antigravity がコードの検証や実行を行うための主要な Gradle コマンドです。

| コマンド | 説明 |
| --- | --- |
| `./gradlew assembleDebug` | デバッグビルドの作成 |
| `./gradlew installDebug` | 接続中の実機/エミュレータへのインストール |
| `./gradlew test` | JVM ユニットテストの実行 |
| `./gradlew lint` | Android Lint による静的解析チェック |

---

## 3. 重要な技術的制約

### 3.1 LiteRT-LM (LLM 推論エンジン)
- **依存関係**: `com.google.ai.edge.litertlm:litertlm-android:latest.release`
- **注意**: **MediaPipe LLM Inference (`tasks-genai`) は非推奨 (deprecated) です。絶対に使用しないでください。**
- **モデル形式**: `.litertlm`（旧 `.task` 形式は使用不可）。
- **初期化と実行**:
  - `Engine` の初期化はバックグラウンドスレッド（`Dispatchers.Default` など）で行う必要があります。
  - GPU 初期化が失敗した場合は、CPU フォールバックを行ってください。詳細は [LlmService.kt](app/src/main/kotlin/com/minibrain/ai/llm/LlmService.kt) を参照。
  - API 使用例：
    ```kotlin
    val engine = Engine(EngineConfig(modelPath = "...", backend = Backend.GPU()))
    engine.initialize() // バックグラウンドスレッド必須
    engine.createConversation().use { conv ->
        conv.sendMessageAsync(prompt).collect { message ->
            val text = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            // text を emit して UI またはフローへ流す
        }
    }
    ```
- **ビルド設定**: `app/build.gradle.kts` の `kotlinOptions` に `-Xskip-metadata-version-check` が指定されている必要があります（Kotlin のコンパイルバージョンの差異をスキップするため）。
- **権限・マニフェスト**: native-library 宣言が `AndroidManifest.xml` に必要です。
  ```xml
  <uses-native-library android:name="libvndksupport.so" android:required="false"/>
  <uses-native-library android:name="libOpenCL.so" android:required="false"/>
  ```
- **実行スレッド制限**: LiteRT-LM は単一スレッド設計です。`QueryExpander` と `LlmReranker` などでの並行 LLM 呼び出しは不可であり、逐次実行を厳守してください。

### 3.2 ONNX Runtime + multilingual-e5-small (Embedder)
- **依存関係**:
  - `com.microsoft.onnxruntime:onnxruntime-android` (推論ランタイム)
  - `ai.djl.huggingface:tokenizers` + `ai.djl.android:tokenizer-native` (XLM-RoBERTa Tokenizer、arm64-v8a 用 `libdjl_tokenizer.so` 同梱)
- **モデル**: `Xenova/multilingual-e5-small` の INT8 量子化版（`model_quantized.onnx` 約118MB + `tokenizer.json` 約17MB）。
- **仕様**:
  - 埋め込み次元: **384**
  - 最大トークン長: **512**
  - **クエリ・文章プレフィックス**: クエリには `query: `、文書（チャンク）には `passage: ` のプレフィックス付与が必須。`EmbedType` enum を使用します。
  - 内部処理: トークナイズ後、ONNX による推論値 (`last_hidden_state`) に対して `attention_mask` を用いた重み平均 pooling と L2 正規化を行います。
- **実行制御**: `EmbedderService` 内の推論は `Mutex` でシリアライズされています。初期化はバックグラウンドスレッドで行い、並列推論は避けてください。

### 3.3 Room データベース
- **ベクトル保存**: `FloatArray`（384次元ベクトル）は `ByteArray` に変換して Room に保存します。変換用メソッドとして `EmbedderService.floatArrayToBytes()` / `bytesToFloatArray()` を利用してください。
- **類似度計算**: スケールが小さいため（個人用途、数千チャンク以下）、コサイン類似度は全件をメモリにロードして CPU 上で計算します。
- **データベースバージョン**: **6**
  - v6 移行時に e5 384次元の導入に伴い、既存のベクトルインデックスがクリアされています。再インデックスが必要です。

### 3.4 Storage Access Framework (SAF)
- フォルダの選択には `ActivityResultContracts.OpenDocumentTree()` を使用し、`takePersistableUriPermission` で永続アクセス権を取得してファイルを読み込みます。

---

## 4. アーキテクチャと検索フロー (AgentPipeline)

### 4.1 全体構造
```
UI (Compose) ──> ViewModel ──> AgentPipeline ──> SearchPipeline ──> (QueryExpander / LlmReranker)
                                             ──> RagPipeline    ──> (BM25 / ベクトル / RRF)
                                             ──> Repository     ──> Room
```

### 4.2 検索・回答生成フロー（Search First）
ユーザーからの質問は、まず [AgentPipeline.kt](app/src/main/kotlin/com/minibrain/ai/agent/AgentPipeline.kt) を通じて処理されます。

1. **クエリ分類**: [QueryClassifier.kt](app/src/main/kotlin/com/minibrain/ai/agent/QueryClassifier.kt) で分類。`GENERAL_KNOWLEDGE` の場合は RAG をスキップして LLM が直接回答。それ以外は RAG 検索へ。
2. **クエリ展開 & HyDE**:
   - `QueryExpander` でクエリを 3〜8 件に展開。
   - `HyDE` で仮想回答を生成（タイムアウト 6秒、失敗時はスキップ）。
3. **並行検索 (Parallel Retrieval)**:
   - **BM25 検索**: 展開クエリ × FTS4 によるテキスト検索。
   - **メタデータ検索**: `fileName`, `path`, `tags`, `documentDate` を検索。また、ファイル名（拡張子除く3文字以上）がクエリの部分文字列として一致する場合（`topicMatch=true`）、先頭チャンクから 500文字をスニペットとして抽出（ADR-026）。
   - **ベクトル検索**: 元クエリ＋展開クエリ＋HyDE 仮想回答によるベクトル類似度検索（類似度 0.45 未満は除外）。
4. **候補の融合 (RRF)**:
   - RRF（Reciprocal Rank Fusion）によりマージ（重み: META=1.5 / VECTOR=1.0 / BM25=1.2）し、上位50件を抽出。
5. **再ランカー (LlmReranker)**:
   - LLM が候補をスコアリングし上位10件を選択。日付クエリや `topicMatch=true` の候補を優先。
   - 期間クエリ（`dateRange != null`）でマッチする結果がある場合、上位5件を再ランカー結果の先頭に強制マージ（ピン留め）して10件でカット。
6. **回答可能性判定 (CoverageCheck)**:
   - 判定が `true` の場合、回答を生成。
   - `false` の場合、ReAct ループ（DSL形式のツール呼び出し）へ移行。
7. **ReAct ループ (フォールバック)**:
   - Planner LLM が `glob`, `list_dir`, `read_file`, `grep`, `vector_search`, `rrf_search`, `timeline_search` などのツールを DSL（key:value）形式で発行し、情報を補填（最大6回）。
8. **引用統合・回答生成**:
   - 引用データを重複排除・優先度順に整列して LLM 回答プロンプトを構築し、回答をストリーミング出力。

---

## 5. よく変更するファイル
開発時によく変更するファイルの一覧です。リンクをクリックして対象コードを確認してください。

| 機能 / モジュール | 対象ファイル (ファイルリンク) |
| --- | --- |
| Search First フロー全体 | [SearchPipeline.kt](app/src/main/kotlin/com/minibrain/ai/search/SearchPipeline.kt) |
| クエリ展開プロンプト・ロジック | [QueryExpander.kt](app/src/main/kotlin/com/minibrain/ai/search/QueryExpander.kt) |
| HyDE (仮想回答生成) | [HyDE.kt](app/src/main/kotlin/com/minibrain/ai/search/HyDE.kt) |
| LLM 再ランカー (Reranker) | [LlmReranker.kt](app/src/main/kotlin/com/minibrain/ai/search/LlmReranker.kt) |
| 評価指標 / 評価ランナー | [EvalMetrics.kt](app/src/main/kotlin/com/minibrain/eval/EvalMetrics.kt) / [EvalRunner.kt](app/src/main/kotlin/com/minibrain/eval/EvalRunner.kt) |
| Coverage Check / 探索戦略 | [CoverageChecker.kt](app/src/main/kotlin/com/minibrain/ai/agent/CoverageChecker.kt) |
| パイプライン統合・制御フロー | [AgentPipeline.kt](app/src/main/kotlin/com/minibrain/ai/agent/AgentPipeline.kt) |
| ReAct 用 Planner プロンプト | [PlannerPrompt.kt](app/src/main/kotlin/com/minibrain/ai/agent/PlannerPrompt.kt) |
| クエリ種別分類 (Classifier) | [QueryClassifier.kt](app/src/main/kotlin/com/minibrain/ai/agent/QueryClassifier.kt) |
| ReAct ツール実行エンジン | [ToolExecutor.kt](app/src/main/kotlin/com/minibrain/ai/agent/tools/ToolExecutor.kt) |
| Glob パターンマッチング | [GlobMatcher.kt](app/src/main/kotlin/com/minibrain/ai/agent/tools/GlobMatcher.kt) |
| 引用統合・優先度・制限トークン制御 | [CitationIntegrator.kt](app/src/main/kotlin/com/minibrain/ai/agent/CitationIntegrator.kt) |
| 日付・期間解決ロジック | [DateResolver.kt](app/src/main/kotlin/com/minibrain/ai/agent/DateResolver.kt) |
| 検索トレースイベントの定義 | [AgentTraceEvent.kt](app/src/main/kotlin/com/minibrain/ai/agent/AgentTraceEvent.kt) |
| LLM 推論サービス・要約生成 | [LlmService.kt](app/src/main/kotlin/com/minibrain/ai/llm/LlmService.kt) |
| チャンク分割ロジック | [MarkdownChunker.kt](app/src/main/kotlin/com/minibrain/data/md/MarkdownChunker.kt) |
| マークダウンメタデータ抽出 | [MarkdownMetaExtractor.kt](app/src/main/kotlin/com/minibrain/data/md/MarkdownMetaExtractor.kt) |
| インデックス・リポジトリ処理 | [DocumentRepository.kt](app/src/main/kotlin/com/minibrain/data/repo/DocumentRepository.kt) |
| チャット履歴データベース処理 | [ChatRepository.kt](app/src/main/kotlin/com/minibrain/data/repo/ChatRepository.kt) |
| チャット UI 画面 | [ChatScreen.kt](app/src/main/kotlin/com/minibrain/ui/screens/ChatScreen.kt) |
| チャット ViewModel | [ChatViewModel.kt](app/src/main/kotlin/com/minibrain/ui/vm/ChatViewModel.kt) |
| DIコンテナ / サービス登録 | [AppContainer.kt](app/src/main/kotlin/com/minibrain/di/AppContainer.kt) |

---

## 6. ドキュメント更新ルール
コードに変更を加える際は、以下のドキュメントファイルを **コードの変更と同一のコミット / PR に含めて同時に更新** しなければなりません。「後で直す」は禁止です。

| ドキュメントファイル | 更新が必要となる変更 |
| --- | --- |
| [ADR.md](ADR.md) | アーキテクチャ上の重要な意思決定、検索アルゴリズムの再編、新ツールの追加など。新規決定は末尾に追記し、古い決定は「廃止」等のステータスに更新する。 |
| [agents.md](agents.md) | **(このファイル)** Antigravity / Claude Code 共通の指示、ファイル構成、制約事項、検索フローの更新等。コードと食い違った場合は直ちに修正する。ルート直下の `CLAUDE.md` はこのファイルへの symlink であり、実体は 1 つ。 |
| [README.md](README.md) | ユーザー向けの特徴、機能概要、画面イメージ、インストール方法の更新等。 |

---

## 7. 注意事項・エージェント向け指示

### 7.1 並行処理・スレッド制御
- **LiteRT-LM は単一スレッドでのみ動作可能**なため、LLM を利用する `QueryExpander` や `LlmReranker` などを非同期で並行実行してはなりません。必ず逐次的に呼び出してください。
- `EmbedderService` と `LlmService` の初期化は非常に重いため、必ず `Dispatchers.Default` などのバックグラウンドスレッドで行わせるコードにしてください。

### 7.2 日付・メタデータ抽出とクエリ解決 (ADR-025, ADR-026)
- **ファイル名逆引き**: `SearchPipeline.metadataSearch` では、ファイルの拡張子を除いた名前に部分一致するクエリを検出して優先抽出します（形態素解析に依存しない日本語ファイル検出のため）。
- **期間クエリ**: `dateRange != null` のときは、該当期間に属する文書（`documentDate` で判定）を優先検索し、上位5件を再ランカー結果の先頭に強制ピン留めします。
- **日付抽出**: `DocumentRepository.extractDateFromPath` は、ファイル名から日付（完全日付 `YYYY-MM-DD` または月のみ `YYYY-MM`）を正しくパースできるようにしてください。月のみの場合は月初の日付（`-01`）として処理します。
- **日付の LLM 参照優先度**: 日付に関連するクエリの場合、LLM に対して以下の優先順位で日付情報を解決するように回答プロンプト（`buildAnswerPrompt`）内で明確に指示を差し込みます。
  1. `[日付: YYYY-MM-DD]` のプレフィックス
  2. 本文内の「初回訪問日:」「日付:」などのラベル行
  3. 本文中の日付表記（`YYYY/MM/DD` など）

### 7.3 ReAct DSL
- ReAct ループで Planner LLM が出力するツール命令は、JVM 上でのユニットテスト実行の互換性を担保するため、**JSON ではなく独自の DSL 形式（key:value）** を用います。
- `buildPlannerHint` は 期間クエリ（`resolveDateRange`）→ 日付クエリ（YYYYMMDD 8桁 DB 検索）→ ファイル名一致 の順に解析して hint を構築します。

### 7.4 実装上の詳細な制約（定数・regression 対策）

変更時に壊れやすい箇所です。値を変える場合は必ず対応するテスト・呼び出し元も更新してください。

**キャッシュ・パフォーマンス**
- `AgentPipeline.run` の冒頭で `SearchRequestCache(treeUri, chunkDao, documentDao)` を 1 つ生成し、`SearchPipeline.search` / `RagPipeline.vectorOnlyTopK` / `retrieveTopChunks` / `buildPlannerHint` に注入します。同一リクエスト内の `chunkDao.getAllByTree` と `bytesToFloatArray` の重複を排除する目的です（ADR-024）。リクエスト終了で破棄するため書き込みとの整合性は考慮不要。
- `SearchPipeline.multiVectorSearch` は Embedder の `Mutex` により実質直列で走ります（並列ではない）。サブクエリ N 件 ≒ 30ms × N の追加コスト。embed 前に 元クエリ + 展開クエリ + HyDE を正規化 + distinct してから embed し、同一テキストへの重複 embed を排除します。

**チューニング定数**
- `mergeCandidatesRrf(weights=...)` の重みは `[meta=1.5, vector=1.0, bm25=1.2]`。順序を変える場合は SearchPipeline 側の `RRF_WEIGHTS` も合わせて更新すること。
- `MarkdownChunker.OVERLAP_CHARS = 120` / `SECTION_TAIL_CARRY = 80`。チャンクサイズを変更したら `MarkdownChunkerTest` の期待値も更新すること。
- `SearchPipeline.search` は `dateRange != null` かつ `dateRangeSearch` ヒットありのとき、上位 `DATE_RANGE_PIN_COUNT = 5` 件を Reranker 結果の先頭に強制マージします（ADR-025）。`docId::headingPath` で dedupe し、後段は Reranker 順を維持、最終的に `RERANK_TOP_K = 10` で切ります。`RRF_WEIGHTS` は変更しません（他クエリの順位を壊さないため）。
- `dateRangeSearch` のスニペットは `firstParagraph`（200 字）ではなく **doc の先頭 chunk テキストから `DATE_RANGE_SNIPPET_CHARS = 600` 字** を採ります（ADR-025）。chunk が空の場合のみ `firstParagraph` フォールバック。スニペットが薄すぎて LLM が「具体的な内容が記載されていません」と返す問題への対処です。
- `SearchPipeline.metadataSearch` は `topicMatch` ヒットだけ `TOPIC_MATCH_SNIPPET_CHARS = 500` で先頭 chunk テキストを採ります（ADR-026）。「初回訪問日:」ラベル行や本文 `YYYY/MM/DD` を 200 字の firstParagraph から漏らさないためです。chunks のロードは topicMatch がある場合のみ lazy で 1 回。

**日付抽出の詳細**
- `DocumentRepository.Companion.extractDateFromPath` は 完全日付（`YYYY[-/_.]MM[-/_.]DD` / `YYYY年MM月DD日` / 8桁 `YYYYMMDD`）と 月のみ（`YYYY-MM` / `YYYY年MM月` / 6桁 `YYYYMM`）の両方を抽出します。月のみは月初 1 日（`YYYY-MM-01`）として登録。**完全日付 → 月のみの順を厳守**し、`LocalDate.of` の validity + 年が `1990..今年` の範囲チェックで誤マッチを弾きます。`@VisibleForTesting` で JVM テストから直接呼べます（ADR-025）。
- `MarkdownMetaExtractor.extractDateFromContent` の YAML frontmatter ラベルは `date / created / published / updated / 日付 / 作成日 / 記録日`（IGNORE_CASE、`'"` クォート可）。**和暦パターン（`YYYY年MM月DD日` / `YYYY年MM月`）は見出し限定**で検出します — 本文中のカジュアルな言及で誤って `documentDate` が付くと Reranker の競合候補が増えて固有名詞ファイルが押し出される regression が起きるためです。Western 形式（`YYYY-MM-DD` / `YYYY/MM/DD`）は従来通り本文全行を走査します。

**トピックマッチと短絡判定（ADR-026）**
- `Citation.topicMatch: Boolean` は `SearchPipeline.metadataSearch` でファイル名 stem が query の substring として一致したヒットだけ true。RRF 融合は metaCandidates を先頭に置く既存挙動と `mergeCandidatesRrf` の first-wins により後段まで保持されます。
- `LlmReranker` は候補プロンプトに `topic=match` タグを出し、「いつ」クエリでは date フィールド優先と並んで topic=match 候補も上位に残すよう指示します。date 欄が空の固有名詞ファイルが押し出される回路を塞ぐためです。
- `CoverageChecker.check` は (1) 日付クエリ + `[日付:]` プレフィックス → 短絡 yes、(2) 日付クエリ + `topicMatch=true` 候補 → 短絡 yes、の 2 段で LLM 呼び出しを省きます。**topic match 短絡が無いと固有名詞ヒットが「no, visit_date」でリセットされて ReAct に落ちる事故が起きます。**

**回答プロンプトへの日付指示**
- `AgentPipeline.buildAnswerPrompt` は `dateRange != null` のとき、期間（start〜end）と「日付を拾う優先順位 3 段」の照合指示を context block 直後に差し込みます（ADR-025 + ADR-026）。`citations` に日付プレフィックス付きが 1 件もない場合は「`[日付:]` 付きは無いが本文ラベル / 表記を期間と照合せよ」というフェールセーフ文に切り替えます。
- `dateRange == null` でも `DATE_QUERY_REGEX`（`いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで`）にマッチすれば「日付に関する質問」ブロックを差し込み、同じ 3 段優先順位で本文から日付を拾うよう LLM に指示します（ADR-026）。固有名詞 +「いつ」クエリ（例:「サウナしきじにいつ行ったっけ」）で `documentDate` が無くても本文中の「初回訪問日: 2022/01/01」を回答に乗せられます。

### 7.5 DB マイグレーション運用
- 既存 `documents` レコードの `headings` / `first_para` / `tags` / `documentDate` は、次回の差分インデックス時に自動補完されます。強制的に補完したい場合は Settings → 再インデックスを実行します。

---

## 8. モデルファイルのパス

```
context.filesDir/models/gemma-4-E2B-it.litertlm        # LLM（約 2.5 GB）
context.filesDir/models/multilingual-e5-small-q.onnx   # Embedder（INT8 量子化、約 118 MB）
context.filesDir/models/e5-tokenizer.json              # XLM-RoBERTa SentencePiece tokenizer（約 17 MB）
```

モデルは `ModelDownloader` が Range リクエスト対応でダウンロードし、レジュームをサポートします。
