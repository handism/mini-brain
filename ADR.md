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
**ステータス:** 採用

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
