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
**ステータス:** 部分置き換え（ADR-028 に拡張）

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
| `grep(query, scope?)` | キーワード全文検索 | FTS4 BM25、scope はフォルダ単位の事後フィルタ（ADR-031） |
| `vector_search(query, scope?, k)` | 意味類似検索 | `EmbedderService.embed` + `CosineSimilarity.topK` |
| `rrf_search(query, k)` | BM25 + ベクトル RRF 融合 | 既存 `RagPipeline.retrieveTopChunks` に委譲 |
| `timeline_search(start, end, k)` | 期間指定で documentDate フィルタ | `SearchRequestCache.documentsInDateRange` → 先頭 chunk に `[日付:]` を付けてスニペットに（ADR-031） |

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

`timeline_search(start, end, limit)` ツールを追加する。`DocumentDao.getByDateRange(treeUri, startDate, endDate)`（ADR-031 で `SearchRequestCache.documentsInDateRange` に置き換え）で `documentDate` カラムを ISO-8601 文字列として範囲検索し、ヒットした文書の先頭チャンクをスニペットとして `Citation` に投入する。

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
- 「決済SDK移行で何をやった？」
- 「オフライン同期の設計を決めたのいつ？」
- 「ソラリスについて教えて」

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

ADR-016 の Search First では「SearchPipeline が 1 件以上 candidates を返した場合は ReAct をスキップ」という単純条件だった。しかし「スパイス堂にはいつ行ったっけ？」のような日付クエリでは、SearchPipeline がカレー関連記事を返しても **訪問日付を含む文書** が上位に入らないケースがあり、正しく回答できなかった。

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

ADR-016 / ADR-017 の Search First 導入後、本文先頭にメタ行（`初回訪問日: 2024/11/03` 等）を埋めるタイプのノートに対する日付クエリ（例: 「スパイス堂にいつ行ったっけ？」）で正答できないリグレッションが発生した。

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

- 「スパイス堂」問題は **C** だけで直る: CoverageCheck が短絡で `canAnswer=true` を返し、Search First の reranked 結果がそのまま回答プロンプトに渡る
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

「スパイス堂にいつ行ったっけ？」のような **固有名詞 + 助詞 + 疑問詞** からなる質問で、`スパイス堂.md` がそもそも引用 10 件に出てこない。

二つの欠陥が連鎖していた:

1. **QueryExpander が固有名詞を独立した検索語として保てない**: LLM 出力に `["スパイス堂"]` 単体が含まれないことがあり、展開クエリのどれも `スパイス堂` 単体を含まない
2. **`metadataSearch` のトークン分割が日本語助詞を扱えない**: `Regex("[\\s　、。・]+")` で分割するため、「スパイス堂にいつ行ったっけ？」は丸ごと 1 トークンになり、fileName `スパイス堂.md` との `contains` 比較が成立しない

### 決定

両側から塞ぐ。

#### D1: QueryExpander プロンプトの固有名詞保持ルールを明示

`ai/search/QueryExpander.kt` の `buildPrompt` に必須ルールを追加:

- クエリに含まれる固有名詞（人名・地名・施設名・店名・サービス名・商品名・会社名・略語・カタカナ語の塊）は、助詞・疑問詞を取り除いた「単独の名詞」として 1 件以上含めること
- 元のクエリは丸ごと 1 件として含めること（上記とは別カウント）
- 「スパイス堂にいつ行ったっけ？」を例示に追加し、`["スパイス堂にいつ行ったっけ？","スパイス堂","スパイス","訪問日","初回訪問日","いつ"]` の形を示す

#### D2: metadataSearch にファイル名逆引きを追加

`ai/search/SearchPipeline.kt` の `metadataSearch` に、既存のトークン一致に加えて以下の OR 条件を追加:

```kotlin
val fileStem = doc.fileName.removeSuffix(".md").removeSuffix(".MD")
val fileNameInQuery = fileStem.length >= MIN_FILENAME_MATCH_CHARS &&
    queries.any { q -> q.contains(fileStem, ignoreCase = true) }
```

`MIN_FILENAME_MATCH_CHARS = 3` でノイズ（短すぎる fileName が偶然 substring 一致するケース）を抑制。

### 期待効果

- **D2 単体で当該問題は直る**: 「スパイス堂にいつ行ったっけ？」の中に fileStem `スパイス堂` が部分文字列として含まれるため、QueryExpansion の挙動に関係なく metadataSearch がヒットする
- **D1 は別パターンへの汎用効果**: ファイル名そのものが質問に出ていない概念的な質問でも、展開段階で固有名詞が抽出されやすくなる

### 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `ai/search/QueryExpander.kt` | プロンプトに固有名詞保持ルール + スパイス堂例示を追加 |
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

- ReAct ループの `ToolExecutor` への `SearchRequestCache` 共有（ADR-027 にて解決・統合済み）
- `DocumentEntity` への `@Index(["treeUri", "documentDate"])` 追加（R5 領域）も別 PR。マイグレーションが伴うため独立させる

## ADR-025: 期間クエリの documentDate Recall と回答 LLM 誘導の強化

ステータス: 採用

### 背景

「去年の冬何してたっけ？」のような期間クエリで、検索段は `[日付: YYYY-MM-DD]` プレフィックス付き候補を Reranker 後段まで運べているにもかかわらず、回答 LLM が「知識ベースには情報が含まれていません」と返してしまう事象が再現した。原因は 2 層に分かれる:

1. **回答プロンプトに dateRange が一切渡されていない** — `buildAnswerPrompt` は `question / citations / history` だけを受け取る設計で、LLM はスニペット先頭の `[日付:]` プレフィックスが「ユーザーの問いに対する根拠」であることを認識できなかった。これが支配的な原因（Search 段は機能しているのに回答が空振りする）。
2. **`extractDateFromPath` が完全日付 (`YYYY-MM-DD` / `YYYY/MM/DD` / `YYYYMMDD`) しか拾わない** — 日記が月単位ファイル (`journal/2024-12.md`) や和暦混在 (`2024年12月.md`) の場合、`documentDate` が NULL のまま登録されて時系列 Recall の上限を下げていた。
3. （副次）dateRangeSearch ヒットが RRF 融合 → 再採点の過程で他ソースの高スコア候補に圧縮されると Reranker 上位 10 から脱落し得る。

### 決定

1. **`buildAnswerPrompt` に dateRange を注入**（`AgentPipeline.kt`）
   - `run` で解決済みの `dateRange` を `buildAnswerPrompt` へ渡し、`dateRange != null` のとき「質問は `<start>` 〜 `<end>` の期間に関するものです。各 snippet 先頭の `[日付:]` プレフィックスを照合し、該当 snippet を最優先で根拠にしてください」という指示を context block 直後に差し込む
   - `citations` に `[日付:]` プレフィックス付きが 1 件もない場合は「該当期間の日記が見つからなかった旨を明記してください」というフェールセーフ文に切り替える（一般知識で誤魔化させない）
2. **`extractDateFromPath` のパターン拡張**（`DocumentRepository.kt`、companion object に移動 + `@VisibleForTesting internal` 化）
   - 完全日付パターンに `YYYY_MM_DD` / `YYYY.MM.DD` / `YYYY年MM月DD日` を追加
   - 月のみパターン `YYYY-MM` / `YYYY年MM月` / `YYYYMM`（6 桁）を追加し、マッチ時は月初 1 日（`YYYY-MM-01`）として登録
   - 完全日付 → 月のみ の順を厳守して `2024-12-15` が `2024-12` に食われないようにする
   - `LocalDate.of` の validity + 年が `1990..今年` の範囲チェックを全パターンに適用（電話番号や ID との誤マッチ防止）
3. **`MarkdownMetaExtractor.extractDateFromContent` の拡張**（`MarkdownMetaExtractor.kt`）
   - YAML frontmatter で許容するラベルに `created` / `published` / `updated` / `日付` / `作成日` / `記録日` を追加（クォート文字 `'` `"` の前置も許容）
   - **見出し** 中の `YYYY年MM月DD日` および `YYYY年MM月`（月のみ → 月初 1 日扱い）を新たに検出
   - ※ 当初は本文全行を走査していたが、「2024年5月に行った」のようなカジュアル言及にも反応してしまい、日記でないノートに誤って `documentDate` が付き、Reranker の競合候補が増えて固有名詞ファイル（`スパイス堂.md` 等）が押し出される regression が発生した。日記ファイルは date を見出し（`# 2024年12月15日` 等）に置く慣例が強いため見出し限定に絞り込んだ。本文中の Western 形式（`YYYY-MM-DD` / `YYYY/MM/DD`）は ADR-025 以前から本文全行を走査しており、今回は触らない
4. **`SearchPipeline.search` で dateRange ヒットを Reranker 後段に pin**（`SearchPipeline.kt`）
   - `dateRangeSearch` の結果を `metaCandidates` 構築用の式から分離して `dateRangeHits` 変数に保持
   - `LlmReranker.rerank` 後、`dateRange != null` かつ `dateRangeHits` が非空なら上位 `DATE_RANGE_PIN_COUNT = 5` 件を結果の先頭に強制マージし、`docId::headingPath` で dedupe して `RERANK_TOP_K` で切る
   - `RRF_WEIGHTS` には触らないため、他クエリの順位は変えない
5. **`dateRangeSearch` のスニペットを doc 先頭 chunk から `DATE_RANGE_SNIPPET_CHARS = 600` 字採る**（`SearchPipeline.kt`、追加対応）
   - 当初は `firstParagraph` (200 字) を使っていたが、ピンに乗るスニペットが薄く LLM が「具体的な活動内容が記載されていません」と返す事象が再現した
   - `ctx.chunkVectors()` から該当 doc の chunks を取り、先頭 chunk のテキストを `[日付:]` プレフィックス付きで返す。chunks が無い場合のみ `firstParagraph` フォールバック
   - 各 doc 1 件の citation を維持しつつスニペット内容を厚くする（5 docs × 600 字 ≈ 3000 字 ≈ 750 tokens で `CitationIntegrator` の 1200 tokens budget に収まる）
6. **`buildAnswerPrompt` の期間クエリ指示文を強化**（`AgentPipeline.kt`、追加対応）
   - 当初の指示文では LLM が「『去年』が具体的にどの年を指すのか不明確」と過剰に逡巡する事象があった
   - 「相対表現は **${dateRange.start} 〜 ${dateRange.end}** の期間に解釈済み。年号の解釈で迷わないこと」を明示し、回答手順を 4 ステップで番号付き列挙にして「snippet 本文に活動内容が書かれていれば素直に紹介し『情報がない』と切り捨てない」を最後に強調

### 影響

- 「去年の冬何してたっけ？」「先月の振り返り」など期間クエリで、`[日付:]` 付き候補が引き出せていれば回答 LLM はそれを根拠として認識する。Search Trace 上では dateRange ヒットが Reranker 結果の先頭 5 件として明示的に観測できる。
- 月単位ファイル名運用 (`journal/2024-12.md`) と和暦混在 (`日記/2024年12月.md`) で `documentDate` が自動補完されるようになるため、評価セット sample-3〜5 の R@10 改善を期待。
- `extractDateFromPath` を `@VisibleForTesting internal` に格上げしたことで、Context / DAO の依存を持たない JVM ユニットテストが可能になり、`ExtractDateFromPathTest` / `MarkdownMetaExtractorTest` を新設できた。

### トレードオフ

- 月のみ抽出を月初 1 日として扱うため、`journal/2024-12.md` 内に「2024-12-25 のメモ」を含むようなケースでは時系列順位がやや粗くなる（月内日次の精度より、月単位ファイルが時系列メタを持つことを優先）。
- `dateRangeSearch` の結果先頭 pin は Reranker の選別を一部上書きするため、Reranker が「この dateRange ヒットは関連性が薄い」と判断したケースでも 5 件は通る。`DATE_RANGE_PIN_COUNT = 5` は CoverageChecker / LlmReranker の `MAX_CANDIDATES` と揃えてあり、悪影響は限定的だが、評価セットで R@10 と P@10 のトレードを継続監視する。

## ADR-026: 固有名詞 +「いつ」クエリの `documentDate` 非依存化（topicMatch ルート）

ステータス: 採用

### 背景

「スパイス堂にいつ行ったっけ？」のように **固有名詞ヒット + 「いつ」** な質問で、対象ファイル（`スパイス堂.md`）が引用にすら出ず、回答も生成できない事象が再現した。スパイス堂.md の本文には「初回訪問日: 2024/11/03」が書かれているのに、それを LLM に届ける前段で詰まっていた。

原因のカスケードは以下:

1. ADR-025 で `MarkdownMetaExtractor` の和暦本文走査を見出し限定に絞ったため、見出しに日付を含まない topic ノート（`# スパイス堂` だけのファイル）は `documentDate = null` になる
2. `LlmReranker` は「いつ」クエリで `date` フィールドを持つ候補を優先する指示を入れるため、`date` 欄が空の topic ノートは上位 10 から押し出される
3. 仮に残っても `CoverageChecker` は `[日付:]` プレフィックスが無い限り LLM に判定を委ねるため、LLM が「no, visit_date」を返して `citations = emptyList()` でリセットされる
4. ReAct フォールバックは Planner がうまく当該ファイルを引いてくる保証が無く、最終的に引用ゼロで回答不能になる

根本問題は **「いつ」クエリ = `documentDate` ありき** という設計仮定。一般公開時に多様な書き方（「初回訪問日: …」「YYYY/MM/DD」「YYYY年MM月DD日」「去年初めて行った」等）をするユーザーを救うには、`documentDate` 抽出をいくら強化しても穴が残る。

### 検討した代替案

- **B案: ディレクトリ選択時に LLM で per-doc メタ抽出**を行い、`documentDate` の Recall を最大化する。実装は素直だが LiteRT-LM 単一スレッド × Gemma E2B では 1 ノートに 5〜15 秒かかり、1000 ノートの初期セットアップで 1.5〜4 時間。一般公開アプリの初回体験として致命的。増分インデックスでも編集 → 再 LLM 抽出に数秒〜十数秒のレイテンシが乗る。**`documentDate` の精度向上で問題を解こうとせず、依存自体を減らす方向の方が筋がいい**と判断して却下。将来パワーユーザー向けのオプトイン機能（Settings → 高度なメタデータ抽出）として残せる余地はある。

### 決定

採用案 A: 固有名詞 / ファイル名一致の候補は「`documentDate` を持たなくても回答に到達できる」ルートを作り、Reranker / CoverageChecker / 回答プロンプトの 3 層で **date 優先フィルタの対象外** にする。

1. **`Citation` に `topicMatch: Boolean = false` フラグを追加**（`RagPipeline.kt`）
   - ファイル名 (stem) がユーザークエリの substring として一致した METADATA ヒットだけが `true`
   - `mergeCandidatesRrf` は `first.getOrPut` で先頭ソースの Citation を保持するため、`metaCandidates` を rankLists の先頭に置く既存挙動と組み合わせて `topicMatch=true` が後段まで運ばれる
2. **`SearchPipeline.metadataSearch` の改修**（`SearchPipeline.kt`）
   - `fileNameInQuery` が成立する doc は `topicMatch = true` を立てる
   - 同時にスニペットを `firstParagraph` (200 字) ではなく **先頭 chunk テキストから `TOPIC_MATCH_SNIPPET_CHARS = 500` 字** に置き換える。「初回訪問日: …」「YYYY/MM/DD」のようなラベル行を回答 LLM に届けるための前準備
   - chunks のロードは `topicMatch` ヒットがある場合だけ lazy で 1 回（`ctx.chunkVectors()` 経由）
3. **`LlmReranker` の date 偏向を緩める**（`LlmReranker.kt`）
   - 候補プロンプトに `topic=match` タグを追加し、「いつ」クエリの指示文を *「date フィールドを持つ候補と topic=match の候補をどちらも上位に残す。topic=match は date 欄が空でも snippet 本文に日付がある可能性が高いので除外しない」* に書き換える
   - これで `date` 欄空の topic ノートが ADR-025 由来の date 優先で押し出される回路を塞ぐ
4. **`CoverageChecker` に `isTopicMatchShortCircuit` を追加**（`CoverageChecker.kt`）
   - 既存の「日付クエリ + `[日付:]` プレフィックス → LLM 短絡 yes」と並列で、「日付クエリ + 上位 5 候補に `topicMatch=true` が 1 件以上 → LLM 短絡 yes」を追加
   - 固有名詞ヒットが top に来ている時点で対象ファイルは確定しており、本文の日付抽出は回答 LLM に任せれば足る。ここで no を返して `citations` をリセットすると ReAct で固有名詞ファイルを取り直す保証が無いため、リセット事故を防ぐ
5. **`AgentPipeline.buildAnswerPrompt` を date クエリ全般に拡張**（`AgentPipeline.kt`）
   - `dateRange != null` の既存ブランチに加え、`dateRange == null` でも `DATE_QUERY_REGEX`（`いつ|何月|何日|何年|年前|月前|去年|先月|先週`）にマッチすれば「日付に関する質問」ブロックを差し込む
   - 共通の「日付を拾う優先順位 3 段」を提示: (1) `[日付: YYYY-MM-DD]` プレフィックス → (2) 本文中の「初回訪問日: …」「訪問日: …」「日付: …」ラベル行 → (3) 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記
   - `dateRange != null` の指示文も同じ優先順位リストを採用するように書き直し、`[日付:]` プレフィックスだけに頼らない構造に変える

### 影響

- 「スパイス堂にいつ行ったっけ」「初回訪問日: …」「YYYY/MM/DD」のような書き方をしているユーザーは、`documentDate` 抽出が成功しなくてもファイル名ヒットで引用に残り、回答 LLM が本文のラベル行から日付を拾える。
- 一般公開時のメタデータ書式依存が大幅に下がる。ユーザー側は普段の Markdown 書き方を変える必要が無く、「ファイル名 = topic」さえ守れば「いつ」系クエリに答えられる。
- ADR-025 で導入した `MarkdownMetaExtractor` の見出し限定制約は維持されるため、純粋な期間クエリ（「去年の夏」など固有名詞なし）の precision は下がらない。

### トレードオフ

- topicMatch ヒットのスニペット長が 200 → 500 字に増えるため、5 件 ピン時の context 量は最大 2500 字（≈ 625 tokens）増。`CitationIntegrator` の `MAX_CONTEXT_TOKENS = 1200` budget には収まるが、他の citation を圧迫する可能性がある。評価セットの P@10 を継続監視する。
- ファイル名 substring 一致は精度が荒い（短い stem や偶然の連続が誤マッチする）。当初は `MIN_FILENAME_MATCH_CHARS = 3` で保険をかけたが、`歯.md` / `AI.md` のような短い stem が検索から漏れるため **後日 1 に引き下げた**（現在は `FileNames.MIN_STEM_MATCH_CHARS = 1`。判定は `FileNames.stemMatchesAnyQuery` に集約）。短い stem の誤マッチは Reranker 側で吸収する方針。
- 「いつ」クエリ + topic match で `documentDate` も `[日付:]` プレフィックスも本文ラベルも一切無いファイルが top に来た場合、回答 LLM は「日付らしき表記が無い」と率直に返すことになる。これは仕様。前は ReAct に落ちて運良くファイルを引けば回答できたが、トータルでは安定度が上がる方向と判断。

## ADR-027: ReAct ツール実行における SearchRequestCache 統合と FTS 同期確認の修正

ステータス: 採用

### 背景

1. **ReAct ツール実行におけるキャッシュの未共有**:
   ADR-024 にてリクエストスコープの `SearchRequestCache` を導入し、`SearchPipeline` / `RagPipeline` で活用していた。しかし、ReAct ループを担当する `ToolExecutor` にはキャッシュが渡されず、イテレーションごとに `vector_search` ツールや `rrf_search` ツールを実行するたびに、全チャンクデータを DB から再ロードし、`embedding` バイト配列から `FloatArray`（384次元）へのデコード（`bytesToFloatArray`）を繰り返し計算していた。オンデバイス RAG では数千チャンクに及ぶ場合があり、これを毎イテレーションでデコードすることは、CPU およびバッテリーの大きな無駄使いと、応答遅延（レイテンシ）を招いていた。

2. **FTS 同期確認処理における不整合判定の甘さ**:
   `DocumentRepository.ensureFtsIndex()` における FTS インデックス再構成の条件が `if (ftsCount >= chunkCount)` となっていた。もし一部のインデックスが破損・欠落しているにもかかわらず総数が上回っている（あるいは不整合がある）場合、正しく同期再インデックス処理が走らず、検索漏れの原因になり得ていた。

### 決定

1. **`ToolExecutor` に `SearchRequestCache` をコンストラクタ引数として追加**
   - [ToolExecutor.kt](file:///Users/mac/git/mini-brain/app/src/main/kotlin/com/minibrain/ai/agent/tools/ToolExecutor.kt) のコンストラクタに `cache: SearchRequestCache` を追加する。
   - `allDocs()` (ドキュメントリスト) を `cache.documents()` を直接呼び出すように簡略化し、DB アクセス数を削減する。

2. **各ツールのキャッシュ活用**
   - **`executeVectorSearch`**:
     - `scope == null` (スコープ制限なし) の場合、`cache.cosineTopK(vec, tool.k)` をそのまま呼び出し、コサイン類似度を高速に取得する。
     - `scope != null` (スコープ制限あり) の場合、`cache.chunkVectors()` から取得した `chunks` と `vectors` を走査し、`relativePath` がスコープに前方一致するチャンクとそのベクトルを抽出して `CosineSimilarity.topK` で計算する。これにより `bytesToFloatArray` のデコード処理を **0回** にする。
   - **`executeRrfSearch`**:
     - `ragPipeline.retrieveTopChunks` に `cache = cache` を引き渡し、RRF 検索時のチャンク取得をキャッシュから行う。

3. **`AgentPipeline` からの `SearchRequestCache` 伝搬**
   - [AgentPipeline.kt](file:///Users/mac/git/mini-brain/app/src/main/kotlin/com/minibrain/ai/agent/AgentPipeline.kt) の `runReActLoop` 内で `ToolExecutor` を生成する際に、リクエスト単位で生成された `cache` インスタンスを渡すように更新する。

4. **FTS インデックス同期確認の厳格化**
   - [DocumentRepository.kt](file:///Users/mac/git/mini-brain/app/src/main/kotlin/com/minibrain/data/repo/DocumentRepository.kt) の `ensureFtsIndex` における判定を `if (ftsCount == chunkCount)` に修正し、件数の一致を以て完全同期と判定するようにする。

### 影響

- ReAct ループが最大イテレーション（6回）回った場合でも、DB 読み込みやデコード処理の再計算が発生しなくなり、オンデバイスでの検索実行時の CPU 負荷、メモリ使用量、およびバッテリー消費が劇的に改善した。
- すべての単体テストが正常にパスすることを確認した。

---

## ADR-028: AppContainer による手動依存性注入の整理

**日付:** 2026-07-19  
**ステータス:** 採用（ADR-005 を拡張）

### 背景

ADR-005 では `MiniBrainApp` クラスにおいて Kotlin の `by lazy` を用いた手動シングルトン管理を採用していた。しかし、アプリの機能拡充に伴い依存するリポジトリやパイプライン、サービス層（`RagPipeline`, `SearchPipeline`, `AgentPipeline`, `CoverageChecker` 等）が増加し、`MiniBrainApp` クラス内に多数の lazy プロパティ定義が並ぶ状態となっていた。
これにより `MiniBrainApp` の責務が過大になり、コードの重複や整理整頓の面で保守性低下が懸念されていた。

### 決定

手動依存性注入コンテナとして `AppContainer` インターフェースおよび `DefaultAppContainer` 実装クラス（[AppContainer.kt](file:///Users/mac/git/mini-brain/app/src/main/kotlin/com/minibrain/di/AppContainer.kt)）を新設する。
- `AppContainer` インターフェースでアプリ全域で必要となる依存プロパティを定義する。
- `DefaultAppContainer` 内で `Context` を受け取り、各依存オブジェクトの `by lazy` 初期化を一括管理する。
- `MiniBrainApp` にて `val container: AppContainer by lazy { DefaultAppContainer(this) }` のみを保持・公開する。
- 各 ViewModel および関連テストは `app.container.documentRepository` や `app.container.agentPipeline` 等を介して依存にアクセスする。

### 理由

- `MiniBrainApp` のコード記述をスリム化し、依存関係の宣言を専用モジュールへ集約できる。
- Hilt / Koin 等の外部 DI フレームワークを導入せず、手動 DI のシンプルさとコンパイル時の安全性を維持できる。
- ユニットテストにおける AppContainer 単位でのモック定義（`every { app.container } returns mockContainer`）が容易になる。

### 影響

- 既存の ViewModel および ViewModelTest の依存取得先が `app.container` に書き換えられた。
- すべてのユニットテストおよび Lint チェックが正常に通過することを確認した。

---

## ADR-029: 差分インデックスでの削除ファイル掃除とバッチ埋め込み

**日付:** 2026-09-29  
**ステータス:** 採用（ADR-024 / ADR-025 を補完）

### 背景

- `DocumentRepository.indexFolder` は「内容が変わったファイル」の chunk / FTS しか消しておらず、フォルダから削除されたファイルの document・chunk・FTS が DB に残り続けていた。検索に古い内容が出続ける原因になっていた。
- `folder_embeddings` は upsert のみで、削除・改名されたフォルダの行が残っていた。`clearFolder` もこのテーブルを消していなかった。
- `ensureFtsIndex` は FTS 件数が chunk 件数と一致しないときに INSERT OR REPLACE で再投入するだけだった。孤立した FTS 行（FTS > chunk）が解消されず、起動のたびに全件を再投入していた。
- チャンクの埋め込みを 1 件ずつ ONNX 推論していたため、初回インデックスが遅かった。
- `SearchPipeline.dateRangeSearch` の特定日付分岐だけスニペットが `firstParagraph`（200 字）のままで、ADR-025 の改善が適用されていなかった。

### 決定

- `indexFolder` は既存 document を `documentDao.getAllByTree` で取得し、フォルダに存在しない `fileUri` の document を FTS → chunks → documents の順で、バッチ単位のトランザクションで削除する（`DocumentDao.deleteByIds` を追加）。
- `folder_embeddings` は `FolderEmbeddingDao.replaceAllByTree`（`@Transaction`）で tree 単位に入れ替える。ただし embed が全件失敗した場合（Embedder 未初期化など）は既存の行を残す。`clearFolder` でも削除する。
- `ensureFtsIndex` は件数が一致しない場合、同じトランザクション内で `DELETE FROM chunks_fts` を実行してから全件を再投入する。
- `EmbedderService.embedAll` を追加する。系列を最長長に合わせて `<pad>`（id=1）で右詰めし、`attention_mask` 付きの mean pooling で 1 回の推論にまとめる。`embed` は `embedAll` の 1 件版とする。インデックス時は `EMBED_BATCH_SIZE = 8` 件ずつ embed し、バッチが失敗したら 1 件ずつに切り替えて、壊れたチャンクだけを捨てる。
- `dateRangeSearch` の期間分岐と特定日付分岐は、同じ `dateHitCitation`（先頭 chunk から `DATE_RANGE_SNIPPET_CHARS = 600` 字）を使う。
- `SearchPipeline` の展開クエリごとの BM25 は、コメントどおり実際に並列実行する。date / meta ジョブは戻り値で受け渡し、外側の `var` への書き込みをなくす。

### 影響

- 既に DB に残っている削除済みファイルは、次回の差分インデックスで自動的に消える。
- 特定日付クエリの引用スニペットが長くなる（最大 600 字 + 日付プレフィックス）。


---

## ADR-030: chunk 取得順の明示とクエリ埋め込みのリクエスト内 memoize

**日付:** 2026-10-02  
**ステータス:** 採用（ADR-024 / ADR-026 を補完）

### 背景

- `SearchRequestCache.firstChunkOf` は「`chunkDao.getAllByTree` の返却順 = ドキュメント先頭から」を前提にしていた。しかしクエリに `ORDER BY` が無く、順序は SQLite の実行計画任せだった。順序が崩れると、ADR-025 / ADR-026 の「先頭 chunk から長めにスニペットを採る」対策が別の chunk を拾ってしまう。
- `RagPipeline.retrieveTopChunks` は、vector 検索と folder 検索で同じクエリを 2 回 embed していた。SearchPipeline で embed 済みの元クエリも、ReAct の `rrf_search` / `vector_search` で再び embed されていた。`ToolExecutor` は独自の `queryVecCache` を持っていたが、ほかの経路とは共有されていなかった。

### 決定

- `ChunkDao.getAllByTree` / `getByScope` / `getByDoc` に `ORDER BY id` を付けて、取得順を保証する。
- `SearchRequestCache.queryEmbedding(text, embed)` を追加し、リクエスト内でクエリ文字列 → 埋め込みを memoize する。`RagPipeline.vectorOnlyTopK` / `retrieveTopChunks` と `ToolExecutor.vector_search` はこれを経由する（`ToolExecutor.queryVecCache` は廃止）。
- `retrieveTopChunks` はクエリを 1 回だけ embed し、vector / folder の両検索で共有する（cache が無い呼び出しでも同様）。

### 影響

- ReAct ループ中の重複 embed が減り、Embedder の Mutex 待ちが短くなる。
- 検索結果の内容は変わらない（順序の保証と計算の重複排除のみ）。

---

## ADR-031: キャンセル伝播・ReAct 観測ウィンドウ・ツールの tree / scope 境界の修正

**日付:** 2026-10-04  
**ステータス:** 採用（ADR-024 / ADR-027 を補完）

### 背景

- `AgentPipeline.addObservation` は「最新 2 件を full、それ以前を compact」とする意図だったが、3 件目以降は**新しい observation を compact で追加**していた。そのため Planner が直前のツール結果の詳細を読めていなかった。
- suspend 文脈の `runCatching` が `CancellationException` も `Result.failure` にしていた。チャットの停止ボタンで「生成エラー / 検索エラー」が表示され、`HyDE` のタイムアウトも失敗として握りつぶされていた。また `ChatViewModel.sendMessage` は途中で例外が出ると `isGenerating` が `true` のまま戻らなかった。
- `read_file(docId=…)` は `DocumentDao.getById` で、現在の tree 以外の文書も読めてしまっていた（path 指定は tree に限定済み）。
- `grep` / `vector_search` の scope が `startsWith(scope)` だったため、`diary` を指定すると `diary2/…` も一致していた。
- `timeline_search` は Citation の source を `GREP` にしていて、日付プレフィックスも付いていなかった。SearchPipeline の期間検索とは別に、DB を直接範囲検索していた。
- `RagPipeline` は `cache == null` / `treeUri == null` 用の DB 直叩きの分岐を持っていた。BM25 の MATCH 式の生成とエラー処理も SearchPipeline と重複していた。

### 決定

- `addObservation` は新しい observation を常に full で追加し、3 件前に押し出されたものだけを compact にする。
- `com.minibrain.util.runCatchingCancellable` を追加する。suspend 文脈ではこれを使い、キャンセル（タイムアウトを含む）は再送出する。
- `ChatViewModel.sendMessage` は `try/finally` で `isGenerating` を戻す。ジョブは LAZY 起動にして `currentJob` を代入してから開始し、キャンセル後に始まった次の送信の状態は触らない。本文が空のまま停止した場合は、空の吹き出しを残さない。
- Citation の JSON 変換は `CitationJson` に移す。否定応答の判定（引用を隠す）は回答冒頭 `NEGATIVE_CHECK_CHARS = 80` 字だけを見る。
- `read_file` の docId 指定は `treeUri` が一致する文書だけを返す。scope は `relativePath == scope` または `startsWith("$scope/")` で判定する。
- `timeline_search` は `SearchRequestCache.documentsInDateRange` を使い、`[日付:]` プレフィックス付き・`SourceType.METADATA` の Citation を返す。SearchPipeline の期間検索も同じ API を使う（`DocumentDao.getByDateRange` は廃止）。
- `RagPipeline` の `treeUri` は必須にする。doc / chunk の参照は常に `SearchRequestCache` 経由とし、cache が無い呼び出しでは呼び出し内だけのキャッシュを作る（`getDocDatesByIds` / `getDocPathsByIds` は廃止）。BM25 は `ChunkDao.bm25SearchOrEmpty` に集約する。

### 影響

- ReAct の 3 ステップ目以降、Planner プロンプトに直前のツール結果が全文で入るようになる（prompt はやや長くなる。上限は従来どおり `formatObservations` の 5000 字）。
- `timeline_search` のヒットが CoverageChecker の `[日付:]` 短絡の対象になる。
- 検索の順位付け（RRF 重み・閾値）は変わらない。

---

## ADR-032: LLM 呼び出しの直列化・クエリ埋め込みのバッチ化・Reranker の省略

**日付:** 2026-10-04  
**ステータス:** 採用（ADR-026 / ADR-030 を補完）

### 背景

- `LlmService.initialize` は既存の `Engine` を close した後に `engine` を null にしていなかった。再初期化が GPU / CPU の両方で失敗すると、close 済みの `Engine` が残って `isReady()` が `true` を返していた。
- `LlmService` の `Mutex` は `initialize` だけを守っており、LiteRT-LM の単一スレッド制約は呼び出し側の規律に頼っていた。生成中の再初期化や close も防げていなかった。
- `litertlm-android` が `latest.release` 指定で、ビルドごとに解決されるバージョンが変わりえた。
- `SearchPipeline.multiVectorSearch` は元クエリ・展開クエリ・HyDE を 1 件ずつ embed していた（最大 10 回の ONNX 推論）。
- 日付クエリ + `topicMatch` 候補があるとき、`CoverageChecker` は LLM を呼ばずに短絡するが、その前段の `LlmReranker` は LLM を呼んでいた。

### 決定

- `LlmService` は `initialize` / `generateStream` / `close` を同じ `Mutex` で直列化する。`engine` は `@Volatile` にし、再初期化の冒頭で close と同時に null にする。`close` は suspend にする。
- `litertlm` のバージョンを `libs.versions.toml` で固定する（0.17.1）。
- `SearchRequestCache.prefetchQueryEmbeddings` / `RagPipeline.prefetchQueryEmbeddings` を追加する。`multiVectorSearch` は検索前に未計算のクエリだけを `embedAll` 1 回で埋め込む。失敗したら握って、従来どおり `queryEmbedding` が 1 件ずつ embed する。
- `SearchPipeline.shouldSkipRerank`: 日付クエリ（`DateResolver.isDateQuery`）かつ融合候補に `topicMatch` があれば `LlmReranker` を呼ばない。topicMatch 候補を先頭に、残りは RRF 順のまま `RERANK_TOP_K` で切る。期間ピン留め（ADR-025）はその後に従来どおり適用する。条件は `CoverageChecker.isTopicMatchShortCircuit` と揃えている。
- CI に `assembleRelease` を追加し、R8 の keep 漏れを検出する。`versionCode` / `versionName` は Gradle プロパティで上書きできるようにする。

### 影響

- `generateStream` の collect 中に別の `generateStream` を呼ぶとデッドロックする（現状そのような呼び出しは無い）。
- 固有名詞 +「いつ」クエリで LLM 呼び出しが 1 回減る（Reranker と CoverageCheck が両方とも省略される）。上位 10 件の残りは Reranker ではなく RRF 順になる。
- その他のクエリの検索結果は変わらない（埋め込みのバッチ化は padding を attention_mask で除外するため、ベクトルは 1 件ずつの場合と数値誤差の範囲で一致する）。


---

## ADR-033: GitHub Releases での APK 配布

**日付:** 2026-10-05  
**ステータス:** 採用

### 背景

- Google Play での公開は予定していないが、ビルド済み APK を配布したい。
- CI の `assembleRelease` は未署名の APK しか作らず、そのままではインストールできない。

### 決定

- `.github/workflows/release.yml` を追加する。`v1.2.3` 形式のタグを push すると、テスト → 署名済み APK のビルド → GitHub Release の作成（APK を添付、リリースノートは自動生成）を行う。`-` を含むタグ（`v1.0.0-beta.1` など）は prerelease にする。
- `versionName` はタグから `v` を除いたもの、`versionCode` は `major * 10000 + minor * 100 + patch` とする。
- 署名鍵は GitHub Secrets（`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`）で渡す。`app/build.gradle.kts` は環境変数 `RELEASE_KEYSTORE_PATH` があるときだけ release の `signingConfig` を設定する。
- モデルファイルは APK に同梱しない（従来どおり初回起動時にダウンロードする）。
- release ビルドは `ndk.abiFilters` で `arm64-v8a` に絞る（全 ABI だと約 166 MB、絞ると約 51 MB）。debug はエミュレータ（x86_64）のため全 ABI を残す。
- `gradle/actions/setup-gradle` は v5 に留める。v6 はキャッシュ部分が別ライセンス（Gradle Terms of Use）になり、使うと同意したことになるため。

### 影響

- 環境変数が無いとき（CI の `ci.yml`・ローカル）の release ビルドは従来どおり未署名。
- 配布 APK は 32bit / x86 端末にはインストールできない（Android 12+ の実機はほぼ arm64）。
- 署名鍵を失うと既存インストールへの上書き更新ができなくなるため、keystore はリポジトリ外にバックアップする。
- minor / patch が 100 以上になると `versionCode` の大小が崩れる。

---

## ADR-034: 起動時はチャットから始め、UI を Material 3 の標準部品に寄せる

**日付:** 2026-10-05  
**ステータス:** 採用

### 背景

- 毎回の起動で Home を経由してからチャットを開く必要があり、普段使い（質問する）までの手数が多かった。
- 再インデックスのボタンが Home と Settings の両方にあった。
- 回答が 85% 幅のカードに収まり、長い Markdown が読みにくかった。回答テキストは全文コピーしかできなかった。
- 引用元は「引用元 (n)」を開かないと見えず、回答の根拠が分かりにくかった。
- Scaffold の padding と `imePadding` を重ねており、キーボード表示時にナビゲーションバー分の隙間が残っていた。

### 決定

- Onboarding の完了時、フォルダ選択済みなら Home を積んだうえで Chat を開く。戻ると Home に出る。
- 再インデックスは Settings に一本化する。Home はフォルダ選択・変更とインデックス状態（完了表示は数秒で消す）、ファイル数だけを出す。チャンク数は利用者向けの指標ではないので出さない。
- 回答は全幅・`SelectionContainer` で表示し、引用元ファイルをチップで常に並べる。チャットのトップバーにはセッション名を出す。
- 履歴はスワイプ削除・日付グループ・タイトル検索、設定は `ListItem` で組む。
- Onboarding の失敗は利用者向けの説明（`Failure.message`）と技術的な詳細（`Failure.detail`、折りたたみ）に分ける。

### 影響

- 起動直後の画面が変わる（フォルダ未選択なら従来どおり Home）。
- Home から再インデックスできなくなる（Settings → 再インデックス）。

---

## ADR-035: 回答待ちの可視化・再生成・Home のハブ化

**日付:** 2026-10-05  
**ステータス:** 採用

### 背景

- ダークモードでも起動時・画面遷移時のウィンドウ背景が白く、一瞬光っていた（`Theme.MiniBrain` の親が Light 固定）。
- ReAct まで進むと回答の本文が届くまで数十秒かかるのに、小さなスピナーと文言だけで止まって見えた。
- LLM が Markdown の表や引用を出すと、`|` や `>` がそのまま並んでいた。
- ローカル LLM は回答が揺らぐのに、同じ質問をやり直す手段が無かった。
- Home はフォルダとファイル数だけで、空状態の質問例は固定文言だった。履歴の一覧はタイトルと時刻だけで見分けにくかった。

### 決定

- `values-night/themes.xml` でダーク時のウィンドウテーマとスプラッシュ背景を暗くする。
- 回答待ちは段階の文言（パイプラインの `onStatus`）を目立つ枠で出し、3 秒を超えたら経過秒数を添える。
- `MarkdownText` に表（区切り行があるときだけ）と引用ブロックを足す。表は列幅・行の高さを揃える独自 `Layout` で組み、横スクロールさせる。
- 最後の回答だけに再生成ボタンを出す。`ChatRepository.removeLastExchange` で最後の質問と回答を消し、同じ質問を送り直す。
- 回答下はコピー・再生成・引用元（「引用元 (n)」＋ファイルチップの横スクロール）を 1 行にまとめる。
- 空のチャットの質問例は、先月（無ければ今月）の文書の有無と最近のファイル名から作る（`buildChatSuggestions`）。作れなければ固定文言。
- Home に最終インデックス日時と最近のチャット（やり取りのあるもの 3 件）を出す。完了時刻はインデックスを Home・Settings のどちらから走らせても記録できるよう、`MiniBrainApp` が `IndexingState.Done` を見て DataStore に書く。別フォルダの記録は出さない。チャンク数は引き続き出さない（ADR-034）。
- `ChatSessionSummary` にメッセージ数と最新の回答を足し、履歴・Home の行（`SessionListItem`）に回答の冒頭と件数を出す。
- 画面遷移に短いスライド＋フェードを入れる。Settings の「開発者」セクションは最下部に移す。
- チャットの本文と入力欄は 720dp を上限に中央寄せする（タブレット・横画面）。

### 影響

- 再生成すると元の回答は履歴から消える（並べて比べる機能は無い）。
- 履歴一覧のクエリがセッションごとに最新回答を相関サブクエリで引く。個人用途の件数なら問題にならない。

---

## ADR-036: インデックス状態・失敗の見せ方とフォルダ変更の統一

**日付:** 2026-10-05  
**ステータス:** 採用

### 背景

- Settings の「再インデックス」を押しても設定画面には何も出ず、連打もできた。
- 起動直後はチャットから始まる（ADR-034）ため、インデックス中であることが Home を開かないと分からなかった。
- チャットの失敗は「生成エラー: <例外文>」の赤字だけで、閉じる・やり直す手段が無かった。
- フォルダ変更で、Settings は前のフォルダのインデックスを確認なしに消し、Home は消さなかった。
- 回答待ちの途中で上にスクロールすると末尾への追従が止まり、戻る手段が無かった。
- 開発者向けの検索ログが既定で表示されていた。

### 決定

- フォルダ変更は `switchKnowledgeFolder` に一本化し、前のフォルダのインデックスを消す（同じフォルダを選び直したときは消さない）。既にフォルダがあるときは確認ダイアログを出してからピッカーを開く。
- Settings は `IndexingState.Progress` の間、再インデックス行に進捗を出し、再インデックスとフォルダ変更を押せなくする。`SettingsViewModel.reindex` もインデックス中は何もしない。
- Chat はインデックス中、トップバーの下に進捗バナーを出す（送信は止めない）。
- チャットの失敗は `ChatError(kind, detail)` にし、エラーカード（見出し・詳細の折りたたみ・再試行・閉じる）で出す。
- 最後のメッセージから離れたら「最新へ」ボタンを出す。質問例をタップしたら入力欄にフォーカスし、カーソルを末尾に置く。
- 検索ログの既定を OFF にする。ユーザー発言のコピーボタンをやめる。Home は 600dp を上限に中央寄せする。アプリ内のロゴをランチャーアイコンの意匠に揃える。

### 影響

- Home からフォルダを変えたときも前のインデックスが消える（従来は残り、DB に孤立していた）。
- 既存利用者でも検索ログの設定を一度も触っていなければ非表示になる。

---

## ADR-037: 検索評価をアプリから実行し、取りこぼしの原因を分ける

**日付:** 2026-10-05  
**ステータス:** 採用

### 背景

- ADR-023 で `EvalRunner` / `EvalMetrics` を入れたが、呼び出し元が無く、評価セットも assets のサンプルだけだった。チューニングの前後を数字で比べられなかった。
- 評価セットは個人ノートのパスを含むので、リポジトリや APK には入れられない。
- Recall@K だけでは、正解が検索で拾えていないのか、拾えたのに Reranker が落としたのかが分からない。

### 決定

- 設定 → 開発者に「検索精度の評価」画面（`EvalScreen` / `EvalViewModel`）を置く。評価セットの JSON は SAF（`OpenDocument`）で選び、永続権限を取って URI を DataStore（`eval_file_uri`）に保存する。
- `SearchPipelineResult.candidates` に RRF 融合後・Reranker 前の候補を載せ、`EvalMetrics` で候補 Recall と `droppedByRerank`（候補にはあったが上位 K 件に残らなかった正解）を出す。
- 実行前に、正解パスがインデックスに無いもの（打ち間違い・移動）を `EvalMetrics.findUnknownPaths` で拾って警告する。
- 結果は `EvalReport.toMarkdown` で Markdown にしてコピーできるようにする。K は 10（`RERANK_TOP_K` と同じ）。

### 影響

- 測るのは `SearchPipeline` まで（CoverageCheck / ReAct は通らない）。
- LLM（展開・HyDE・Reranker）の出力が実行ごとに揺れるため、小さな差は複数回流して確かめる。
- 画面を離れると評価は止まる。実行中は画面を消灯させない。

---

## ADR-038: チャンクの埋め込みに「パス > 見出し」を付ける

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- チャンクの埋め込みは本文（`chunk.text`）だけから作っていた。ファイルの 2 つ目以降のチャンクには、ファイル名や見出しが表す「何の話か」が入らない。
- 例えば `food/スパイス堂.md > 訪問記録` の本文「ビーフカレーが最高だった」は、「スパイス堂の感想」というクエリとベクトルの上で離れてしまう。

### 決定

- `Chunk.embeddingText()` で `"{headingPath から .md を除いたもの}\n{本文}"` を作り、passage の埋め込みに使う。`headingPath` は `MarkdownChunker` が作る「相対パス > 見出し > …」。
- DB の `chunks.text` と FTS（bigram）には付けない。BM25 にファイル名を重ねて足すと、どのチャンクもファイル名で当たってしまうため。スニペット表示も変えない。
- DB を v7 に上げ、`MIGRATION_6_7` で `documents.contentHash` だけを書き換える。chunks は消さないので、再インデックスまでは旧ベクトルのまま検索できる。

### 影響

- 更新後、設定から再インデックスすると全文書が埋め込み直しになる（時間は初回インデックスと同程度）。
- 先頭に文脈を足す分、512 トークンを超えるチャンクでは末尾が少し多く切れる。
- 効果は ADR-037 の評価（特に候補 Recall）で前後を比べて確かめる。

---

## ADR-039: Reranker に渡す候補を減らし、1 件あたりの本文を増やす

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- `LlmReranker` は 30 件の候補を、本文 140 字ずつで判定していた。140 字では見出しの直後しか読めず、関連するかどうかを判断する材料が足りなかった。
- 候補の行には `path=` と `heading=` の両方を出していたが、`headingPath` はもともと「相対パス > 見出し」なので、パスが 2 回入っていた。
- BM25 の候補は `relativePath` を持たず、本文も 200 字で切っていた。そのため、BM25 だけで拾えた正解が ADR-037 の評価で取りこぼし扱いになっていた。

### 決定

- `LlmReranker.CANDIDATE_LIMIT` を 30 → 20、`SNIPPET_MAX_CHARS` を 140 → 300 にする。パスが見出しに含まれるときは `path=` を出さない。プロンプトの総量は従来とほぼ同じ（約 2,500 トークン）。
- LLM が同じインデックスを重ねて返しても、結果に同じ候補を重ねない。
- BM25 の候補に `relativePath` を付け（`SearchRequestCache.documents()` から引く）、スニペットを 300 字にして Reranker の読む長さに揃える。

### 影響

- RRF 21〜30 位の候補は Reranker に渡らなくなる。評価の「絞り込みで落ちた」の件数で影響を確かめる。
- 回答プロンプトに入る BM25 候補の本文が長くなる（`TokenEstimator` の上限で件数は抑えられる）。
- 評価の Recall は、BM25 のパス欠落を直した分だけ以前より高く出ることがある。施策の比較は、このバグ修正を含む同じビルド同士で行う。

---

## ADR-040: BM25 を実際に順位付けし、ベクトルの類似度を見えるようにする

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- `ChunkDao.bm25SearchByTree` は `chunks_fts MATCH ...` の結果を `ORDER BY` なしで `LIMIT` していた。FTS4 には順位付けの関数が無いので、返るのは rowid 順の先頭（≒ DB に早く入ったチャンク）で、関連度とは無関係だった。
- 検索式はクエリの 1 文字（「の」「に」など）も OR でつないでいたため、ほぼ全チャンクがヒットしていた。
- `VECTOR_MIN_SCORE = 0.45` は e5 の類似度の分布（実際は高めに固まる）に対して低すぎる可能性があるが、実データの分布を見る手段が無かった。
- チャンク（最大 800 字前後）が e5 の 512 トークンを超えて末尾が切れているかも未確認だった。

### 決定

- `bm25SearchByTree` は、全ヒットの `matchinfo(chunks_fts, 'pcnalx')` を取って `Bm25Scorer` で Okapi BM25（k1=1.2, b=0.75）を計算し、上位 limit 件を返す。列の重みは本文 1.0、見出し 0.5（見出しには「パス > 見出し」が全チャンクに入るため）。
- 検索クエリは、2 文字以上の語があれば日本語の 1 文字トークンを外す（`NGramTokenizer.toQueryTokens`）。1 文字だけのクエリ（「歯」など）では残す。インデックスは変えない。
- 使っていなかった `ChunkDao.bm25Search`（tree 指定なし）を消す。
- ベクトル検索の類似度の幅を検索ログ（`VectorSearchHitEvent`）に出し、評価レポートに「正解チャンク / 正解以外の最高類似度」を出す。`VECTOR_MIN_SCORE` 自体は、この数字が集まるまで変えない。
- チャンクの長さは実測した結果、変えない（下記）。

### チャンク長の実測

`E5Tokenizer` と実際の tokenizer.json で `MarkdownChunker` の出力（`embeddingText()`）を数えた。

| 文書 | 字/トークン | 512 超え |
|---|---|---|
| ADR.md / README.md / agents.md（技術文） | 約 2.0 | 0 / 286 チャンク（最大 497） |
| 日記風の平文 | 約 1.6 | 812 字で 515 トークン |

平文の最長チャンク（約 900 字）で末尾 70〜80 トークン（約 120 字）が切れる程度で、次のチャンクとの重なり（`OVERLAP_CHARS = 120`）でほぼ補える。チャンクを小さくすると件数と再インデックス時間が増えるため、今は変えない。

### 見送り: 隣接チャンクの追加

回答プロンプトに隣のチャンクも足す案は見送る。知識ベースの枠（`TokenEstimator.MAX_CONTEXT_TOKENS = 1200`）は今でもほぼ使い切っているので、足すと載る引用元が減るだけになる。評価も検索までしか測れず、回答の良し悪しで比べられない。

### 影響

- BM25 の結果が大きく変わる（以前は実質ランダム）。評価の数字も大きく動く可能性がある。
- 1 クエリごとに全ヒットの matchinfo を読む。1 文字トークンを外したのでヒット数は減るが、チャンク数が数万規模になったら見直す。
- 端末の SQLite での動作は未確認。JVM テスト（Robolectric の SQLite）で並び順を確認している。


## ADR-041: フォルダの列挙を DocumentsContract で行い、失敗を黙って捨てない

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- 評価セット（`eval/notes`、85 件）を端末でインデックスすると、2 段目（`tech/Git.md` など）の 64 件は入る一方、3 段目（`tech/Android/R8とProGuard.md` など）の 21 件が全部欠けていた。評価の失敗 24 件はこれが原因で、検索の問題ではなかった。
- `MdFileReader` は `DocumentFile`（`TreeDocumentFile`）で再帰していた。`listFiles()` / `isDirectory` / `isFile` / `length()` は内部の query が失敗しても例外を握りつぶし、空配列や false を返す。さらに、全ファイルの読み込みとサブフォルダの列挙を `async` で一度に投げていた。
- 列挙が失敗すると、そのフォルダのノートは黙って欠ける。`indexFolder` はそれを「フォルダから消えた」と判断し、既存の document まで削除する。

### 決定

- `DocumentsContract.buildChildDocumentsUriUsingTree` に対して、1 フォルダ 1 回の query で ID・名前・MIME・更新日時・サイズを取る。`DocumentFile` では 1 ファイルにつき 4〜5 回 query していた。
- フォルダの列挙は順番に行い、ファイルの読み込みだけ `Semaphore(4)` で並列にする。
- フォルダを列挙できなければ `IOException("フォルダを読み込めませんでした: <パス>")` を投げる。`indexFolder` は既存どおり `IndexingState.Error` にして、document を消さない。個々のファイルの読み込み失敗は、パス付きで警告ログを出してスキップする（従来どおり）。
- ファイル URI は `buildDocumentUriUsingTree(treeUri, documentId)` で作る。`DocumentFile.listFiles()` と同じ形なので、既存の `documents.fileUri` と一致し、全件の再インデックスは起きない。
- provider へのアクセスは `DocumentTree` interface に切り出し、JVM テストでは偽物に差し替える。`androidx.documentfile` 依存は削除する。

### 影響

- 端末では未確認（実機・エミュレータが無い環境で修正した）。原因が `DocumentFile` の握りつぶしではなく端末へのコピー漏れだった場合は、インデックスの数は変わらない。ただし、列挙の失敗は今後エラーとして見えるようになる。

## ADR-042: 特定の日付と「YYYY年の季節」を期間として解決する

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

評価（ADR-041 の修正後）で、日付系の 3 件が Reranker で落ちていた。

- `20260923 のメモ`：8 桁の日付はどのパターンにも当たらず、日付検索が動いていなかった（R=0）。
- `2026年9月8日の日記`：`YEAR_MONTH_RE` が先に当たって 9 月全体の期間になり、9 月の文書が日付順に 5 件固定されていた（RR=0.33）。
- `2025年の夏は何をしてた？`：年 + 季節のパターンが無く、2025 年全体として 1〜3 月の journal が固定されていた（R=0.33）。

### 決定

- `resolveDateRange` は最初に、年まで入った特定の日付（`YYYY-MM-DD` / `YYYY/MM/DD` / `YYYY.MM.DD` / `YYYY年M月D日` / 8 桁 `YYYYMMDD`）を探し、1 日だけの `DateRange` を返す。これで既存の日付ヒット固定（`DATE_RANGE_PIN_COUNT`、ADR-025）が特定日付にも効く。
- 8 桁の注文番号などと取り違えないよう、`DateValidator.parseDay`（実在する日付、年は 1990〜今年）を通ったものだけ採る。
- `YYYY年(の)春/夏/秋/冬` を `seasonRange` で期間にする。年だけのパターンより先に評価する。

### 影響

- 特定日付のクエリは `QueryClassifier` で `TEMPORAL_SUMMARIZATION` になる。分岐で使っているのは `GENERAL_KNOWLEDGE` だけなので、挙動は変わらない。
- `PlannerHintBuilder` は、特定日付でも「期間クエリ検出」の hint（`timeline_search` 推奨）を出す。以前の「検出された日付」とパス一致の hint は出なくなる。
- 回答プロンプトには「◯〜◯（同じ日）の期間に解釈済み」の指示が入る。

## ADR-043: 期間クエリで固定する文書を、日付順ではなく関連度で選ぶ

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- 期間クエリでは、`dateRangeSearch` のヒットの先頭 `DATE_RANGE_PIN_COUNT = 5` 件を Reranker 結果の先頭に固定している（ADR-025）。
- `SearchRequestCache.documentsInDateRange` は `documentDate` の昇順で返すため、固定されるのは「期間の最初の 5 件」だった。
- 「先月の振り返りミーティングで出た改善策は？」では 9 月 1〜15 日の日記が固定され、正解の `work/meetings/2026-09-24 振り返り.md` は 7 位に押し出されていた（RR 0.14）。期間 + 話題のクエリで、期間内の文書が 6 件以上あると同じことが起きる。

### 決定

- 固定する 5 件は、期間内のヒットを次の順で doc 単位に並べ替えて選ぶ（`SearchPipeline.selectDateRangePins`）。
  1. Reranker の結果での順位
  2. RRF 融合後（`merged`）での順位
  3. 元の日付順
- 固定の件数、`RRF_WEIGHTS`、`documentsInDateRange` の並びは変えない。

### 影響

- 「2025年8月にあったこと」のような話題の無い期間クエリでも、Reranker が選んだ文書から固定されるようになる。期間内の文書が 5 件以下なら、全件が固定されるのは変わらない。

## ADR-044: JSON 配列を返させる LLM 呼び出しを `]` で打ち切り、段階ごとの時間を記録する

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- 評価で multi-06「転職活動で受けた会社と結果」だけが、3 回続けて 105〜110 秒かかっていた（他は約 10 秒）。EvalRunner は SearchPipeline だけを測るので、LLM を呼ぶクエリ展開・HyDE・Reranker のどれかが原因になる。
- HyDE は 6 秒のタイムアウトと 280 字の上限がある。一方、`QueryExpander` と `LlmReranker` には、タイムアウトも出力の上限も無かった。どちらも生成を最後まで collect していた。
- JSON 配列を出した後にモデルが解説や同じ語の繰り返しを続けると、`maxNumTokens = 4096` に届くまで止まらない。毎回ほぼ同じ時間なので、同じ出力が上限まで続いていると見ている。どの段階かはログが無く断定できなかった。
- `QueryExpander` は標準の `runCatching` を使っていた（§5.1 違反）。

### 決定

- `LlmService.generateJsonArray(prompt, timeoutMs)` を追加する。`]` が出た時点で collect をやめて生成を打ち切り、時間内に終わらなければ null を返す。
- `QueryExpander` はタイムアウト 15 秒で、null なら元のクエリだけを返す。`runCatchingCancellable` に直す。
- `LlmReranker` はタイムアウト 30 秒（プロンプトが約 2,500 トークンあるため長め）で、null なら RRF の順位のまま返す。
- `SearchPipeline.search` は、展開 / HyDE / 検索 / 絞り込み（候補統合を含む）の所要時間を `SearchTimingEvent` としてトレースに積む。検索ログに 1 行で出し、評価レポートの「ケース別」表の末尾に「内訳」列として出す。

### 影響

- 正常なケースでも、配列の後に続いていた余計な出力の分だけ速くなる。
- 低速な端末（CPU フォールバック）では、タイムアウトに引っかかって展開や Reranker が効かなくなる可能性がある。評価の「内訳」列で 15 秒・30 秒に張り付いていないか確認する。
- 生成途中での打ち切りは、HyDE のタイムアウトと同じくキャンセルで行う。

## ADR-045: 検索評価をエミュレータで自動実行する

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

- 検索の評価は、実機でフォルダを選び、再インデックスし、評価セット JSON を選んで流す手作業だった。施策ごとに「リリース → 実機に入れる → 評価 → 結果を貼る」が必要で、1 回の確認が重い。
- 評価で測るのは SearchPipeline（展開〜Reranker）までで、画面は要らない。必要なのは、フォルダの SAF 権限・モデル・ノート・評価セットだけ。

### 決定

- `scripts/eval-emulator.sh` 1 本で、次を順に行う。
  1. AVD `minibrain-eval`（`system-images;android-35;google_apis;arm64-v8a`、RAM 8GB・6 コア）が動いていなければヘッドレスで起動する。
  2. debug APK と androidTest APK をビルドして入れる。
  3. `eval/models/` のモデル 3 点を、サイズが違うときだけ `run-as` で `filesDir/models` に送る。
  4. `eval/notes/` を `/sdcard/Documents/minibrain-eval` に入れ替えで送り、評価セットを `filesDir/eval/queries.json` に置く。
  5. `EmulatorEvalTest`（androidTest）を `am instrument` で実行し、`filesDir/eval/report.md` を `eval/reports/<日時>-emulator.md` に回収する。
- `EmulatorEvalTest` は、埋め込みと LLM を初期化し、`indexFolder` → `EvalRunner` → `EvalReport` を画面なしで実行する。進み具合は logcat の `EmulatorEval` タグに出す。
- SAF のフォルダ権限は、debug ビルドにだけ入る `GrantFolderActivity` でフォルダ選択を開き、UiAutomator で「このフォルダを使用」「許可」を押して取る。権限はアプリを入れ直しても残るので、操作は初回だけ。
- LLM は既定で CPU を使う。エミュレータには LiteRT-LM が使える GPU が無く、GPU の初期化がネイティブで落ちることもあるため。`EVAL_GPU=true` で GPU を試せる。
- androidTest の依存（`androidx.test:runner`、`uiautomator`）と `testInstrumentationRunner` を追加する。release APK には何も入らない。

### 影響

- 1 回の評価は約 12 分（ビルドとモデル転送を含む。M1 Max・CPU 推論で平均 11.5 秒/件、実機は約 10 秒/件）。所要時間と「内訳」列は目安で、実機の値とは一致しない。極端に遅いケースの検出には使える。
- 初回の実行（v1.2.4 相当のコード）では Recall@10 0.98 / MRR 0.95 で、multi-06 は 12.8 秒（実機 v1.2.3 では 105 秒）。ADR-044 の打ち切りが効き、タイムアウトに張り付いたケースは無かった。
- LLM の出力は GPU と CPU で少し変わるので、Recall / MRR も実機と完全には一致しない。施策の前後比較はエミュレータ同士で行い、リリース前の最終確認は実機で行う。
- モデル（約 2.7GB）・ノート・レポートは git 管理外の `eval/` に置く。

## ADR-046: 列挙の質問で近いフォルダの文書をまとめて拾い、クエリ展開に英訳を足す

**日付:** 2026-10-06  
**ステータス:** 採用

### 背景

エミュレータ評価（ADR-045）で残っていた取りこぼしは 2 件だった。

- multi-01「これまでに行った旅行を全部教えて」（R=0.60）：`travel/` の 5 件のうち、`travel/北海道.md` は Reranker の 10 件から漏れ、`travel/キャンプ/長野 白樺湖.md` は候補にも入っていなかった。列挙の質問では関係する文書が 10 件の枠と競合する。また「旅行」と `travel/` は、ファイル名の一致でも BM25 でもつながらない。
- sem-10「シンガポール出身の同僚について」（R=0）：正解の `people/alex.md` は英語のノート（"From Singapore"）。ベクトルの類似度は 0.81 と低く、BM25 は日本語の語では当たらない。
- フォルダ埋め込み（`folder_embeddings`）は作っていたが、使っているのは ReAct 側の `RagPipeline.retrieveTopChunks` だけで、SearchPipeline では使っていなかった。

### 決定

- 列挙の質問（`全部|全て|すべて|一覧|これまで[にの]`）で `dateRange == null` のとき、SearchPipeline の最後に `pinEnumerationFolder` を行う。
  - `RagPipeline.nearestFolder` で、クエリの埋め込みに最も近いフォルダを 1 つ選ぶ（クエリの埋め込みは `SearchRequestCache` のものを使い回す）。
  - 対象は、そのフォルダの最上位フォルダ（`travel/キャンプ` なら `travel`）。配下の文書が `ENUMERATION_MAX_DOCS = 8` 件を超えるときは選んだフォルダ自身、それも超えるときは何もしない。
  - 対象の文書を全部、結果の先頭に置く。並びは Reranker の順位 → RRF の順位 → パス順。候補に無かった文書は先頭チャンクから Citation を作る（`SourceType.FOLDER`）。最後に `RERANK_TOP_K = 10` で切る。
- `QueryExpander` のプロンプトに、中心になる名詞（国名・地名・職業・人との関係・技術用語など）の英訳を 1〜2 件含めるルールと例を足す。英単語は BM25 で小文字の 1 語になるので、"Singapore" で英語のノートに当たる。例は評価セットに無い題材にした。

### 結果（エミュレータ、前後とも同じ条件）

| 指標 | 変更前 | 変更後 |
|---|---|---|
| Recall@10 | 0.98 | 1.00 |
| MRR | 0.95 | 0.97 |
| multi-01 | R 0.60 / RR 0.25 | R 1.00 / RR 1.00 |
| sem-10 | R 0.00 | R 1.00 / RR 0.33 |

悪化したケースは無い。平均の所要時間は 11.5 秒 → 11.1 秒で、展開が英訳の分だけ長くなる以上の変化は無かった。

### 影響

- 列挙の質問では、近いフォルダの無関係な文書も先頭に入る（例：上位フォルダ `food/` を選ぶと `food/店/` も入る）。Precision は下がるが、列挙では漏れの方が困るので許容する。
- フォルダ選びはフォルダ埋め込み 1 件の類似度だけで決める。似た名前のフォルダ（`travel` と `travel-plan`）を取り違えると、関係の無い文書が先頭に入る。評価で見続ける。
- 英訳の分、展開されるクエリが増え、BM25・埋め込みの回数が少し増える。
