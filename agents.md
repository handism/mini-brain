# agents.md (Antigravity AI Agent Rules)

このファイルは、Google DeepMind の AI コーディングアシスタント **Antigravity** 向けに、プロジェクトの技術仕様、ビルド・実行コマンド、設計の制約、および注意事項をまとめたものです。
Antigravity はコードの変更、テストの実行、リファクタリングなどを行う際に、常にこのファイルを最優先で参照してください。

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
| [agents.md](agents.md) | **(このファイル)** Antigravity 向けの指示、ファイル構成、制約事項、検索フローの更新等。コードと食い違った場合は直ちに修正する。 |
| [CLAUDE.md](.claude/CLAUDE.md) | Claude Code 向けのビルドコマンド、制約、よく変更するファイルの更新等。 |
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
