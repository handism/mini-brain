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

### ONNX Runtime + multilingual-e5-small（Embedder）

- 依存:
  - `com.microsoft.onnxruntime:onnxruntime-android`（推論ランタイム）
  - `ai.djl.huggingface:tokenizers` + `ai.djl.android:tokenizer-native`（XLM-RoBERTa SentencePiece、Android arm64-v8a 用 `libdjl_tokenizer.so` を AAR で同梱）
- モデル: `Xenova/multilingual-e5-small` INT8 量子化版（`model_quantized.onnx` 約 118MB + `tokenizer.json` 約 17MB）
- 埋め込み次元: **384**（USE は 100 だった）
- 最大トークン長: 512
- **Prefix 必須**: クエリは `query: `, 文書は `passage: ` を付ける。`EmbedType` enum (`QUERY` / `PASSAGE`) を `embed()` の引数で渡す（誤用防止のため API レベルに昇格）
- API:
  ```kotlin
  // 文書（インデックス時）
  val vec = embedderService.embed(chunk.text, EmbedType.PASSAGE)
  // クエリ（検索時）
  val vec = embedderService.embed(question, EmbedType.QUERY)
  ```
- 内部処理: tokenize → ONNX run (`input_ids` + `attention_mask`) → `last_hidden_state[1, seq, 384]` → **attention_mask で重み平均 pooling** → **L2 正規化**
- `EmbedderService` は `Mutex` でシリアライズ済み（並列推論非推奨）
- 初期化はバックグラウンドスレッド（`Dispatchers.Default`）で行う
- MediaPipe `tasks-text` は依存から削除済み

### Room DB

- `FloatArray`（ベクトル）は `ByteArray` として保存
- 変換: `EmbedderService.floatArrayToBytes()` / `bytesToFloatArray()`
- コサイン類似度は全件メモリロードで計算（個人用途 = 数千チャンク以下を想定）
- DB バージョン: 6
  - v2→v3: `documents` テーブルに `headings`, `first_para`, `tags` カラムを追加
  - v3→v4: `documents` テーブルに `documentDate` カラムを追加（Recentness Ranking 用）
  - v4→v5: `folder_embeddings` テーブルを追加（Folder Embedding 用）
  - v5→v6: Embedder を E5 (384 次元) に乗り換えたため `chunks` / `chunks_fts` / `folder_embeddings` を空にし、`documents.contentHash` を `__REINDEX_REQUIRED_V6__` に置換（次回 `indexFolder()` 実行時に全文書を再 chunk + 再 embed）。**既存ユーザーは Settings → 再インデックスを実行する必要あり**

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
  1.5 HyDE（LLM）: 仮想回答を 1〜2 文生成（タイムアウト 6 秒、失敗時はスキップ）
  2. Parallel Retrieval:
     ├─ 展開クエリ × BM25（FTS4）            : 並行実行
     ├─ 展開クエリ × Metadata Search         : fileName/path/tags/documentDate を検索
     │                                       + fileName(拡張子除く 3 字以上) が query の substring に含まれる逆引き
     │                                       逆引きヒットは Citation.topicMatch=true を立て、snippet を先頭 chunk から 500 字採る（ADR-026）
     │                                       snippet 先頭に `[日付: YYYY-MM-DD]` を埋め込む
     └─ multiVectorSearch                     : 元クエリ + 展開クエリ + HyDE 仮想回答で順次ベクトル検索
                                              コサイン類似度 < 0.45 は閾値カットで除外
  3. Candidate Merge: RRF rank 融合（重み META=1.5 / VECTOR=1.0 / BM25=1.2）→ 上位 50 件
  4. LlmReranker（LLM）: 候補をスコアリングし上位 10 件を選択
     ※ Reranker 入力は `path=... heading=... date=... topic=... source=... snippet=...` 構造化形式
     ※ 日付クエリ（いつ・何月 等）は date フィールドを持つ候補 **と topic=match の候補** を両方優先する（ADR-026）
  4.5 dateRange pin（ADR-025）: dateRange != null かつ dateRangeSearch ヒットあり →
      上位 5 件を Reranker 結果の先頭に強制マージ（docId::headingPath で dedupe、RERANK_TOP_K=10 で切る）

↓ CoverageCheck（LLM）: query + top5 candidates を見て回答可能かを判定
  ※ 日付クエリ かつ snippet 先頭が `[日付:` で始まる候補があれば LLM を呼ばずに即 canAnswer=true で短絡
  ※ 日付クエリ かつ topicMatch=true の候補が top5 にあれば同じく LLM 短絡 yes（ADR-026）
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
  ※ dateRange != null または「いつ/何月/年前/去年/先月/先週」系の date クエリのとき、context block 直後に「日付を拾う優先順位」指示を注入（ADR-025 + ADR-026）:
    (1) `[日付: YYYY-MM-DD]` プレフィックス → (2) 本文中の「初回訪問日:」「訪問日:」「日付:」ラベル行 → (3) 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記
```

## モデルファイルのパス

```
context.filesDir/models/gemma-4-E2B-it.litertlm          # LLM（約 2.5 GB）
context.filesDir/models/multilingual-e5-small-q.onnx    # Embedder（INT8 量子化、約 118 MB）
context.filesDir/models/e5-tokenizer.json               # XLM-RoBERTa SentencePiece tokenizer（約 17 MB）
```

モデルは `ModelDownloader` が Range リクエスト対応でダウンロードし、レジュームをサポートする。

## よく変更するファイル

| 変更内容                             | 対象ファイル                                          |
| ------------------------------------ | ----------------------------------------------------- |
| Search First フロー全体              | `ai/search/SearchPipeline.kt`                         |
| クエリ展開                           | `ai/search/QueryExpander.kt`                          |
| HyDE（仮想回答生成）                 | `ai/search/HyDE.kt`                                   |
| LLM 再採点                           | `ai/search/LlmReranker.kt`                            |
| 評価指標 / 評価セットローダ          | `eval/EvalMetrics.kt` / `eval/EvalRunner.kt`          |
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

コードに変更を加える際は、以下の 4 ファイルを **常に最新の状態に保つ**。コード変更とドキュメント更新は同じ作業の一部であり、別タスクではない。

| ファイル | 更新が必要な変更 |
| --- | --- |
| `ADR.md` | アーキテクチャ判断・検索フロー再編・新ツール追加・既存判断の置き換えなど、設計意図を残すべき変更。新規 ADR を末尾に追記し、置き換えられた既存 ADR は「廃止」または「部分置き換え」とステータスを更新する |
| `agents.md` | Antigravity 向けの指示、ファイル構成、制約事項、検索フローの更新等。コードと食い違ったら即修正 |
| `.claude/CLAUDE.md` | 検索フロー図・ビルドコマンド・依存・DB スキーマ・「よく変更するファイル」表・注意事項など、Claude や開発者が実装中に参照するファクト。コードと食い違ったら即修正 |
| `README.md` | ユーザー向けの特徴・アーキテクチャ概要・画面構成・モデル情報など、外部から見える振る舞い |

運用ルール:

- 同一 PR / 同一コミットに含める。「あとで直す」は禁止
- アーキテクチャに影響する変更は、必ず ADR を追加するか既存 ADR を更新する（置き換える場合は旧 ADR を「廃止」「部分置き換え」にステータス変更し新 ADR へリンク）
- CLAUDE.md と README.md の検索フロー図・アーキテクチャ図は同じ意味を保つ（表現の粒度は差があってよい）
- コミット前に `git diff` で 4 ファイルがコード状態と整合しているかを確認する

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
- `SearchPipeline.multiVectorSearch` は Embedder の Mutex により実質直列で走る（並列ではない）。サブクエリ N 件 ≒ 30ms × N の追加コスト。embed 前に元クエリ + 展開クエリ + HyDE を正規化 + distinct してから embed する（同一テキストへの重複 embed を排除）
- `AgentPipeline.run` の冒頭で `SearchRequestCache(treeUri, chunkDao, documentDao)` を 1 つ生成し、`SearchPipeline.search` / `RagPipeline.vectorOnlyTopK` / `retrieveTopChunks` / `buildPlannerHint` に注入する。これにより同一リクエスト内の `chunkDao.getAllByTree` と `bytesToFloatArray` の重複を排除する（ADR-024）。リクエスト終了で破棄するため書き込みとの整合性は不要
- `SearchPipeline.VECTOR_MIN_SCORE = 0.45f` 未満のベクトル候補は Reranker に渡る前に捨てる。閾値は評価セット (`EvalRunner`) で調整する
- `mergeCandidatesRrf(weights=...)` の重みは [meta=1.5, vector=1.0, bm25=1.2]。順序を変える場合は SearchPipeline 側の `RRF_WEIGHTS` も合わせて更新する
- `HyDE` は LiteRT-LM 単一スレッド制約のため `QueryExpander` の直後に逐次実行する（並列不可）。失敗・タイムアウトは null フォールバック
- `MarkdownChunker.OVERLAP_CHARS = 120` / `SECTION_TAIL_CARRY = 80`。チャンクサイズを変更したら `MarkdownChunkerTest` の期待値も更新する
- 評価セットは `app/src/main/assets/eval/queries.sample.json` をテンプレに、ユーザーが自分の質問〜正解 relativePath を追加して使う。`EvalRunner.run(treeUri, cases, k)` で P@K / R@K / MRR を得る
- DB マイグレーション: 既存 `documents` レコードの `headings` / `first_para` / `tags` / `documentDate` は次回差分インデックス時に自動補完される。強制補完は Settings → 再インデックスで可能
- `DocumentRepository.Companion.extractDateFromPath` は完全日付（`YYYY[-/_.]MM[-/_.]DD` / `YYYY年MM月DD日` / 8桁 `YYYYMMDD`）と月のみ（`YYYY-MM` / `YYYY年MM月` / 6桁 `YYYYMM`）の両方を抽出する。月のみは月初 1 日（`YYYY-MM-01`）として登録。完全日付 → 月のみの順を厳守し、`LocalDate.of` validity + 年が `1990..今年` の範囲チェックで誤マッチを弾く。@VisibleForTesting で JVM テストから直接呼べる（ADR-025）
- `MarkdownMetaExtractor.extractDateFromContent` の YAML frontmatter ラベルは `date / created / published / updated / 日付 / 作成日 / 記録日`（IGNORE_CASE、`'"` クォート可）。和暦パターン (`YYYY年MM月DD日` / `YYYY年MM月`) は **見出し限定** で検出する（本文中のカジュアル言及で誤って `documentDate` が付き Reranker の競合候補が増えて固有名詞ファイルが押し出される regression を防ぐ）。Western 形式 (`YYYY-MM-DD` / `YYYY/MM/DD`) は従来通り本文全行を走査
- `AgentPipeline.buildAnswerPrompt` は `dateRange != null` のとき期間（start〜end）と「日付を拾う優先順位 3 段（`[日付:]` プレフィックス → 本文ラベル行 → 本文 YYYY/MM/DD・YYYY年MM月DD日）」の照合指示を context block 直後に差し込む（ADR-025 + ADR-026）。`citations` に日付プレフィックス付きが 1 件もない場合は「`[日付:]` 付きは無いが本文ラベル / 表記を期間と照合せよ」というフェールセーフ文に切り替える
- `AgentPipeline.buildAnswerPrompt` は `dateRange == null` でも `DATE_QUERY_REGEX`（`いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで`）にマッチすれば「日付に関する質問」ブロックを差し込み、同じ 3 段優先順位で本文から日付を拾うよう LLM に指示する（ADR-026）。固有名詞 +「いつ」クエリ（例: 「サウナしきじにいつ行ったっけ」）で `documentDate` が無くても本文中の「初回訪問日: 2022/01/01」を回答に乗せられる
- `Citation.topicMatch: Boolean` は SearchPipeline.metadataSearch でファイル名 stem が query の substring として一致したヒットだけ true（ADR-026）。RRF 融合は metaCandidates を先頭に置く既存挙動と `mergeCandidatesRrf` の first-wins により後段まで保持される
- `LlmReranker` は候補プロンプトに `topic=match` タグを出し、「いつ」クエリでは date フィールド優先と並んで topic=match 候補も上位に残すよう指示する（ADR-026）。date 欄空の固有名詞ファイルが押し出される回路を塞ぐ
- `CoverageChecker.check` は (1) 日付クエリ + `[日付:]` プレフィックス → 短絡 yes、(2) 日付クエリ + `topicMatch=true` 候補 → 短絡 yes、の 2 段で LLM 呼び出しを省く（ADR-026）。topic match 短絡が無いと固有名詞ヒットが「no, visit_date」でリセットされて ReAct に落ちる事故が起きる
- `SearchPipeline.metadataSearch` は `topicMatch` ヒットだけ `TOPIC_MATCH_SNIPPET_CHARS = 500` で先頭 chunk テキストを採る（ADR-026）。「初回訪問日:」ラベル行や本文 YYYY/MM/DD を 200 字 firstParagraph から漏らさないため。chunks のロードは topicMatch がある場合のみ lazy で 1 回
- `SearchPipeline.search` は `dateRange != null` かつ `dateRangeSearch` ヒットあり → 上位 `DATE_RANGE_PIN_COUNT = 5` 件を Reranker 結果の先頭に強制マージする（ADR-025）。`docId::headingPath` で dedupe、後段は Reranker 順を維持、最終的に `RERANK_TOP_K = 10` で切る。`RRF_WEIGHTS` は変更しない（他クエリの順位を壊さないため）
- `dateRangeSearch` のスニペットは `firstParagraph` (200 字) ではなく **doc の先頭 chunk テキストから `DATE_RANGE_SNIPPET_CHARS = 600` 字** を採る（ADR-025）。chunk が空の場合のみ `firstParagraph` フォールバック。pin に乗るスニペットが薄すぎて LLM が「具体的な内容が記載されていません」と返す問題への対処
- 期間クエリで「情報が含まれていません」と返答された場合の調査手順:
  ```
  adb logcat -s CoverageChecker:D SearchPipeline:D AgentPipeline:D DateResolver:D
  # 1. DateResolver で dateRange が解決されているか
  # 2. SearchPipeline で `dateRangeSearch range=... hits=N` の N が 0 になっていないか（0 なら documentDate が NULL の文書が多い → 再インデックス）
  # 3. SearchPipeline で `dateRange pin: pinned=N final=M` が出ているか
  # 4. CoverageChecker で `short-circuit: date query with dated candidate` が出ているか
  ```
