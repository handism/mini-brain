# agents.md (AI Agent Rules)

このファイルは、AI コーディングエージェント（Google DeepMind の **Antigravity**、Anthropic の **Claude Code** など）向けに、プロジェクトの技術仕様、ビルド・実行コマンド、設計の制約、および注意事項をまとめたものです。
エージェントはコードの変更、テストの実行、リファクタリングなどを行う際に、常にこのファイルを最優先で参照してください。

> **エージェント設定ファイルの配置方針**: 実体はすべて `.agents/` 配下（またはリポジトリルート）に置き、`.claude/` 側は symlink とします。実体が 1 つなのでツールごとの内容ズレが起きません。
>
> - `CLAUDE.md` → `agents.md`（このファイル）への symlink（大文字小文字を区別する FS でも壊れないよう、ファイル名と完全一致させる）
> - `.claude/skills/<name>` → `../../.agents/skills/<name>` への symlink
>
> スキルを追加する場合は `.agents/skills/<name>/SKILL.md` に実体を作り、`.claude/skills/` から symlink を張ってください。

---

## 1. プロジェクト概要
- **目的**: Android 12+ 向けのオンデバイス エージェント型 RAG アプリ。
- **依存性注入 (DI)**: [AppContainer.kt](app/src/main/kotlin/com/minibrain/di/AppContainer.kt) での手動DIコンテナ (`MiniBrainApp.container`) 管理（Hilt や Koin などの DI フレームワークは不使用）。

---

## 2. 重要な技術的制約

### 2.1 LiteRT-LM (LLM 推論エンジン)
- **依存関係**: `com.google.ai.edge.litertlm:litertlm-android`（バージョンは `gradle/libs.versions.toml` の `litertlm` で固定。`latest.release` は使わない、ADR-032）
- **注意**: **MediaPipe LLM Inference (`tasks-genai`) は非推奨 (deprecated) です。絶対に使用しないでください。**
- **モデル形式**: `.litertlm`（旧 `.task` 形式は使用不可）。
- **初期化と実行**:
  - `Engine` の初期化はバックグラウンドスレッド（`Dispatchers.Default` など）で行う必要があります。
  - GPU 初期化が失敗した場合は、CPU フォールバックを行ってください。詳細は [LlmService.kt](app/src/main/kotlin/com/minibrain/ai/llm/LlmService.kt) を参照。
- **ビルド設定**: `app/build.gradle.kts` の `kotlinOptions` に `-Xskip-metadata-version-check` が指定されている必要があります（Kotlin のコンパイルバージョンの差異をスキップするため）。
- **権限・マニフェスト**: native-library 宣言が `AndroidManifest.xml` に必要です。
  ```xml
  <uses-native-library android:name="libvndksupport.so" android:required="false"/>
  <uses-native-library android:name="libOpenCL.so" android:required="false"/>
  ```
- **実行スレッド制限**: LiteRT-LM は単一スレッド設計です。`QueryExpander` と `LlmReranker` などでの並行 LLM 呼び出しは不可であり、逐次実行を厳守してください。`LlmService` は `initialize` / `generateStream` / `close` を同じ `Mutex` で直列化しています（安全網であり、逐次呼び出しの原則は変わりません）。`generateStream` の collect 中に別の `generateStream` を呼ぶとデッドロックします（ADR-032）。JSON 配列だけを返させる呼び出し（`QueryExpander` / `LlmReranker`）は `generateJsonArray` を使い、`]` が出たら生成を打ち切り、タイムアウト（展開 15 秒 / Reranker 30 秒）なら元のクエリ・RRF 順に戻します。最後まで collect すると同じ語の繰り返しで `maxNumTokens` まで止まらず、1 件 100 秒かかったことがあります（ADR-044）。

### 2.2 ONNX Runtime + multilingual-e5-small (Embedder)
- **クエリ・文章プレフィックス**: クエリには `query: `、文書（チャンク）には `passage: ` のプレフィックス付与が必須。`EmbedType` enum を使用します。
- **実行制御**: `EmbedderService` 内の推論は `Mutex` でシリアライズされています。初期化はバックグラウンドスレッドで行い、並列推論は避けてください。

### 2.3 Room データベース
- **ベクトル保存**: `FloatArray`（384次元ベクトル）は `ByteArray` に変換して Room に保存します。変換用メソッドとして `EmbedderService.floatArrayToBytes()` / `bytesToFloatArray()` を利用してください。
- **類似度計算**: スケールが小さいため（個人用途、数千チャンク以下）、コサイン類似度は全件をメモリにロードして CPU 上で計算します。

### 2.4 Storage Access Framework (SAF)
- フォルダの選択には `ActivityResultContracts.OpenDocumentTree()` を使用し、`takePersistableUriPermission` で永続アクセス権を取得してファイルを読み込みます。
- フォルダの列挙（`MdFileReader`）は `DocumentsContract` を直接 query します。`DocumentFile` は query の失敗を握りつぶして空を返し、深い階層のノートが黙って欠けたため使いません。列挙の失敗は例外にして、インデックスを Error にします（ADR-041）。

---

## 3. アーキテクチャと検索フロー

検索・回答生成の流れ（クエリ分類 → 展開/HyDE → BM25・メタデータ・ベクトルの並行検索 → RRF → Reranker → CoverageCheck → ReAct）は [AgentPipeline.kt](app/src/main/kotlin/com/minibrain/ai/agent/AgentPipeline.kt) を起点に読むこと。壊れやすい定数・挙動は §5 を参照。

---

## 4. ドキュメント更新ルール
コードに変更を加える際は、以下のドキュメントファイルを **コードの変更と同一のコミット / PR に含めて同時に更新** しなければなりません。「後で直す」は禁止です。

| ドキュメントファイル | 更新が必要となる変更 |
| --- | --- |
| [ADR.md](ADR.md) | アーキテクチャ上の重要な意思決定、検索アルゴリズムの再編、新ツールの追加など。新規決定は末尾に追記し、古い決定は「廃止」等のステータスに更新する。 |
| [agents.md](agents.md) | **(このファイル)** Antigravity / Claude Code 共通の指示、ファイル構成、制約事項、検索フローの更新等。コードと食い違った場合は直ちに修正する。ルート直下の `CLAUDE.md` はこのファイルへの symlink であり、実体は 1 つ。 |
| [README.md](README.md) | ユーザー向けの特徴、機能概要、画面イメージ、インストール方法の更新等。 |

---

## 5. 注意事項・エージェント向け指示

### 5.1 並行処理・スレッド制御
- **LiteRT-LM は単一スレッドでのみ動作可能**なため、LLM を利用する `QueryExpander` や `LlmReranker` などを非同期で並行実行してはなりません。必ず逐次的に呼び出してください。
- `EmbedderService` と `LlmService` の初期化は非常に重いため、必ず `Dispatchers.Default` などのバックグラウンドスレッドで行わせるコードにしてください。
- suspend 文脈で例外を握るときは標準の `runCatching` ではなく `com.minibrain.util.runCatchingCancellable` を使ってください。`runCatching` は `CancellationException`（`withTimeout` のタイムアウトを含む）まで失敗扱いにするため、停止ボタンで「生成エラー」が出たり、タイムアウトが握りつぶされたりします（ADR-031）。
- `ChatViewModel.sendMessage` は `try/finally` で `isGenerating` を戻します。ジョブは LAZY 起動で `currentJob` を代入してから開始します（`viewModelScope` は `Main.immediate` のため）。

### 5.2 日付・メタデータ抽出とクエリ解決 (ADR-025, ADR-026)
- **ファイル名逆引き**: `SearchPipeline.metadataSearch` では、ファイルの拡張子を除いた名前に部分一致するクエリを検出して優先抽出します（形態素解析に依存しない日本語ファイル検出のため）。判定は `FileNames.stemMatchesAnyQuery` に集約されており、`PlannerHintBuilder.build` のファイル名候補抽出も同じ規則を使います。しきい値 `MIN_STEM_MATCH_CHARS = 1` は `歯.md` / `AI.md` のような 1 文字 stem を拾うための値です（ADR-026 の記載は 3 でしたが後日 1 に引き下げ）。
- **期間クエリ**: `dateRange != null` のときは、該当期間に属する文書（`documentDate` で判定）を優先検索し、上位5件を再ランカー結果の先頭に強制ピン留めします。`DateResolver.resolveDateRange` は年まで入った特定の日付（`YYYY-MM-DD` / `YYYY/MM/DD` / `YYYY年M月D日` / 8 桁 `YYYYMMDD`）を最初に見て、1 日だけの期間として返します（月全体に広げない）。「YYYY年の夏」のような年 + 季節も期間にします（ADR-042）。
- **日付抽出**: `DocumentRepository.extractDateFromPath` は、ファイル名から日付（完全日付 `YYYY-MM-DD` または月のみ `YYYY-MM`）を正しくパースできるようにしてください。月のみの場合は月初の日付（`-01`）として処理します。
- **日付の LLM 参照優先度**: 日付に関連するクエリの場合、LLM に対して以下の優先順位で日付情報を解決するように回答プロンプト（`AnswerPromptBuilder.buildAnswerPrompt`）内で明確に指示を差し込みます。
  1. `[日付: YYYY-MM-DD]` のプレフィックス
  2. 本文内の「初回訪問日:」「日付:」などのラベル行
  3. 本文中の日付表記（`YYYY/MM/DD` など）

### 5.3 ReAct DSL
- ReAct の observation は「最新 2 件を full、それ以前を compact」で Planner に渡します（`addObservation`）。新しい observation は常に full で追加してください（ADR-031）。
- ReAct ツールは現在の `treeUri` の外を読まないこと。`read_file` の docId 指定も `treeUri` 一致を確認します。`grep` / `vector_search` の scope はフォルダ単位で判定します（`diary` は `diary2/` に一致しない、ADR-031）。
- ReAct ループで Planner LLM が出力するツール命令は、JVM 上でのユニットテスト実行の互換性を担保するため、**JSON ではなく独自の DSL 形式（key:value）** を用います。
- `PlannerHintBuilder.build` は 期間クエリ（`resolveDateRange`）→ 日付クエリ（YYYYMMDD 8桁 DB 検索）→ ファイル名一致 の順に解析して hint を構築します。

### 5.4 実装上の詳細な制約（定数・regression 対策）

変更時に壊れやすい箇所です。値を変える場合は必ず対応するテスト・呼び出し元も更新してください。

**キャッシュ・パフォーマンス**
- `AgentPipeline.run` の冒頭で `SearchRequestCache(treeUri, chunkDao, documentDao)` を 1 つ生成し、`SearchPipeline.search` / `RagPipeline.vectorOnlyTopK` / `retrieveTopChunks` / `PlannerHintBuilder.build` に注入します。同一リクエスト内の `chunkDao.getAllByTree` と `bytesToFloatArray` の重複を排除する目的です（ADR-024）。クエリの埋め込みも `SearchRequestCache.queryEmbedding` で memoize し、同じ文を二度 embed しません（ADR-030）。`multiVectorSearch` は先に `RagPipeline.prefetchQueryEmbeddings` で全クエリを `embedAll` 1 回で埋め込みます（失敗時は従来の 1 件ずつに戻る、ADR-032）。リクエスト終了で破棄するため書き込みとの整合性は考慮不要。
- `RagPipeline` は doc / chunk を常に `SearchRequestCache` 経由で参照します。cache を渡さない単独呼び出し（EvalRunner・テスト）では呼び出し内だけのキャッシュを作ります。期間フィルタは `SearchRequestCache.documentsInDateRange` に集約し、`SearchPipeline.dateRangeSearch` と `ToolExecutor.timeline_search` が共有します。BM25 の MATCH 式の生成と失敗時の空返しは `ChunkDao.bm25SearchOrEmpty` に集約しています（ADR-031）。FTS4 は順位を付けないので、`ChunkDao.bm25SearchByTree` は全ヒットの `matchinfo(chunks_fts, 'pcnalx')` を取り、`Bm25Scorer` で採点して上位を返します。`LIMIT` だけで切る SQL に戻さないこと（rowid 順になり関連度と無関係になる）。検索クエリ側は 2 文字以上の語があれば日本語の 1 文字トークンを外します（`NGramTokenizer.toQueryTokens`、ADR-040）。
- `DocumentRepository.indexFolder` は `chunkBuffer`（900件単位）で `chunkDao.insertAll` と `insertFts` をまとめ、`writableDb.beginTransaction()` を使って単一の SQLite トランザクションでバッチ挿入します。また `indexFolderEmbeddings` や古い FTS/Chunk の削除時もバッチ化・トランザクションで保護し、auto-commit によるディスク I/O オーバーヘッドを排除しています。
- `indexFolder` / `clearFolder` は `DocumentRepository.indexMutex` で直列化されます（Home / Settings からの同時実行で挿入・削除が競合しないため）。`indexFolder` は失敗時に例外を投げず `IndexingState.Error` を出します（キャンセルのみ再送出）。
- `indexFolder` はフォルダから消えたファイルの document / chunk / FTS を削除し、`folder_embeddings` は `FolderEmbeddingDao.replaceAllByTree` で tree 単位に入れ替えます（ADR-029）。`clearFolder` も `folder_embeddings` を消します。
- チャンクの埋め込みは `EmbedderService.embedAll` で `EMBED_BATCH_SIZE = 8` 件ずつまとめて推論します。バッチが失敗したら 1 件ずつの `embed` に切り替え、失敗したチャンクだけを捨てます（ADR-029）。
- チャンクの埋め込み入力は `Chunk.embeddingText()`（「パス > 見出し」+ 改行 + 本文）です。DB の `text` と FTS には付けません。作り方を変えたら既存ベクトルと混ざらないよう、DB を上げて `contentHash` を書き換えるマイグレーションを足してください（`MIGRATION_6_7`、ADR-038）。
- `ensureFtsIndex` は件数が合わないとき、`chunks_fts` を全消去してから再投入します（孤立 FTS 行の解消のため）。

**チューニング定数**
- RRF 後の候補は `SearchPipeline.collapseByDoc` で 1 ファイル 1 件にまとめてから Reranker に渡します。代表は最上位のチャンクで、ファイル名一致（`topicMatch`）があればそちらを残し、日付ヒットの日付は代表のスニペットに `[日付:]` として付けます。Reranker の後、RRF 上位 `RRF_KEEP_COUNT = 3` 件のうち落とされたものを末尾に戻します（`keepRrfTop`、ADR-047）。
- `LlmReranker` は RRF 上位 `CANDIDATE_LIMIT = 20` 件を、本文 `SNIPPET_MAX_CHARS = 300` 字ずつで判定します。件数と字数はプロンプト量（約 2,500 トークン）とのトレードオフなので、片方を増やすなら片方を減らしてください。BM25 のスニペット長（`SearchPipeline.SNIPPET_CHARS`）もこれに揃えています（ADR-039）。
- `mergeCandidatesRrf(weights=...)` の重みは `[meta=1.5, vector=1.0, bm25=1.2]`。順序を変える場合は SearchPipeline 側の `RRF_WEIGHTS` も合わせて更新すること。
- `MarkdownChunker.OVERLAP_CHARS = 120` / `SECTION_TAIL_CARRY = 80`。チャンクサイズを変更したら `MarkdownChunkerTest` の期待値も更新すること。
- `SearchPipeline.search` は `dateRange != null` かつ `dateRangeSearch` ヒットありのとき、上位 `DATE_RANGE_PIN_COUNT = 5` 件を Reranker 結果の先頭に強制マージします（ADR-025）。固定する 5 件は日付順の先頭ではなく、Reranker の順位 → RRF の順位 → 日付順で doc 単位に選びます（`selectDateRangePins`、ADR-043）。
- 列挙の質問（`ENUMERATION_QUERY_REGEX`: 全部 / 全て / すべて / 一覧 / これまでに・の）で `dateRange == null` のときは、`RagPipeline.nearestFolder`（フォルダ埋め込み）で最も近いフォルダを選び、その上位フォルダが `ENUMERATION_MAX_DOCS = 8` 件以下なら上位フォルダごと、多ければ選んだフォルダだけの文書を全部、結果の先頭に置きます（`pinEnumerationFolder`、ADR-046）。
- `QueryExpander` のプロンプトは、中心になる名詞の英訳を 1〜2 件含めるよう指示しています（英語のノートを日本語の質問で拾うため、ADR-046）。`docId::headingPath` で dedupe し、後段は Reranker 順を維持、最終的に `RERANK_TOP_K = 10` で切ります。`RRF_WEIGHTS` は変更しません（他クエリの順位を壊さないため）。
- `dateRangeSearch` のスニペットは `firstParagraph`（200 字）ではなく **doc の先頭 chunk テキストから `DATE_RANGE_SNIPPET_CHARS = 600` 字** を採ります（ADR-025）。期間分岐・特定日付分岐のどちらも `dateHitCitation` を通します（ADR-029）。chunk が空の場合のみ `firstParagraph` フォールバック。スニペットが薄すぎて LLM が「具体的な内容が記載されていません」と返す問題への対処です。
- `SearchPipeline.metadataSearch` は `topicMatch` ヒットだけ `TOPIC_MATCH_SNIPPET_CHARS = 500` で先頭 chunk テキストを採ります（ADR-026）。「初回訪問日:」ラベル行や本文 `YYYY/MM/DD` を 200 字の firstParagraph から漏らさないためです。先頭 chunk の取得は `SearchRequestCache.firstChunkOf(docId)` 経由（`ChunkDao.getAllByTree` の `ORDER BY chunks.id` が「先頭」を保証するので外さないこと、ADR-030）で、chunks のロードは topicMatch がある場合のみ lazy で 1 回（`dateRangeSearch` / `ToolExecutor.timeline_search` も同じ API を使う）。

**日付抽出の詳細**
- `DocumentRepository.Companion.extractDateFromPath` は 完全日付（`YYYY[-/_.]MM[-/_.]DD` / `YYYY年MM月DD日` / 8桁 `YYYYMMDD`）と 月のみ（`YYYY-MM` / `YYYY年MM月` / 6桁 `YYYYMM`）の両方を抽出します。月のみは月初 1 日（`YYYY-MM-01`）として登録。**完全日付 → 月のみの順を厳守**し、`LocalDate.of` の validity + 年が `1990..今年` の範囲チェックで誤マッチを弾きます。`@VisibleForTesting` で JVM テストから直接呼べます（ADR-025）。
- `MarkdownMetaExtractor.extractDateFromContent` の YAML frontmatter ラベルは `date / created / published / updated / 日付 / 作成日 / 記録日`（IGNORE_CASE、`'"` クォート可）。**和暦パターン（`YYYY年MM月DD日` / `YYYY年MM月`）は見出し限定**で検出します — 本文中のカジュアルな言及で誤って `documentDate` が付くと Reranker の競合候補が増えて固有名詞ファイルが押し出される regression が起きるためです。Western 形式（`YYYY-MM-DD` / `YYYY/MM/DD`）は従来通り本文全行を走査します。

**トピックマッチと短絡判定（ADR-026）**
- `Citation.topicMatch: Boolean` は `SearchPipeline.metadataSearch` でファイル名 stem が query の substring として一致したヒットだけ true。RRF 融合は metaCandidates を先頭に置く既存挙動と `mergeCandidatesRrf` の first-wins により後段まで保持されます。
- `LlmReranker` は候補プロンプトに `topic=match` タグを出し、「いつ」クエリでは date フィールド優先と並んで topic=match 候補も上位に残すよう指示します。date 欄が空の固有名詞ファイルが押し出される回路を塞ぐためです。
- `CoverageChecker.check` は (1) 日付クエリ + `[日付:]` プレフィックス → 短絡 yes、(2) 日付クエリ + `topicMatch=true` 候補 → 短絡 yes、の 2 段で LLM 呼び出しを省きます。**topic match 短絡が無いと固有名詞ヒットが「no, visit_date」でリセットされて ReAct に落ちる事故が起きます。**

**回答プロンプトへの日付指示**
- `AnswerPromptBuilder.buildAnswerPrompt` は `dateRange != null` のとき、期間（start〜end）と「日付を拾う優先順位 3 段」の照合指示を context block 直後に差し込みます（ADR-025 + ADR-026）。`citations` に日付プレフィックス付きが 1 件もない場合は「`[日付:]` 付きは無いが本文ラベル / 表記を期間と照合せよ」というフェールセーフ文に切り替えます。
- `dateRange == null` でも `DATE_QUERY_REGEX`（`いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで`）にマッチすれば「日付に関する質問」ブロックを差し込み、同じ 3 段優先順位で本文から日付を拾うよう LLM に指示します（ADR-026）。固有名詞 +「いつ」クエリ（例:「スパイス堂にいつ行ったっけ」）で `documentDate` が無くても本文中の「初回訪問日: 2024/11/03」を回答に乗せられます。

### 5.5 UI 実装の約束事
- 画面の文言は `res/values/strings.xml` に置き、Composable からは `stringResource` で参照します（Compose UI テストは日本語の文言・contentDescription でノードを探すので、文言を変えたらテストも更新）。ViewModel / Repository 由来のメッセージ（エラー文、`IndexingState.Progress.fileName` など）は現状コード内の文字列のままです。
- 配色は Material 3 の dynamic color が前提です（`Color.kt` は `dynamicColor = false` 時のフォールバックのみ）。色は `MaterialTheme.colorScheme` から取り、固定色を足さないでください。
- フォルダ名の表示は `folderDisplayName(treeUri)` を使います（ボリューム ID の `primary:` などを落とす処理を画面ごとに書かない）。
- チャット履歴一覧は `ChatSessionDao.observeSummaries`（最新メッセージ日時順）を使います。削除は `ChatRepository.deleteSession` が返す `DeletedSession` を `restoreSession` に渡すと id を保ったまま戻せます。履歴画面はスワイプで削除し、TalkBack 用に同じ削除を `customActions` にも載せています（ゴミ箱ボタンは置かない）。
- 起動時、フォルダ選択済みなら Onboarding → Home → Chat と積んでチャットから始めます（`OnboardingViewModel.hasKnowledgeBase`、ADR-034）。再インデックスは Settings に一本化し、Home はフォルダ選択・変更とインデックス状態（ファイル数・最終インデックス日時）、最近のチャットを出します（ADR-035）。
- チャットの回答は吹き出しにせず全幅で表示し、`SelectionContainer` で部分選択できるようにしています。ユーザー発言だけが `primaryContainer` の吹き出しです。引用元はファイル単位のチップ（`distinctCitationSources`）で常に表示し、スニペットは「引用元 (n)」で展開します。
- `Scaffold` の中で `imePadding` を使うときは、先に `.consumeWindowInsets(padding)` を挟みます（edge-to-edge でナビゲーションバー分の余白が二重になるため）。
- タップできるアイコンは `IconButton` の既定サイズ（48dp）を縮めないでください。アイコン自体を小さくするのは構いません。
- 回答待ちの表示（段階の文言＋3 秒後から経過秒数）、再生成（最後の回答だけ、`ChatRepository.removeLastExchange`）、回答下のアクション 1 行化は ADR-035 を参照。チャットの本文・入力欄の最大幅は `CHAT_CONTENT_MAX_WIDTH`（720dp）です。
- `MarkdownText` は見出し・リスト・コード・水平線に加え、表（ヘッダー直後に区切り行がある場合のみ）と引用ブロックを描きます。
- 空のチャットの質問例は `buildChatSuggestions`（`ChatSuggestions.kt`）で知識ベースから作り、作れなければ `R.array.chat_suggestions` を使います。
- 履歴一覧と Home の「最近のチャット」は同じ `SessionListItem` を使います（`ChatSessionSummary.messageCount` / `lastAnswer`）。Home の最終インデックス日時は `MiniBrainApp` が `IndexingState.Done` を見て DataStore（`PREF_LAST_INDEXED_AT` / `PREF_LAST_INDEXED_TREE`）に記録します。
- ダークモードのウィンドウ背景は `values-night/themes.xml` で暗くしています。テーマを変えるときは昼夜の両方を直してください。
- フォルダの切り替えは Home・Settings とも `switchKnowledgeFolder`（`ui/vm/KnowledgeFolder.kt`）を通します。前のフォルダのインデックスを消すので、既にフォルダがあるときは `FolderChangeDialog` で確認してからピッカーを開きます（ADR-036）。
- インデックスの進み具合は Home だけでなく Chat（バナー）と Settings（再インデックス行）にも出します。Settings はインデックス中に再インデックス・フォルダ変更を押せません。
- チャットの失敗は `ChatViewModel.error`（`ChatError(kind, detail)`）で渡し、見出しは `kind` から strings.xml で決め、例外の中身は「詳細を表示」で折りたたみます。「再試行」は `regenerate` です。
- 検索ログ表示の既定値は `SHOW_SEARCH_LOG_DEFAULT = false`（開発者向けのため）。コピーボタンは回答側だけに付けます。
- 検索精度の評価（設定 → 開発者、`EvalScreen`）は SAF で選んだ評価セット JSON を `EvalRunner` に流します。検索まわりを変えたら前後で流して Recall / MRR / 候補 Recall を比べてください。`SearchPipelineResult.candidates`（RRF 後・Reranker 前）は評価で「候補に無い / 絞り込みで落ちた」を分けるのに使うので外さないこと（ADR-037）。評価レポートは「絞り込みで落ちた」を、候補の順位から「Reranker に渡らず（21 位以下）」と「Reranker が落とした」に分けて出します（ADR-047）。LLM の出力は実行ごとに揺れるので、1 回の評価で 1〜2 件動いただけでは判断しないこと。段階ごとの所要時間は `SearchTimingEvent` で検索ログと評価レポートの「内訳」列に出ます（ADR-044）。
- 評価はエミュレータでも回せます: `scripts/eval-emulator.sh [queries.json]`。AVD `minibrain-eval`（API 35 arm64、RAM 8GB）を起動し、debug APK と androidTest を入れ、`eval/models/` のモデルと `eval/notes/` を送って `EmulatorEvalTest` を実行し、レポートを `eval/reports/` に保存します。フォルダの権限は debug 専用の `GrantFolderActivity` を UiAutomator で操作して取ります。LLM は既定で CPU（`EVAL_GPU=true` で GPU を試す）なので、所要時間は目安です（1 回約 12 分）。検索まわりを変えたら、エージェントはこれを流して前後を比べてください（ADR-045）。 `EVAL_CASES=id1,id2` で一部のケースだけ、`EVAL_DUMP=true` で各ケースの候補の並び（`eval/reports/*-dump.md`）を出せます。
- アプリ内のロゴは `R.drawable.ic_logo`（ランチャーアイコンと同じ意匠の単色版）を `Icon` の tint で塗ります。Typography は title / label 系も日本語向けに字間を詰めています（`Type.kt`）。

### 5.6 DB マイグレーション運用
- 既存 `documents` レコードの `headings` / `first_para` / `tags` / `documentDate` は、次回の差分インデックス時に自動補完されます。強制的に補完したい場合は Settings → 再インデックスを実行します。

### 5.7 リリース（ADR-033）
- `v1.2.3` 形式のタグを push すると `.github/workflows/release.yml` が署名済み APK を GitHub Release に添付します。`versionCode` はタグから `major * 10000 + minor * 100 + patch` で計算します。
- release の署名は環境変数 `RELEASE_KEYSTORE_PATH` などがあるときだけ有効です（`app/build.gradle.kts`）。無いときは未署名のまま。keystore や鍵情報をリポジトリに入れないでください。
- release ビルドは `arm64-v8a` のみ（`ndk.abiFilters`）。debug は全 ABI。
- release は R8（`isMinifyEnabled = true`）で難読化されます。JNI からクラス名で引かれるライブラリ（LiteRT-LM、ONNX Runtime）は `proguard-rules.pro` で `-keep` してください。ONNX Runtime の AAR は consumer ルールを持たず、keep が無いと埋め込み推論で native abort します（v1.0.1 の実機クラッシュ）。
- `gradle/actions/setup-gradle` は v5 に固定しています。v6 以降はキャッシュ部分のライセンスが変わるため、上げる前に確認してください。
