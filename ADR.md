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
**ステータス:** 採用

### 背景

単純なベクトル検索（+ BM25 ハイブリッド）では、フォルダ構造・ファイル種別・日付を理解した探索が必要なクエリに対応できなかった。

具体的な未解決クエリ:
- 「昨日何していた？」→ 日記フォルダの日付ファイルを直接参照すべきだが、意味的に近いチャンクが返らない
- 「Datadogについて何か書いていた？」→ "Datadog.md" を直接開くべきだが、ベクトル空間での近傍探索になってしまう

### 決定

`RagPipeline` を補助扱いとし、新たに `AgentPipeline` を中心に据えたエージェント型検索に移行する。

検索フロー:
```
質問
↓ planSearch（クライアントサイド判定 or LLM）
SearchPlan（intent + targetFolders + targetFiles + searchKeywords）
↓ executeSearch（intent に応じた戦略）
Citations
↓ answer（intent を意識したプロンプト）
回答
```

### intent と検索戦略

| intent | トリガー | 検索方法 |
|---|---|---|
| `diary_lookup` | 「昨日」「先週」等のキーワード（クライアント判定） | 日付文字列でファイル名検索 → チャンク取得 |
| `file_lookup` | ファイル名が質問に含まれる（クライアント判定） | ファイル名一致でチャンク取得 |
| `topic_research` | LLM が判定 | BM25 + ベクトル検索（対象フォルダで絞り込み） |
| `general` | LLM が判定 or フォールバック | 既存 RagPipeline に委譲 |

### ファイルメタデータインデックス

インデックス時に各ファイルから以下をルールベースで抽出し `documents` テーブルに保存する:
- `headings`: 見出し一覧（JSON 配列）
- `first_para`: 先頭段落（200 文字上限）
- `tags`: Obsidian 形式タグ（JSON 配列）

LLM 生成サマリは採用しない（インデックス速度とオフライン動作を優先）。

### 実装ファイル

| ファイル | 役割 |
|---|---|
| `ai/agent/AgentPipeline.kt` | planSearch / executeSearch / answer のオーケストレーション |
| `ai/agent/SearchPlan.kt` | 検索計画データクラス |
| `ai/agent/DateResolver.kt` | 相対日付（昨日・先週等）→ 絶対日付変換 |
| `data/md/MarkdownMetaExtractor.kt` | 見出し・本文・タグのルールベース抽出 |

### 理由

- 日付・ファイル名による fast-path（LLM 不要）を優先し、モバイルの応答速度を維持
- LLM による検索計画は汎用クエリにのみ使用し、二重 LLM 呼び出しを最小化
- 既存の RagPipeline を残し `general` フォールバックとして再利用することで後方互換性を確保

### トレードオフ

- `topic_research` / `general` では LLM 呼び出しが 2 回（計画 + 回答）になり、応答時間が増加する
- Gemma 4 E2B（2B パラメータ）の JSON 出力は不安定な場合があるため、パース失敗時の `general` フォールバックが必須
- ファイルメタデータは次回の差分インデックス時まで NULL のまま（既存ドキュメントは再インデックスが必要）
