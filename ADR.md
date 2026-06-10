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
↓ buildPlannerHint（DB 先行解析）
  ├─ 日付クエリ: YYYYMMDD 8桁で DB 検索 → docId を hint に直接注入
  │              未一致なら YYYYMMDD* / YYYY/MM/DD* / YYYY-MM-DD* の glob パターンを hint に列挙
  └─ ファイル名一致: fileName を hint に追加
↓ ReAct ループ（最大 6 回）
  Planner LLM → {"tool":"..","args":{..}} or {"action":"finalize"}
  → ToolExecutor 実行 → Observation 追加
  → JSON パース失敗 2 回連続 → RRF 即時フォールバック
↓ CitationIntegrator（重複除去・優先度整列・4000 字 budget）
  citations 空 → RRF 強制フォールバック（セーフティネット）
↓ buildAnswerPrompt → LLM 回答生成
```

### ツール一覧

| ツール | 用途 | 実装 |
|---|---|---|
| `glob(pattern)` | パターンでファイル列挙（`**` 再帰対応） | DB 全件 + `GlobMatcher.globToRegex` フィルタ |
| `list_dir(folder)` | フォルダ直下のサブフォルダ・ファイル一覧 | prefix フィルタ |
| `read_file(docId\|path)` | ファイル全文取得（chunks 連結） | `ChunkDao.getByDoc` → headingPath 順に連結、8000 字上限 |
| `grep(query, scope?)` | キーワード全文検索 | FTS4 BM25、scope は事後フィルタ |
| `vector_search(query, scope?, k)` | 意味類似検索 | `EmbedderService.embed` + `CosineSimilarity.topK` |
| `rrf_search(query, k)` | BM25 + ベクトル RRF 融合 | 既存 `RagPipeline.retrieveTopChunks` に委譲 |

### Citation 優先度

`READ_FILE > GREP > VECTOR > RRF > GLOB`（`CitationIntegrator` で dedup・整列）

### 新規ファイル

| ファイル | 役割 |
|---|---|
| `ai/agent/AgentTypes.kt` | `AgentTool` / `ToolCall` / `ToolResult` / `Observation` / `PlannerDecision` / `AgentResult` |
| `ai/agent/PlannerPrompt.kt` | Planner プロンプト生成 + pure Kotlin regex JSON パーサ |
| `ai/agent/CitationIntegrator.kt` | 重複除去・優先度整列・budget 制御（純関数） |
| `ai/agent/tools/ToolExecutor.kt` | 6 ツールの実装 |
| `ai/agent/tools/GlobMatcher.kt` | glob → Regex 変換（`**`→`.*` / `*`→`[^/]*` / `?`→`[^/]`） |

### 削除ファイル

- `ai/agent/SearchPlan.kt` — plannerHint 文字列に役割移譲

### 設計のポイント

**pure Kotlin regex JSON パーサ**  
`org.json.JSONObject` は Android stub であり JVM ユニットテストで機能しない。`PlannerPrompt.parseDecision` は regex のみで `tool` / `action` / `args` を抽出する。

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
