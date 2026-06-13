# Architecture Decision Records

## ADR-001: LLM 推論エンジンに LiteRT-LM を採用

**日付:** 2026-06-08  
**ステータス:** 採用

### 背景

当初 MediaPipe LLM Inference API を候補として検討した。しかし 2026 年現在、Google は MediaPipe LLM Inference の Android/iOS 実装を deprecated にし、後継として LiteRT-LM を公式推奨している。

### 決定

LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) を採用する。

### 理由

- MediaPipe LLM Inference は deprecated であり、新規プロジェクトでの採用はリスクが高い
- LiteRT-LM は GPU(ML Drift) / CPU(XNNPack) 両バックエンドをサポートし、パフォーマンスが優位
- KV-キャッシュ管理をランタイムが内蔵しており、実装が簡潔になる
- Gemma 4 など最新モデルの `.litertlm` 形式に対応している

### トレードオフ

- LiteRT-LM は比較的新しいライブラリであり、情報が少ない
- `latest.release` を使用するため、破壊的変更のリスクがある → 定期的なバージョン確認が必要

---

## ADR-002: LLM モデルに Gemma 4 E2B を採用

**日付:** 2026-06-08  
**ステータス:** 採用

### 背景

Gemma シリーズの最新版（Gemma 4）を採用する方針とした。Gemma 4 には 1B モデルが存在せず、最小モデルは E2B（Effective 2B）である。

### 決定

Gemma 4 E2B (`.litertlm` 形式、HuggingFace: `litert-community/gemma-4-E2B-it-litert-lm`) を採用する。

### 理由

- Gemma 4 最小モデルであり、モバイル向け混合量子化（2/4/8bit）により RAM 約 0.8 GB で動作
- Apache 2.0 ライセンスで個人・商用問わず利用可能
- 多言語対応（日本語含む）
- Gemma 3 1B と比べて品質が向上しており、ディスクサイズは増えるがユーザー体験が向上する

### トレードオフ

- ディスクサイズ約 2.5 GB（旧 Gemma 3 1B の約 529 MB より大幅増）
- 初回ダウンロード量の増加により Wi-Fi 必須が実質的な前提条件になる
- ローエンド端末（RAM 4 GB 以下）では OOM が発生する可能性がある

---

## ADR-003: Embedder に MediaPipe TextEmbedder + USE Multilingual を採用

**日付:** 2026-06-08  
**ステータス:** 廃止（ADR-020 に置き換え）

### 背景

RAG の検索フェーズに意味ベクトル検索を採用するため、テキスト埋め込みモデルが必要。

### 決定

MediaPipe Tasks Text の TextEmbedder と Universal Sentence Encoder Multilingual を採用する。

### 候補との比較

| 案 | 特徴 |
|---|---|
| MediaPipe TextEmbedder + USE Multilingual | **採用**。日本語対応、オンデバイス、MediaPipe エコシステムとの親和性 |
| BM25 / キーワード検索 | 実装簡単・高速だが意味検索不可、日本語トークナイズが必要 |
| ハイブリッド検索 | 高精度だが実装複雑 |

### 理由

- 個人用 md は日本語を含むため意味検索が必須
- MediaPipe TextEmbedder はオンデバイスで動作し、クラウド依存がない
- USE Multilingual は日本語・英語混在テキストを同一ベクトル空間で扱える

### トレードオフ

- モデルサイズ約 280 MB
- 英語専用モデルと比べて精度が若干落ちる可能性がある
- 量子化モデル非対応のため `floatEmbedding()` を使用（`quantizedEmbedding()` は使わない）

---

## ADR-004: ベクトルストアに Room DB を採用

**日付:** 2026-06-08  
**ステータス:** 採用

### 背景

チャンクのベクトルと本文テキストを永続化するストレージが必要。

### 決定

Room DB を採用し、`FloatArray` を `ByteArray` に変換して保存する。類似度計算は全件をメモリにロードして行う。

### 候補との比較

| 案 | 特徴 |
|---|---|
| Room DB + メモリ内コサイン類似度 | **採用**。Android 標準、追加依存なし |
| SQLite 拡張（sqlite-vss 等） | ANN 検索が可能だが、Android への組み込みが困難 |
| ファイルベース（JSON 等） | シンプルだが型安全性・クエリ機能に劣る |

### 理由

- 個人用途（想定チャンク数〜数千）では全件メモリロードで十分なパフォーマンス
- Room は Android 標準ライブラリで安定性・ドキュメントが充実
- 将来 ANN（HNSW 等）に置き換える場合も `ChunkDao.getAll()` のインターフェースを変えるだけで済む

### トレードオフ

- SQLite は内積・コサイン類似度計算をネイティブにサポートしないため、全件ロードが必要
- チャンク数が数万に増えると起動時のキャッシュ構築がボトルネックになる可能性がある

---

## ADR-005: 依存性注入に手動シングルトンを採用

**日付:** 2026-06-08  
**ステータス:** 採用

### 背景

`LlmService`、`EmbedderService`、`AgentPipeline` などのヘビーなサービスクラスをどう管理するか。

### 決定

`MiniBrainApp` クラスで Kotlin の `by lazy` を使った手動シングルトンとして管理する。Hilt / Koin は使わない。

### 理由

- シングルモジュール・小規模なアプリであり、DI フレームワークの導入コストに見合わない
- `LlmService` と `EmbedderService` は初期化コストが高く、アプリライフサイクルと同期させる必要がある
- `by lazy` により必要なタイミングで初期化され、コードがシンプルに保たれる

### トレードオフ

- テスト時のモック差し替えが手動になる
- 依存グラフが `MiniBrainApp` に集中するため、大規模化した場合はリファクタリングが必要

---

## ADR-006: チャンク分割戦略に見出しベース分割を採用

**日付:** 2026-06-08  
**ステータス:** 採用

### 決定

`#`〜`###` の見出し階層でセクション単位に分割し、800 文字超のセクションは段落単位で再分割（50 文字オーバーラップ）する。

### 理由

- Markdown の意味的な区切りを尊重することで、チャンクのコンテキスト品質が向上する
- 見出しパス（例: `「設計ノート」 > 「DB 方針」`）を引用元として回答に表示でき、ユーザーが元ファイルを確認しやすい
- 固定長文字数分割より日本語・英語混在テキストで安定した品質が得られる

### トレードオフ

- 見出しのない md ファイルは 1 チャンクになる可能性がある
- 章が非常に長い場合の段落分割はコンテキストが断片化するリスクがある

---

## ADR-007: 検索アーキテクチャをエージェント型に移行

**日付:** 2026-06-09  
**ステータス:** 廃止（ADR-008 に置き換え）

### 概要

固定 intent フロー（diary_lookup / file_lookup / topic_research / general）を採用したが、intent の組み合わせに対応できないクエリや、Planner LLM の JSON 出力不安定が問題となり廃止。ADR-008 の ReAct ループに移行。

---

## ADR-008: 検索アーキテクチャを ReAct ループ + DCI に移行

**日付:** 2026-06-10  
**ステータス:** 採用（ADR-007 を廃止・置き換え）

### 背景

ADR-007 の固定 intent フローには以下の課題があった:

- `SearchPlan` の intent 定義を拡張しないと新しいクエリパターンに対応できない
- 「フォルダを絞ってからキーワード検索」のような複合クエリに対応不可
- ナレッジベースが「人間がディレクトリ・ファイル名で意味的に構造化している」前提を活かせていない
- Planner LLM の JSON パース失敗率が高く、フォールバック頻度が問題

### 決定

固定 intent フローを廃止し、**ReAct ループ + DCI（Directory/Content Intelligence）** を採用する。Planner LLM が `glob / list_dir / read_file / grep / vector_search / rrf_search` から毎ステップ1ツールを選び、観測結果を見て次のツールを決める反復探索を行う。

検索フロー:
```
質問
↓ QueryClassifier（MEMORY_SEARCH / GENERAL_KNOWLEDGE / TEMPORAL_SUMMARIZATION）
  GENERAL_KNOWLEDGE → RAG スキップ → LLM 直接回答
↓ buildPlannerHint（DB 先行解析）
  ├─ 期間クエリ: resolveDateRange → DateRange を hint に注入 / timeline_search 推奨
  ├─ 日付クエリ: YYYYMMDD 8桁で DB 検索 → docId を hint に直接注入
  │              未一致なら YYYYMMDD* / YYYY/MM/DD* / YYYY-MM-DD* の glob パターンを hint に列挙
  └─ ファイル名一致: fileName を hint に追加
↓ ReAct ループ（最大 6 回）
  Planner LLM → DSL key:value 形式で1ツールを出力 or ACTION: finalize
  → ToolExecutor 実行 → Observation 追加
  → DSL パース失敗 2 回連続 → RRF 即時フォールバック
↓ CitationIntegrator（重複除去・優先度整列・トークン budget 1200）
  citations 空 → RRF 強制フォールバック（セーフティネット）
↓ buildAnswerPrompt → LLM 回答生成
```

### ツール一覧

| ツール | 用途 | 実装 |
|---|---|---|
| `glob(pattern)` | パターンでファイル列挙（`**` 再帰対応） | DB 全件 + `GlobMatcher.globToRegex` フィルタ |
| `list_dir(folder)` | フォルダ直下のサブフォルダ・ファイル一覧 | prefix フィルタ |
| `read_file(docId\|path)` | ファイル全文取得（chunks 連結） | `ChunkDao.getByDoc` → headingPath 順、8000 字上限。3000 字超は LLM 要約して単一 citation |
| `grep(query, scope?)` | キーワード全文検索 | FTS4 BM25、scope は事後フィルタ |
| `vector_search(query, scope?, k)` | 意味類似検索 | `EmbedderService.embed` + `CosineSimilarity.topK` |
| `rrf_search(query, k)` | BM25 + ベクトル RRF 融合 | 既存 `RagPipeline.retrieveTopChunks` に委譲 |
| `timeline_search(start, end, k)` | 期間指定で documentDate フィルタ | `DocumentDao.getByDateRange` → 先頭 chunk をスニペットに |

### Citation 優先度

`READ_FILE > GREP > VECTOR > RRF > GLOB > FOLDER`（`CitationIntegrator` で dedup・整列）

### 新規ファイル

| ファイル | 役割 |
|---|---|
| `ai/agent/AgentTypes.kt` | `AgentTool` / `ToolCall` / `ToolResult` / `Observation` / `PlannerDecision` / `AgentResult` |
| `ai/agent/AgentTraceEvent.kt` | トレースイベント型（`PlannerDecisionEvent` / `ToolCallEvent` / `ObservationEvent` / `FinalAnswerEvent`） |
| `ai/agent/PlannerPrompt.kt` | Planner プロンプト生成 + DSL key:value パーサ（`org.json` 非使用） |
| `ai/agent/CitationIntegrator.kt` | 重複除去・優先度整列・budget 制御（純関数） |
| `ai/agent/QueryClassifier.kt` | クエリ種別判定（MEMORY_SEARCH / GENERAL_KNOWLEDGE / TEMPORAL_SUMMARIZATION） |
| `ai/agent/tools/ToolExecutor.kt` | 7 ツールの実装 |
| `ai/agent/tools/GlobMatcher.kt` | glob → Regex 変換（`**`→`.*` / `*`→`[^/]*` / `?`→`[^/]`） |

### 削除ファイル

- `ai/agent/SearchPlan.kt` — plannerHint 文字列に役割移譲

### 設計のポイント

**DSL key:value パーサ（JSON 非使用）**  
`org.json.JSONObject` は Android stub であり JVM ユニットテストで機能しない。`PlannerPrompt` は Planner LLM に JSON でなく `TOOL: glob\nPATTERN: foo` のような key:value DSL を出力させ、`KEY_VALUE_RE` regex で全キーを抽出してパースする。

**Observation の token 制御**  
最新 2 件: full（各 1500 字）、それ以前: 1行サマリ、合計上限 5000 字。

**observations=0 時の finalize 禁止**  
小型モデルが観測ゼロで即 finalize するのを防ぐため、プロンプトで明示的に禁止する。

### 理由

- DCI 戦略（glob → read_file）は構造化された KB に対してベクトル検索より精度が高い
- LLM が自由にツールを選ぶことで事前に想定していないクエリパターンにも対応できる
- フォールバック多層化（ParseError 2 回 / citations 空 / LLM 未初期化）により旧設計と同等以上の安全性を確保

### トレードオフ

- **レイテンシ増加**: 最大 6 回の LLM 呼び出し（各 3〜10 秒）で、旧設計比で応答時間が大幅に増加する。精度とのトレードオフとして許容する
- **Gemma 4 E2B の JSON 品質**: 小型モデルのため observations 数に応じてプロンプトを切り替えて軽減
- **全件メモリロード**: `vector_search` は `ChunkDao.getAll()` を毎回実行する。数万チャンクで問題になった場合は ANN（HNSW 等）への置換を検討

---

## ADR-009: 日付ファイル検索を DB 先行解決に変更

**日付:** 2026-06-10  
**ステータス:** 採用

### 背景

ADR-008 の当初実装では、日付クエリに対して `glob("YYYY/MM/DD*")` パターンを Planner hint に渡していた。しかしナレッジベースの日付命名形式は `YYYY/MM/DD`・`YYYYMMDD`・`YYYY-MM-DD` など統一されておらず、LLM が正しい形式を選べない問題があった。

### 決定

`buildPlannerHint` の中で、日付の8桁数字（`YYYYMMDD`）を使って `documents.relativePath` を DB 検索する。区切り文字（`/`・`-`）を除去して比較することで形式に依存しない照合を行う。

- **一致あり** → `[d=42] diary/20260609.md` のように `docId` を hint に直接注入。LLM は `read_file(docId=42)` を一発で呼べる
- **一致なし** → `"20260609*" or "2026/06/09*" or "2026-06-09*"` の3形式 glob パターンを hint に列挙してフォールバック

### 理由

- 命名規則をアプリ側で仮定せずに済む
- `docId` が確定すれば LLM の glob 試行が不要になり、反復回数と失敗リスクが減る

### トレードオフ

- `buildPlannerHint` が `DocumentDao.getAllByTree` を呼ぶため、DB アクセスが追加で発生する（ただし `ToolExecutor` の `allDocs` キャッシュとは別のタイミング）

---

## ADR-010: QueryClassifier によるクエリ種別ルーティング

**日付:** 2026-06-11  
**ステータス:** 採用

### 背景

「Kotlin とは何ですか？」のような一般知識の質問でも RAG ループが動作し、無駄な LLM 呼び出しと検索レイテンシが発生していた。

### 決定

`QueryClassifier.classify()` を AgentPipeline の入口に追加し、3 種別に分類する。

| 種別 | 条件 | ルーティング |
|---|---|---|
| `GENERAL_KNOWLEDGE` | `とは何？`・`の仕組み`・英語識別子＋`の使い方` 等の明確なパターン | RAG スキップ → LLM 直接回答 |
| `TEMPORAL_SUMMARIZATION` | `DateResolver.resolveDateRange` が DateRange を返す | ReAct ループ（`timeline_search` 推奨） |
| `MEMORY_SEARCH` | 上記以外（デフォルト） | ReAct ループ（通常フロー） |

### 理由

- 一般知識クエリの応答速度を大幅に改善（ReAct ループ最大 6 回 → 直接回答）
- 誤分類コストの非対称性: GENERAL_KNOWLEDGE への誤分類は RAG データを使えないリスクがあるため、パターンは意図的に厳しめに設定

### トレードオフ

- パターンに完全にマッチしない一般知識クエリは `MEMORY_SEARCH` にフォールバックする（精度より安全性を優先）
- 新しいパターンが必要な場合は `GENERAL_KNOWLEDGE_PATTERNS` リストへの追加が必要

---

## ADR-011: Planner 出力形式を JSON から DSL key:value に変更

**日付:** 2026-06-11  
**ステータス:** 採用（ADR-008 の設計ポイントを置き換え）

### 背景

ADR-008 では Planner LLM が JSON（`{"tool":"glob","args":{"pattern":"..."}}` 形式）を出力し、`PlannerPrompt.parseDecision` が regex で抽出していた。小型モデル（Gemma 4 E2B）は JSON の閉じ括弧を省略したり余分なテキストを混入させたりすることが多く、パース失敗率が高かった。

### 決定

Planner プロンプトのツール例示を DSL key:value 形式に変更する。

```
TOOL: glob
PATTERN: 2026/06/*

TOOL: read_file
DOC_ID: 42

ACTION: finalize
REASON: 情報が揃った
```

`PlannerPrompt.parseDecision` は `KEY_VALUE_RE`（`^([A-Za-z_]+):\s*(.+)$`）で全行をスキャンし、`TOOL` キーでルーティングする。

### 理由

- JSON より構造が単純でモデルが間違えにくい
- 閉じ括弧・引用符が不要なためトークン数が減り、生成速度も向上する
- `org.json` を使わないため JVM ユニットテスト（`PlannerPromptTest`）がそのまま動く

### トレードオフ

- DSL はカスタム仕様であり、モデルにとって学習データが少ない可能性がある
- プロンプトのツール例示とパーサの定義が対応している必要があるため、新ツール追加時は両方を更新する必要がある

---

## ADR-012: Timeline Search ツールの追加

**日付:** 2026-06-11  
**ステータス:** 採用

### 背景

「去年の夏に何をしていた？」のような期間クエリに対して、glob / grep では期間フィルタができなかった。`DateResolver.resolveDateRange` が `DateRange` を解決できても、ToolExecutor にその期間でファイルを絞るツールがなかった。

### 決定

`timeline_search(start, end, limit)` ツールを追加する。`DocumentDao.getByDateRange(treeUri, startDate, endDate)` で `documentDate` カラムを ISO-8601 文字列として範囲検索し、ヒットした文書の先頭チャンクをスニペットとして `Citation` に投入する。

- `documentDate` は `DocumentRepository` のインデックス時にファイルパスから抽出（`YYYY-MM-DD` / `YYYYMMDD` / `YYYY/MM/DD` 形式を正規化）して保存する
- `buildPlannerHint` が `resolveDateRange` で DateRange を取得できた場合、`timeline_search` を推奨する hint を挿入する

### 理由

- 期間クエリを O(ファイル数) の DB range scan で解決でき、glob の試行錯誤が不要
- `QueryClassifier.TEMPORAL_SUMMARIZATION` → `timeline_search` のパスが明確になる

### トレードオフ

- `documentDate` が null のファイル（日付形式でない命名）はヒットしない → grep / vector_search へのフォールバックが依然必要

---

## ADR-013: Recentness Ranking（RRF スコアへの新鮮度加点）

**日付:** 2026-06-11  
**ステータス:** 採用

### 背景

RRF のスコアはランク位置だけで決まるため、1年前の文書と昨日の文書が同じランクにいると同スコアになる。日記・メモ用途では「最新情報」を優先したいケースが多い。

### 決定

RRF スコアに指数減衰の `freshnessBoost` を加算する。

```kotlin
finalScore = rrfScore + FRESHNESS_BOOST_MAX × exp(−daysSince / FRESHNESS_DECAY_DAYS)
// FRESHNESS_BOOST_MAX = 0.010f, FRESHNESS_DECAY_DAYS = 90f
// 30日経過: +0.0072, 1年: +0.0017, 3年: +0.0001
```

`documentDate` は ADR-012 で保存した値を使用。`null` の場合は加点なし。

### 理由

- RRF max score（両方 rank=1）≈ 0.032。boost max を約 30% に設定することで、低ランクの古い文書が常に上位を取ることを防ぎつつ新しい文書を優遇できる
- 指数関数により古い文書のスコアが滑らかに減衰し、急激な断絶がない

### トレードオフ

- 日付のない文書（`documentDate = null`）はランキングで不利になる
- `FRESHNESS_BOOST_MAX` と `FRESHNESS_DECAY_DAYS` のチューニングが必要

---

## ADR-014: Folder Embedding（フォルダ単位の意味ベクトル）

**日付:** 2026-06-11  
**ステータス:** 採用

### 背景

ベクトル検索はチャンク単位で動作するため、「仕事フォルダに関する質問」のようなフォルダ全体を対象とするクエリに弱かった。

### 決定

インデックス時にフォルダ直下のファイル名・見出しを連結したテキストを埋め込み、`folder_embeddings` テーブルに保存する。`RagPipeline.retrieveTopChunks` で `folderSearch` を並列実行し、フォルダレベルの `Citation`（`source=FOLDER`）として結果に追加する。

- `CitationIntegrator` での優先度は最低位（`GLOB > FOLDER`）
- DB スキーマ: `folder_embeddings(id, path, treeUri, embedding)` に `UNIQUE INDEX (path, treeUri)`

### 理由

- フォルダ名・ファイル名が意味的にまとまっている KB では、チャンク検索より高精度でフォルダを絞り込める
- 既存の `RagPipeline` に `folderSearch` を追加するだけで導入でき、変更範囲が小さい

### トレードオフ

- フォルダ数が多い場合は埋め込み生成コストが増加する
- フォルダ単位 Citation は snippet が「フォルダ全体に関連するコンテンツ」という固定テキストのため、回答生成には直接貢献しない（フォルダの存在を示すメタ情報として機能する）

---

## ADR-015: read_file 大ファイルの LLM 要約

**日付:** 2026-06-11  
**ステータス:** 採用

### 背景

`read_file` で 3000 字を超えるファイルをそのまま `Citation.snippet` に入れると、`CitationIntegrator` のトークン budget（1200 tokens）を1ファイルで使い切り、他の引用が入らなくなる問題があった。

### 決定

`ToolExecutor.executeReadFile` でファイル全文が `SUMMARIZE_THRESHOLD_CHARS`（3000 字）を超えた場合、`LlmService.summarize()` で 500 字以内の要約を生成してから単一の `Citation` として投入する。要約失敗時は先頭 3000 字に切り詰めてフォールバック。

### 理由

- 大ファイルでもトークン budget を圧迫しない
- 要約内容を LLM が生成するため、冗長な本文よりも回答プロンプトのコンテキスト品質が向上する可能性がある

### トレードオフ

- 要約のために追加の LLM 呼び出しが1回発生し、レイテンシが増加する
- 要約精度は Gemma 4 E2B の能力に依存する

---

## ADR-016: 検索アーキテクチャを Search First → Rerank → Agent に移行

**日付:** 2026-06-12  
**ステータス:** 採用（ADR-008 の ReAct ループは Fallback に降格）

### 背景

ADR-008 の ReAct ループは「LLM がどの文書を探すか決める」アーキテクチャであり、LLM の推論能力が候補収集の上限になっていた。Recall 不足により以下のクエリで精度が不十分だった:

- 「5年前の3月何してた？」
- 「Datadog関連で何をやった？」
- 「AWS Organizations導入したのいつ？」
- 「TOKIUMについて教えて」

原因は Agent の推論能力ではなく、**候補の取りこぼし（Recall 不足）**。

### 決定

**Search First → Rerank → Agent** アーキテクチャを採用する。

```
User Query
↓ QueryExpander（LLM）: クエリを 3〜8 件に展開
↓ Parallel Retrieval: 展開クエリ × (BM25 + Metadata) を並行実行 + 元クエリの Grep
↓ Candidate Merge: 重複排除して最大 50 件に統合
↓ LlmReranker（LLM）: 候補をスコアリングし上位 10 件に絞り込み
↓ Citation → Answer（SearchPipeline 結果が空の場合のみ ReAct ループにフォールバック）
```

#### QueryExpander

- `LlmService.generateStream()` でクエリを 3〜8 件に展開
- JSON 配列 `["クエリ1", "クエリ2", ...]` 固定出力。パース失敗時は元クエリのみ使用
- 実装: `ai/search/QueryExpander.kt`

#### Metadata Search（新ツール）

- `DocumentEntity` の `fileName` / `relativePath` / `tags` / `documentDate` をメモリ内フィルタで検索
- BM25 が本文を対象とするのに対し、メタデータ検索はパス・タグを直接参照するため、日付フォルダ・会社名など **構造化された命名規則** のある KB で Recall が向上する
- `Citation.source = SourceType.METADATA`（優先度: GREP の次）

#### LlmReranker

- 最大 30 件の候補を `[i] headingPath: snippet(100字)` 形式でプロンプトに展開
- LLM に上位 K 件のインデックスを JSON 配列で出力させる（スコア値ではなく順序インデックス）
- パース失敗時は元順序の先頭 K 件にフォールバック
- 実装: `ai/search/LlmReranker.kt`

#### ReAct ループの位置づけ変更

ADR-008 の ReAct ループは **SearchPipeline が空を返した場合のフォールバック** に降格。削除はしない。

### 新規ファイル

| ファイル | 役割 |
|---|---|
| `ai/search/QueryExpander.kt` | LLM によるクエリ展開（JSON パーサ内蔵） |
| `ai/search/LlmReranker.kt` | LLM による候補再採点（インデックス出力方式） |
| `ai/search/SearchPipeline.kt` | Search First フロー全体のオーケストレーション |

### 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `ai/agent/AgentPipeline.kt` | `SearchPipeline` を先行実行。空のときのみ `runReActLoop()` を呼び出す |
| `ai/agent/AgentTraceEvent.kt` | `QueryExpansionEvent` / `BM25SearchHitEvent` / `MetadataSearchHitEvent` / `GrepSearchHitEvent` / `CandidateMergeEvent` / `RerankEvent` を追加 |
| `ai/rag/RagPipeline.kt` | `SourceType.METADATA` を追加 |
| `ai/agent/CitationIntegrator.kt` | `METADATA` を `SOURCE_PRIORITY` に挿入（`GREP > METADATA > VECTOR`） |
| `ai/agent/QueryClassifier.kt` | `について教えて` パターンを `GENERAL_KNOWLEDGE` から削除（個人ノートでも多用される表現のため `MEMORY_SEARCH` に倒す） |
| `MiniBrainApp.kt` | `QueryExpander` / `LlmReranker` / `SearchPipeline` の lazy インスタンスを追加 |
| `ui/screens/ChatScreen.kt` | 新トレースイベント 6 種を Search Trace として表示 |

### 理由

- **Recall 最大化**: 候補収集を複数手法の並行実行に委ね、LLM は選別（Rerank）に専念させる
- **メタデータ検索の追加**: 日付フォルダ・会社名など本文以外に意味がある KB に対して効果的
- **ReAct ループの保持**: フォールバックとして残すことでフォルダ探索・複数ファイル横断クエリへの対応力を維持

### トレードオフ

- **レイテンシ増加**: QueryExpander（LLM×1）+ 並行検索 + LlmReranker（LLM×1）が追加される。既存の ReAct ループが走らない分で一部相殺されるが、初回応答は遅くなる
- **LiteRT-LM 単一スレッド制約**: `QueryExpander` と `LlmReranker` は逐次実行が必須。並行 LLM 呼び出しは不可
- **小型モデルの JSON 品質**: Gemma 4 E2B の JSON 出力は不安定なため、両クラスともパース失敗フォールバックを実装済み
- **メモリフィルタのスケール**: `metadataSearch` は `documentDao.getAllByTree()` で全件ロード。個人用途（数千件）では問題なし

---

## ADR-017: Coverage Check + Explorer Strategy の追加

**日付:** 2026-06-12  
**ステータス:** 採用（ADR-016 のフォールバック条件を拡張）

### 背景

ADR-016 の Search First では「SearchPipeline が 1 件以上 candidates を返した場合は ReAct をスキップ」という単純条件だった。しかし「サウナしきじにはいつ行ったっけ？」のような日付クエリでは、SearchPipeline がサウナ関連記事を返しても **訪問日付を含む文書** が上位に入らないケースがあり、正しく回答できなかった。

**根本原因**: 「candidates > 0 = 回答可能」という仮定が誤り。10 件の候補が返ってもすべて不正解のケースが存在する。

### 決定

Search First の後に **CoverageCheck** ステップを追加し、「証拠が揃っているか」を LLM で評価する。

```
SearchPipeline
↓
CoverageCheck（LLM）
  canAnswer=true  → 回答生成
  canAnswer=false → ExplorerStrategy 決定 → ReAct ループ
↓（ReAct）
CitationIntegrator → LLM 回答生成
```

#### CoverageChecker

- `query` + `candidates top5` を LLM に渡し、`yes` / `no, 不足情報` の単純テキスト形式で出力させる
- パース: 先頭が `yes` → `canAnswer=true`、`no` → `canAnswer=false` + カンマ区切りの `missingInformation`
- 判定不能（出力が曖昧）→ `canAnswer=true` にデフォルトし、不要な ReAct 起動を抑制
- 実装: `ai/agent/CoverageChecker.kt`

#### ExplorerStrategy

`missingInformation` の内容に応じて探索戦略を決定し、ReAct の `plannerHint` 先頭に注入する。

| strategy | 条件 | hint 内容 |
|---|---|---|
| `EXPAND_TIME` | missing に `date` / `visit` / `time` / `when` を含む | `timeline_search または metadata_search(date) で訪問日・イベント日時を調べてください。` |
| `EXPAND_TOPIC` | 上記以外 | `read_file または grep で詳細内容を調べてください。` |

#### LlmReranker の日付クエリ対応

`いつ / 何月 / 何日 / 何年 / 年前 / 月前 / 去年 / 先月 / 先週 / いつから / いつまで` を含むクエリを `isDateQuery()` で検出し、rerank プロンプトに「日付情報を含む候補を優先する」旨を追記する。

### 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `ai/agent/CoverageChecker.kt`（新規） | CoverageResult(canAnswer, missingInformation)、LLM プロンプト + テキストパーサ |
| `ai/agent/AgentTraceEvent.kt` | `CoverageCheckEvent` / `ExplorerStrategyEvent` を追加 |
| `ai/agent/AgentPipeline.kt` | ReAct 起動条件を `isEmpty \|\| !canAnswer` に変更。`resolveExplorerStrategy()` 追加。`runReActLoop` に `explorerHint` パラメータ追加 |
| `ai/search/LlmReranker.kt` | `isDateQuery()` + 日付クエリ時のプロンプト追記 |
| `MiniBrainApp.kt` | `CoverageChecker` を lazy 生成して `AgentPipeline` に注入 |
| `ui/screens/ChatScreen.kt` | `CoverageCheckEvent`（OK=tertiary / NG=error）/ `ExplorerStrategyEvent` の表示追加 |

### 理由

- 「候補が存在する」ではなく「質問に答えられる証拠が揃っている」を ReAct 起動の判定基準にする
- 不足情報から探索戦略を自動決定することで、日付クエリに対して `timeline_search` を優先的に実行できる
- LlmReranker の日付認識により、rerank 段階でも日付情報を持つ候補が落ちにくくなる

### トレードオフ

- **レイテンシ追加**: candidates が返った場合に LLM 呼び出しが 1 回増加する（`canAnswer=true` のケースも含む）
- **LLM 精度依存**: 小型モデルが `yes/no` 形式を確実に守らない場合、デフォルト `canAnswer=true` に倒すため偽陽性（回答できるのに ReAct スキップ）は起きにくいが、偽陰性（回答できるのに ReAct 起動）の余地がある
- **LiteRT-LM 単一スレッド**: CoverageChecker は LlmReranker の後（SearchPipeline 返却後）に逐次実行されるため、スレッド競合は発生しない

---

## ADR-018: Parallel Retrieval を BM25 + Metadata + Vector に再編、CoverageCheck 短絡、EXPAND_TIME ヒント見直し

**日付:** 2026-06-12
**ステータス:** 採用（ADR-016・ADR-017 を部分置き換え）

### 背景

ADR-016 / ADR-017 の Search First 導入後、本文先頭にメタ行（`初回訪問日: 2022/01/02` 等）を埋めるタイプのノートに対する日付クエリ（例: 「サウナしきじにいつ行ったっけ？」）で正答できないリグレッションが発生した。

原因の連鎖:

1. `SearchPipeline.metadataSearch` の snippet が `doc.firstParagraph` のみで、メタ行が含まれない
2. `CoverageChecker` が snippet に日付を見出せず `canAnswer=false` を返す
3. `resolveExplorerStrategy(EXPAND_TIME)` が `timeline_search` 強制ヒントを ReAct に渡す
4. 具体年月日のない汎用日付クエリでは `timeline_search` が空ヒットし、「ファイル名一致 → `read_file` で全文を読む」勝ち筋に入れない

加えて、Parallel Retrieval の 3 本目「元クエリ Grep（FTS4）」は BM25 と検索対象・スコアリングが近く、意味的近傍を拾う手段が欠けていた。

### 決定

3 つを同時に変更する。

#### A. Parallel Retrieval を BM25 + Metadata + Vector に再編

`SearchPipeline.search()` の並行3本柱から Grep を廃止し、Vector（Embedding 類似）に差し替える。

- `RagPipeline.vectorOnlyTopK(question, treeUri, k)` を新規公開メソッドとして追加（既存 private `vectorSearch` + Citation 化 + タイムアウト）
- `SearchPipeline.vectorSearch` は `ragPipeline.vectorOnlyTopK` を呼ぶラッパー
- `Citation.source = SourceType.VECTOR` で投入
- トレースイベントを `GrepSearchHitEvent` → `VectorSearchHitEvent` に差し替え（`GrepSearchHitEvent` は ReAct ループの `grep` ツール用に温存）

#### B. EXPAND_TIME ヒントを read_file 優先に変更

`AgentPipeline.resolveExplorerStrategy()` の `EXPAND_TIME` 分岐で渡す hint を、`timeline_search または metadata_search(date) で…` から以下に変更:

> ファイル本文に日付メタが埋め込まれている可能性が高いです。まず read_file で該当ファイル全文を取得して『初回訪問日』『日付』『date』などのラベル行を確認してください。それでも特定できない場合のみ timeline_search を使ってください。

#### C. metadataSearch の snippet 強化と CoverageCheck の短絡

- `SearchPipeline.metadataSearch` / `dateRangeSearch` の Citation 生成で、`doc.documentDate` が非 NULL の場合に snippet 先頭へ `[日付: YYYY-MM-DD] ` プレフィックスを付与（共通ヘルパ `buildSnippetWithDate`）
- `CoverageChecker.check` の冒頭に短絡ロジックを追加: 日付クエリ正規表現（`いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで`）にマッチし、かつ top5 候補に `[日付:` プレフィックス付き snippet が含まれる場合、LLM を呼ばずに `canAnswer=true` を返す

### 期待効果

- 「サウナしきじ」問題は **C** だけで直る: CoverageCheck が短絡で `canAnswer=true` を返し、Search First の reranked 結果がそのまま回答プロンプトに渡る
- **A** は Recall 向上（ファイル名や本文に直接出てこない概念にもベクトル類似で寄せられる）
- **B** は ReAct フォールバック経路に落ちた際の最終手段として、構造化ノートに対する全文読解を優先させる

### 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `ai/rag/RagPipeline.kt` | `vectorOnlyTopK()` 公開メソッドを追加 |
| `ai/search/SearchPipeline.kt` | `grepSearch` を `vectorSearch` に差し替え、`buildSnippetWithDate` 追加、`metadataSearch` / `dateRangeSearch` の snippet を強化 |
| `ai/agent/CoverageChecker.kt` | 日付クエリ × 日付プレフィックス候補で LLM 呼ばずに短絡 |
| `ai/agent/AgentPipeline.kt` | `resolveExplorerStrategy(EXPAND_TIME)` の hint を `read_file` 優先に書き換え |
| `ai/agent/AgentTraceEvent.kt` | `VectorSearchHitEvent` を追加（`GrepSearchHitEvent` は温存） |
| `ui/screens/ChatScreen.kt` | トレース UI に Vector 行を追加 |

### 理由

- **C（snippet 強化と短絡）** は根本原因への直接対処であり、副作用が小さく効果が確実
- **A（Grep → Vector）** は Parallel Retrieval を「字面マッチ（BM25）/ 構造マッチ（Metadata）/ 意味マッチ（Vector）」の直交三本柱に整理する
- **B（read_file 優先）** はノートの実装スタイル（本文先頭にメタ行）と整合した探索戦略

### トレードオフ

- **Vector 検索のレイテンシ**: 並行ジョブ 1 本が embed + 全件コサイン類似度になり、Grep（FTS4）よりは重い。タイムアウト（`SEARCH_TIMEOUT_MS = 8s`）でガード
- **snippet 強化は documentDate に依存**: 既存 DB の `documentDate` が NULL のままだとプレフィックスが付かず短絡も効かない。再インデックスで `MarkdownMetaExtractor.extractDateFromContent` が補完する想定（NULL のもののみ再抽出）
- **`isDateQuery` 正規表現の二重持ち**: `CoverageChecker` と `LlmReranker` が同じ正規表現を独立に保持する。共通化リファクタは別途

---

## ADR-019: QueryExpander の固有名詞保持 + metadataSearch のファイル名逆引き

**日付:** 2026-06-12
**ステータス:** 採用（ADR-016 の Query Expansion と Metadata Search を強化）

### 背景

ADR-018 までで日付クエリのリグレッションは解消したが、別パターンのリグレッションが残っていた:

「サウナしきじにいつ行ったっけ？」のような **固有名詞 + 助詞 + 疑問詞** からなる質問で、`サウナしきじ.md` がそもそも引用 10 件に出てこない。

二つの欠陥が連鎖していた:

1. **QueryExpander が固有名詞を独立した検索語として保てない**: LLM 出力に `["サウナしきじ"]` 単体が含まれないことがあり、展開クエリのどれも `サウナしきじ` 単体を含まない
2. **`metadataSearch` のトークン分割が日本語助詞を扱えない**: `Regex("[\\s　、。・]+")` で分割するため、「サウナしきじにいつ行ったっけ？」は丸ごと 1 トークンになり、fileName `サウナしきじ.md` との `contains` 比較が成立しない

### 決定

両側から塞ぐ。

#### D1: QueryExpander プロンプトの固有名詞保持ルールを明示

`ai/search/QueryExpander.kt` の `buildPrompt` に必須ルールを追加:

- クエリに含まれる固有名詞（人名・地名・施設名・店名・サービス名・商品名・会社名・略語・カタカナ語の塊）は、助詞・疑問詞を取り除いた「単独の名詞」として 1 件以上含めること
- 元のクエリは丸ごと 1 件として含めること（上記とは別カウント）
- 「サウナしきじにいつ行ったっけ？」を例示に追加し、`["サウナしきじにいつ行ったっけ？","サウナしきじ","しきじ","訪問日","初回訪問日","いつ"]` の形を示す

#### D2: metadataSearch にファイル名逆引きを追加

`ai/search/SearchPipeline.kt` の `metadataSearch` に、既存のトークン一致に加えて以下の OR 条件を追加:

```kotlin
val fileStem = doc.fileName.removeSuffix(".md").removeSuffix(".MD")
val fileNameInQuery = fileStem.length >= MIN_FILENAME_MATCH_CHARS &&
    queries.any { q -> q.contains(fileStem, ignoreCase = true) }
```

`MIN_FILENAME_MATCH_CHARS = 3` でノイズ（短すぎる fileName が偶然 substring 一致するケース）を抑制。

### 期待効果

- **D2 単体で当該問題は直る**: 「サウナしきじにいつ行ったっけ？」の中に fileStem `サウナしきじ` が部分文字列として含まれるため、QueryExpansion の挙動に関係なく metadataSearch がヒットする
- **D1 は別パターンへの汎用効果**: ファイル名そのものが質問に出ていない概念的な質問でも、展開段階で固有名詞が抽出されやすくなる

### 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `ai/search/QueryExpander.kt` | プロンプトに固有名詞保持ルール + サウナしきじ例示を追加 |
| `ai/search/SearchPipeline.kt` | `metadataSearch` にファイル名逆引きの OR 条件、`MIN_FILENAME_MATCH_CHARS` 定数を追加 |

### 理由

- 日本語クエリは助詞で分割しないと意味のあるトークンが取り出せないが、形態素解析を Mini Brain に追加するのは依存・パフォーマンス・モデルファイル増の点でコストが高い
- ファイル名逆引きは「ノートの命名は意味的に重要」というナレッジベース固有の構造を利用しており、形態素解析なしで日本語助詞の壁を回避できる
- LLM 側（QueryExpander）にも固有名詞保持を明示することで、ファイル名逆引きが効かない概念的質問にも備える

### トレードオフ

- **誤マッチの可能性**: fileStem が 3 文字以上あっても、稀に偶然の substring 一致が起きる（例: `AWS.md` と質問「AWS の使い方」）。実害は metadataSearch スコア 0.6 で LlmReranker に渡されるだけなので、reranker が落としてくれることを期待
- **LLM の指示追従性**: QueryExpander のルールを Gemma 4 E2B が守らないケースは依然あるが、D2 のファイル名逆引きがセーフティネットとして機能する
- **メモリ全件スキャン**: `documentDao.getAllByTree` を毎回呼ぶのは ADR-016 から変わらず。数千件規模では問題なし

---

## ADR-020: Embedder を multilingual-e5-small + ONNX Runtime に置換

**日付:** 2026-06-12  
**ステータス:** 部分置き換え（ADR-003 を廃止・置き換え。tokenizer 部分は ADR-021 で置き換え）

### 背景

ADR-003 で採用した MediaPipe TextEmbedder + Universal Sentence Encoder Multilingual (USE) は次の問題があった:

- 文脈理解が浅く、短いクエリ vs 長い文書のマッチング精度が不足
- USE は MTEB / JMTEB 系ベンチで現代的な多言語埋め込みモデルに大きく劣る
- MediaPipe の TextEmbedder API は USE 専用フォーマットに固定されており、他モデルへの差し替えが事実上不可能

### 決定

埋め込みモデルを `intfloat/multilingual-e5-small` の INT8 量子化 ONNX 版に切り替える。
推論ランタイムは MediaPipe TextEmbedder から ONNX Runtime Mobile + HuggingFace Tokenizers (DJL) に置換する。

### 候補との比較

| 案 | 精度（日本語） | サイズ | Android 実装容易性 |
|---|---|---|---|
| Universal Sentence Encoder Multilingual（旧採用） | △ | 280 MB | ◎（MediaPipe TextEmbedder） |
| multilingual-e5-small INT8（**採用**） | ○ | ~118 MB | ○（ONNX 既製版あり） |
| Ruri-v3-30m | ◎（JMTEB トップ級） | ~150 MB | △（公式 ONNX/TFLite なし、自前変換必須） |

### 理由

- Xenova/multilingual-e5-small が INT8 量子化済み ONNX を配布しており、自前変換不要で導入できる
- XLM-RoBERTa 基盤は ONNX Runtime での実績が豊富で運用リスクが低い
- INT8 量子化版は USE より小さく、Gemma 4 E2B (~2.5 GB) と合算しても端末ストレージ圧迫が緩和される
- `ai.djl.huggingface:tokenizers` + `ai.djl.android:tokenizer-native` の組み合わせで `tokenizer.json` を Android arm64-v8a 上で直接読める
- E5 公式 1+1 prefix 規約（`query: ` / `passage: `）を `EmbedType` enum で API レベルに昇格させ、誤用しにくくする

### 実装の要点

- 埋め込み次元: 100 → 384（**既存 chunks/folder_embeddings は破棄必須**）
- DB マイグレーション v5 → v6 で `chunks` / `chunks_fts` / `folder_embeddings` を空にし、`documents.contentHash` を改変して次回 `indexFolder()` 実行時に全文書を再 chunk + 再 embed
- Pooling: attention_mask によるマスク平均 + L2 正規化（E5 公式仕様）
- ダウンロード対象が 1 → 2 に増加（`model_quantized.onnx` + `tokenizer.json`）

### トレードオフ

- APK サイズ +約 25 MB（onnxruntime-android + djl tokenizer ネイティブ libs）
- 既存ユーザーは v5→v6 マイグレーション後に Settings → 再インデックスを手動で実行する必要がある（SAF treeUri が必要なため起動時自動 indexing は不可）
- LiteRT-LM と ONNX Runtime の native lib が共存するため、`packaging.pickFirsts += "**/*.so"` が引き続き必須
- Ruri-v3-30m と比べると JMTEB スコアは劣るが、ONNX エコシステムの安定性を優先

## ADR-021: 16KB ページサイズ対応 — DJL Tokenizer を純 Kotlin 実装に置換

**日付:** 2026-06-13  
**ステータス:** 採用（ADR-020 の tokenizer 部分を置き換え）

### 背景

ADR-020 の実機デバッグで「このアプリは16KBアライメントではありません。ELFのアライメントチェックに失敗しました。」が発生した（Android 16 / 16KB ページサイズ端末）。原因は新規追加した prebuilt ネイティブライブラリ 2 つの ELF LOAD セグメントが 4KB アライメントのままであること:

1. `onnxruntime-android:1.20.0` の `libonnxruntime4j_jni.so`（microsoft/onnxruntime#24902、PR #24947 で修正済み → 1.23.0 以降で解決）
2. `ai.djl.android:tokenizer-native:0.33.0` の `libdjl_tokenizer.so`（deepjavalibrary/djl#3815、**未解決**。しかも 0.33.0 が唯一のリリースでバージョンアップ不可能）

APK の zip アライメントは AGP 8.5.1+ が自動処理するため、問題は prebuilt .so の ELF アライメントのみ。

### 決定

- `onnxruntime-android` を 1.26.0 にアップグレード（ELF 16KB アライメント済み）
- DJL tokenizer（`ai.djl.huggingface:tokenizers` + `ai.djl.android:tokenizer-native`）を削除し、`tokenizer.json` を直接読む**純 Kotlin tokenizer** (`E5Tokenizer`) を自前実装

### 候補との比較

| 案 | 16KB 対応 | 工数 | 備考 |
|---|---|---|---|
| DJL のバージョンアップ | × | — | tokenizer-native は 0.33.0 が唯一のリリース |
| `android:pageSizeCompat="enabled"` 互換モード | △ | 最小 | 4KB 互換モードで動作。根本解決ではない |
| libdjl_tokenizer.so を自前リビルド | ○ | 大 | Rust + NDK ツールチェーンが必要 |
| 純 Kotlin tokenizer 実装（**採用**） | ◎ | 中 | ネイティブ依存を完全排除。JVM ユニットテストも可能に |

### 実装の要点

XLM-RoBERTa tokenizer (SentencePiece Unigram) の HuggingFace `tokenizer.json` パイプラインを Kotlin で再現:

- `PrecompiledCharsMap`: `precompiled_charsmap`（Darts double-array trie）による SentencePiece 正規化。HuggingFace `spm_precompiled` (Rust) の忠実移植。grapheme 単位処理は `java.text.BreakIterator`
- `UnigramModel`: Viterbi によるサブワード分割。未知文字は `min_score - 10` ペナルティで `<unk>`（fuse_unk 対応）
- `E5Tokenizer`: 正規化 → 連続スペース圧縮 → Metaspace（`▁` 置換 + prefix）→ セグメント毎 Viterbi → `<s>`/`</s>` 付与 + 512 トランケート
- `tokenizer.json`（17MB）のロードは Moshi `JsonReader` でストリーミングパース（`org.json` 非依存のため JVM ユニットテスト互換）

### トレードオフ

- HF tokenizers (Rust) との完全一致は理論保証されない（ユニットテストで主要ケースの一致を検証）。トークン列の微差は embedding 類似度にほぼ影響しない
- APK サイズ約 9MB 減（libdjl_tokenizer.so 削除）、Moshi（~250KB）追加
- tokenizer ロード時間は DJL native と同等オーダー（17MB JSON のストリーミングパース）

## ADR-022: SearchPipeline の候補マージを RRF rank 融合に変更

**日付:** 2026-06-13  
**ステータス:** 採用

### 背景

SearchPipeline の Candidate Merge は、ソース別の固定擬似スコア（BM25=0.5 / METADATA=0.6 / 日付ヒット=0.8）とベクトル検索の実コサイン類似度を混ぜて降順 sort していた。この方式には次の問題があった:

- 擬似スコアと実スコアの比較に意味がなく、マージ順がほぼ「ソース種別の固定優先度」で決まる
- 複数ソースに出現する候補（= 信頼度が高い候補）が加点されない
- 定数の根拠が説明できず、チューニングの議論が「0.6 を 0.7 にするか」という不毛な形になる

### 決定

ソース別の rank リストを RRF（Reciprocal Rank Fusion、k=60）で融合する方式に変更（`mergeCandidatesRrf`）。

- score = Σ 1/(k + rank + 1)。複数ソースに出現する候補ほど加点される
- 重複排除キーは従来同様 docId + headingPath。同キーは**最初に出現した Citation を保持**するため、rank リストは meta → vector → bm25 の順で渡す（メタデータの `[日付:]` プレフィックス付き snippet を CoverageChecker 短絡のために優先保持）
- 日付範囲ヒットは meta リストの先頭に置くことで、従来の 0.8 加点と同等の優先度を rank で表現する
- RagPipeline の ReAct 用 RRF（freshnessBoost 付き）はそのまま。変更は SearchPipeline の Candidate Merge のみ

### 付随変更

- `SourceType.BM25` を新設。SearchPipeline の BM25 候補が `RRF` と誤ラベルされていたのを修正（CitationIntegrator 優先度は METADATA と VECTOR の間）
- `DateResolver.resolveDateRange` を `AgentPipeline.run` で一度だけ解決し、`QueryClassifier.classify` / `SearchPipeline.search` / `buildPlannerHint` に引数で共有（同一クエリの三重解決を解消）
- dateRangeSearch の特定日付ヒットにも `[日付:]` プレフィックスを付与（範囲ヒットと挙動を統一し CoverageChecker 短絡を有効化）

### トレードオフ

- 候補の最終順位は LlmReranker が決めるため、マージ方式変更の影響は「上位 50 件に何が残るか」に限定される
- ベクトル検索の実類似度の絶対値情報は捨てられ rank のみ使う（RRF の標準的性質）

## ADR-023: Precision/Recall 向上施策パッケージ

**日付:** 2026-06-13  
**ステータス:** 採用

### 背景

ADR-022 で RRF rank 融合に切り替え、SearchPipeline の基本骨格は安定した。次の改善余地として:

- 元クエリのみのベクトル検索: 字面の言い換えは BM25/Metadata 側だけで吸収していた
- 全ソース均等の RRF: METADATA 完全一致と低類似度の VECTOR ノイズが同じ rank コストになる
- Reranker への投入情報が `[i] headingPath: snippet` のみで、path や文書日付が陽に渡っていない
- 見出し境界の文脈断絶: チャンクが見出し直下に切れているため、見出しまたぎの文意（代名詞・主語）が落ちる
- 評価指標が無い: 改善を主観でしか判断できず、退行も検出できない

### 決定

以下を一括導入する（Recall + Precision の両軸を同時に底上げするパッケージ）。

**Recall 向上**

1. **展開クエリ × Vector** — `SearchPipeline.multiVectorSearch` で元クエリ + 展開クエリ + HyDE 仮想回答を順にベクトル検索し、(docId, headingPath) 重複を除いた rank リストを RRF に渡す。Embedder は Mutex で直列化されているため、N 件のサブクエリは順次実行（≈ 30ms × N）
2. **HyDE（Hypothetical Document Embeddings）** — `HyDE` クラスが LLM で「ありそうな回答」を 1〜2 文生成し、それを `query: ` でなく `passage: ` 近傍として再検索。query↔passage 表現非対称の緩和。タイムアウト 6 秒、失敗時は元クエリのみにフォールバック
3. **オーバーラップ強化** — `MarkdownChunker.OVERLAP_CHARS` を 50 → 120。さらにセクション境界に直前セクション末尾 80 文字を `SECTION_TAIL_CARRY` として付け足し、見出しまたぎの文脈断絶を緩和

**Precision 向上**

4. **VECTOR_MIN_SCORE 閾値** — `SearchPipeline.vectorSearch` でコサイン類似度 0.45 未満を除外。Reranker のノイズ源を断つ。E5（L2 正規化）でこの値以下はほぼ無関係
5. **RRF ソース別重み付け** — `mergeCandidatesRrf(weights=...)` で META=1.5 / VECTOR=1.0 / BM25=1.2 を適用。Metadata 完全一致を優先しつつ、VECTOR を最下位にして低類似度のノイズが上位に来づらくする
6. **Reranker 入力強化** — `LlmReranker` のプロンプトを `[i] path=... heading=... date=... source=... snippet=...` 構造化形式に変更。snippet 上限 100 → 140 文字。`source` を渡すことで「METADATA 完全一致を優先」「VECTOR は意味類似だがノイズあり」を LLM が判断材料にできる

**評価フレーム**

7. **EvalRunner / EvalMetrics** — `app/src/main/kotlin/com/minibrain/eval/` に P@K, R@K, MRR を計測する純 Kotlin 評価フレームを追加。assets の `eval/queries.sample.json` をテンプレとして、ユーザーが「質問→正解 relativePath」を JSON で足すだけで P/R 指標が得られる。docId ではなく relativePath を採用し、再インデックス耐性を持たせる

### 付随変更

- `AgentTraceEvent` に `HyDeGeneratedEvent` を追加し、ChatScreen のトレース表示にも反映
- `MiniBrainApp` の SearchPipeline 生成で HyDE をシングルトンとして注入
- `mergeCandidatesRrf` に `weights: List<Float>?` パラメータを追加（null で従来挙動と互換）

### トレードオフ

- LLM 呼び出しが 1 件増える（QueryExpander → HyDE → Reranker → CoverageCheck）。タイムアウトでフォールバック保証
- インデックスサイズが OVERLAP 増 + SECTION_TAIL_CARRY で約 10〜15% 増（個人ノートのスケールでは無視可）
- 重み・閾値の定数は実評価セットで調整する想定。EvalRunner を使ったオフライン計測がチューニング基盤

## ADR-024: リクエストスコープの SearchRequestCache 導入（Recall/Precision 無影響な処理効率化）

ステータス: 採用

### 背景

`SearchPipeline → RagPipeline` 経路で同一リクエスト内に大きな重複ロード/デコードが残っていた。

- `RagPipeline.vectorSearch` が呼ばれるたびに `chunkDao.getAllByTree(treeUri)` と各チャンクの `bytesToFloatArray()` を再実行する
- `multiVectorSearch` は 元クエリ + 展開クエリ(最大 7) + HyDE(1) で `vectorSearch` を最大 9 回呼ぶ
- `documentDao.getAllByTree(treeUri)` も `SearchPipeline.metadataSearch` / `SearchPipeline.dateRangeSearch` / `AgentPipeline.buildPlannerHint` で重複してロードされる
- `multiVectorSearch` の embed 前重複排除がなく、同一文字列に対する embed が走り得る（EmbedderService は Mutex 直列）

スコアや候補集合に影響を与えずに削れる純粋な計算重複であり、Recall/Precision を一切落とさずに時間短縮できる。

### 決定

1. **リクエストスコープのキャッシュ層 `SearchRequestCache` を新設**（`ai/rag/SearchRequestCache.kt`）
   - `treeUri` をキーに `documents()`, `chunkVectors()`（chunks と decode 済み `Array<FloatArray>` のペア）を lazy + Mutex 付きで提供
   - `cosineTopK(queryVec, k)` を提供して、`CosineSimilarity` をキャッシュ済みベクトルに対して一度に走らせる
   - スコープは 1 リクエスト分のみ。`AgentPipeline.run` の冒頭で生成し、`SearchPipeline.search`、`RagPipeline.retrieveTopChunks` / `vectorOnlyTopK`、`buildPlannerHint` に注入する
2. **`multiVectorSearch` の embed 前重複排除**
   - 元クエリ + 展開クエリ + HyDE 仮想回答を **正規化（trim + 空白圧縮）→ LinkedHashSet で distinct** してから順に embed する
   - 主クエリ枠 `VECTOR_LIMIT` / それ以外 `VECTOR_LIMIT_PER_EXPANDED` の K 値割り当ては従来通り
3. **`RagPipeline` のシグネチャに `cache: SearchRequestCache?` を追加**（デフォルト null）
   - cache != null かつ `cache.treeUri == treeUri` のときだけキャッシュ経路を使う
   - EvalRunner や単独テストなど cache を渡さない呼び出しは従来パスで動作（後方互換）
4. **`SearchPipeline.search` も `cache` を受け取る**。null の場合はリクエスト内で自前生成し、少なくとも 1 リクエスト内の重複排除は保証する

### 影響

- 6000 チャンク・600 ドキュメントの想定で `bytesToFloatArray` × 約 54000 回 → 6000 回、`chunkDao.getAllByTree` × 9 → 1、`documentDao.getAllByTree` × 3〜4 → 1 に圧縮
- スコアリングは「同じ FloatArray に対する同じ CosineSimilarity」となり完全に bit-equal。Recall/Precision は維持

### 既知の未解決領域（別 ADR 候補）

- ReAct ループの `ToolExecutor` は依然として独自に `cachedDocs` / `queryVecCache` を持つ。SearchRequestCache を共有すれば SearchPipeline 経由の結果を再利用できるが、ロジック変更幅が大きく別 PR で扱う（R3 領域）
- `DocumentEntity` への `@Index(["treeUri", "documentDate"])` 追加（R5 領域）も別 PR。マイグレーションが伴うため独立させる
