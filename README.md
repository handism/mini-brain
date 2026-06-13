# Mini Brain

内部ストレージの Markdown ファイルを知識源として、自然言語で質問するとオンデバイス AI が回答する個人用エージェント型 RAG アプリ。

すべての推論はデバイス上で完結するため、個人情報・機密情報を含むメモも安全に扱える。

## 特徴

- **完全プライベート** — 質問・回答・md の内容はクラウドに送信しない。ネットワーク通信は初回モデルダウンロードのみ
- **Search First → Coverage Check → Agent** — Query Expansion で検索語を展開し BM25 / Metadata / Vector を並行検索、LLM Reranker で上位候補を選択。さらに Coverage Check で「証拠が揃っているか」を評価し、不足している場合は Explorer Strategy を決定して ReAct にエスカレーション
- **Query Expansion** — LLM が元クエリを 3〜8 件の検索語群に展開。固有名詞（人名・地名・施設名・サービス名等）は助詞・疑問詞を取り除いた単独名詞として必ず保持し、日付表現・関連語も補完
- **HyDE（Hypothetical Document Embeddings）** — LLM がクエリへの「ありそうな回答」を 1〜2 文生成し、その埋め込みでベクトル検索を追加実行。query↔passage の表現非対称を緩和して Recall を底上げ
- **Metadata Search** — ファイル名・フォルダパス・タグ・documentDate を直接検索。本文にない情報（日付フォルダ・会社名）も Recall に貢献。ファイル名(拡張子除く)が質問に部分文字列として含まれる場合の逆引きも実施し、日本語助詞でトークン化されないクエリでも固有名詞ファイルを確実に拾う。snippet 先頭に `[日付: YYYY-MM-DD]` を埋め込み、日付クエリでの回答可能性判定を高速化
- **Multi-Vector Search** — 元クエリ + 展開クエリ + HyDE 仮想回答で順次ベクトル検索し、コサイン類似度 0.45 未満はノイズとして除外。字面が一致しない言い換えや概念質問を取り逃さない
- **重み付き RRF** — Metadata=1.5 / BM25=1.2 / Vector=1.0 の重みで rank 融合。完全一致のメタデータを優先しつつ、低類似度のベクトルノイズを上位に来づらくする
- **LLM Reranker** — 50 件の候補を LLM が関連度順に再採点し、上位 10 件を回答コンテキストに投入。`path / heading / date / source / snippet` の構造化形式で投入し、日付クエリでは date フィールドを持つ候補を優先
- **Coverage Check** — Search First 後に LLM が「回答に必要な証拠が揃っているか」を評価。不足情報（`visit_date` 等）を特定し、Explorer Strategy（EXPAND_TIME / EXPAND_TOPIC）を決定して ReAct に指示を渡す。日付クエリ かつ 日付プレフィックス付き候補がある場合は LLM 呼び出しを短絡
- **ReAct エージェント（Fallback）** — Search First + Coverage Check で証拠が揃わなかった場合のフォールバック。Explorer Strategy の hint を参照し、glob / list_dir / read_file / grep / vector_search / rrf_search / timeline_search を自由に組み合わせて多段探索。EXPAND_TIME は read_file での全文読解を優先
- **Query Classifier** — 一般知識の質問は RAG をスキップして直接 LLM が回答。検索コストを削減
- **Recentness Ranking** — ファイルパスから日付を抽出し、RRF スコアに指数減衰のフレッシュネス加点を適用（新しい情報を優先）
- **Timeline Search** — 期間表現（去年の夏・2024年3月など）を DateRange に解釈し、期間指定でファイルを収集する `timeline_search` ツールを提供
- **Folder Embedding** — フォルダ単位の仮想埋め込みを生成。ベクトル検索でフォルダ全体の意味をキャプチャ
- **日付形式不問** — YYYY/MM/DD・YYYYMMDD・YYYY-MM-DD など命名規則が混在していても DB 側で吸収して一致ファイルの docId を解決
- **ハイブリッド検索** — BM25（FTS4）+ ベクトル検索（multilingual-e5-small INT8 / 384 次元）を RRF でマージ
- **Markdown 対応** — 見出し階層を解析してチャンク分割。引用元のファイル名と見出しパスを回答に表示
- **ストリーミング回答** — LiteRT-LM のトークンストリームを Compose UI にリアルタイム描画
- **差分インデックス** — SHA-256 ハッシュで変更ファイルだけ再インデックス

## スタック

| カテゴリ     | 技術                                      |
| ------------ | ----------------------------------------- |
| 言語         | Kotlin                                    |
| UI           | Jetpack Compose + Material 3              |
| 最小 SDK     | API 31 (Android 12)                       |
| LLM          | Gemma 4 E2B via LiteRT-LM                 |
| Embedder     | multilingual-e5-small (INT8 ONNX) via ONNX Runtime + 純 Kotlin tokenizer |
| DB           | Room（FTS4 + ベクトル）                   |
| 設定保存     | DataStore                                 |
| ファイル選択 | Storage Access Framework                  |

## セットアップ

### 必要環境

- Android Studio Narwhal (2025.1.1) 以降（AGP 9.x が必要）
- JDK 17
- Android 12 以上の実機（RAM 6 GB 以上、空き容量 4 GB 以上推奨）

### ビルド手順

```bash
# 1. Android Studio でプロジェクトを開く
#    File > Open > /path/to/mini-brain

# 2. Gradleをインストールしてwrapperを生成
brew install gradle
gradle wrapper --gradle-version 9.5.1

# 3. Gradle Sync が完了したらビルド
./gradlew assembleDebug

# 4. 実機にインストール
#    実機を開発者向けオプション有効化し、USBデバッグを有効化してUSBで接続
./gradlew installDebug
```

### 初回起動

1. Wi-Fi に接続した状態でアプリを起動
2. Onboarding 画面で「ダウンロード開始」をタップ
3. モデルのダウンロードが完了するまで待機（LLM 約 2.5 GB + Embedder 約 118 MB + Tokenizer 約 17 MB）
4. ホーム画面で md ファイルが入ったフォルダを選択
5. インデックス作成が完了したらチャット画面で質問

## プロジェクト構成

```
app/src/main/kotlin/com/minibrain/
├── MiniBrainApp.kt          # Application — シングルトン DI
├── MainActivity.kt
├── ai/
│   ├── search/                  # Search First パイプライン（新規）
│   │   ├── SearchPipeline.kt    # QueryExpansion→HyDE→並行検索→重み付き RRF→Rerank のオーケストレーション
│   │   ├── QueryExpander.kt     # LLM によるクエリ展開（3〜8 件・JSON 配列出力）
│   │   ├── HyDE.kt              # LLM による仮想回答生成（query↔passage 非対称の緩和）
│   │   └── LlmReranker.kt       # LLM による候補再採点（path/heading/date/source/snippet 構造化）
│   ├── agent/
│   │   ├── AgentPipeline.kt     # Search First + CoverageCheck + ReAct フォールバック統合
│   │   ├── AgentTypes.kt        # AgentTool / Observation / PlannerDecision 等の型定義
│   │   ├── AgentTraceEvent.kt   # トレースイベント型（Search First + CoverageCheck + ReAct）
│   │   ├── CoverageChecker.kt   # 回答可能性評価（LLM）+ Explorer Strategy 決定
│   │   ├── PlannerPrompt.kt     # Planner プロンプト生成 + DSL(key:value)パーサ
│   │   ├── CitationIntegrator.kt# 重複除去・優先度整列・トークン budget 制御
│   │   ├── DateResolver.kt      # 相対日付・期間表現 → 絶対日付/DateRange 変換
│   │   ├── QueryClassifier.kt   # クエリ種別判定（MEMORY/GENERAL/TEMPORAL）
│   │   └── tools/
│   │       ├── ToolExecutor.kt  # 7 ツールの実装（glob/list_dir/read_file/grep/vector/rrf/timeline）
│   │       └── GlobMatcher.kt   # glob パターン → Kotlin Regex 変換
│   ├── llm/
│   │   ├── LlmService.kt        # LiteRT-LM Engine ラッパー
│   │   └── ModelDownloader.kt   # レジューム対応ダウンロード
│   ├── embed/
│   │   └── EmbedderService.kt   # ONNX Runtime + multilingual-e5-small ラッパー（EmbedType で query/passage 切替）
│   └── rag/
│       ├── RagPipeline.kt       # RAG（RRF・Citation 型・SourceType 定義）
│       └── CosineSimilarity.kt  # コサイン類似度（純 Kotlin）
├── eval/                        # 検索評価フレーム（P@K / R@K / MRR）
│   ├── EvalCase.kt              # 評価ケース型（質問 + 期待 relativePath）
│   ├── EvalMetrics.kt           # P@K / R@K / MRR 計算（純 Kotlin）
│   └── EvalRunner.kt            # 評価セットを SearchPipeline に流して指標化
├── data/
│   ├── db/                      # Room スキーマ・DAO（v6: E5 Embedder への切替で chunks/folder_embeddings をリセット）
│   ├── md/
│   │   ├── MarkdownChunker.kt      # 見出しベースのチャンク分割
│   │   ├── MarkdownMetaExtractor.kt # 見出し・本文・タグ抽出
│   │   └── MdFileReader.kt         # SAF 経由の md 列挙・読み込み
│   └── repo/                    # リポジトリ層
└── ui/
    ├── nav/AppNav.kt
    ├── screens/                 # Onboarding / Home / Chat / Settings
    ├── vm/                      # ViewModel 群
    └── theme/                   # Material 3 テーマ
```

## 検索アーキテクチャ

**Recall 最大化** を目的とした **Search First → Coverage Check → Agent** を採用。候補収集を並行多手法で行い、LLM は選別（Rerank）・回答可能性評価（Coverage Check）・回答生成に専念させる。

```
質問
↓ QueryClassifier
  GENERAL_KNOWLEDGE → RAG スキップ → LLM 直接回答
  TEMPORAL_SUMMARIZATION / MEMORY_SEARCH → 以下へ

↓ SearchPipeline（Search First）
  1. QueryExpander（LLM）: クエリを 3〜8 件に展開
  1.5 HyDE（LLM）: 仮想回答を 1〜2 文生成（タイムアウト 6 秒、失敗時はスキップ）
  2. Parallel Retrieval:
     ├─ 展開クエリ × BM25（FTS4）
     ├─ 展開クエリ × Metadata Search（fileName / path / tags / documentDate）
     │                              + fileName 逆引き（fileName が query の substring）
     │                              snippet 先頭に `[日付: YYYY-MM-DD]` を埋め込む
     └─ Multi-Vector（元クエリ + 展開クエリ + HyDE 仮想回答）
                                    コサイン類似度 < 0.45 は閾値カット
  3. Candidate Merge: 重み付き RRF（k=60、META=1.5 / BM25=1.2 / VECTOR=1.0）で重複排除 → 上位 50 件
  4. LlmReranker（LLM）: 上位 10 件に絞り込み
     ※ path / heading / date / source / snippet の構造化形式で投入
     ※ 日付クエリは date フィールドを持つ候補を優先

↓ CoverageCheck（LLM）
  ※ 日付クエリ かつ snippet 先頭が `[日付:` で始まる候補があれば LLM を呼ばずに即 canAnswer=true で短絡
  canAnswer=true  → 回答生成へ
  canAnswer=false → ExplorerStrategy を決定して ReAct ループへ
    EXPAND_TIME  : visit/date/time が不足 → read_file で全文を読んで日付メタを確認（timeline_search は最終手段）
    EXPAND_TOPIC : その他の情報が不足    → read_file/grep を hint に追加

↓ 空 OR CoverageCheck 失敗の場合 → ReAct ループ（Fallback）
  Planner LLM がツールを選択（DSL key:value 形式）:
  ├─ glob / list_dir / read_file / grep / vector_search / rrf_search / timeline_search
  → パース失敗 2 回連続 → RRF 即時フォールバック

↓ CitationIntegrator
  優先度: READ_FILE > GREP > METADATA > BM25 > VECTOR > RRF > GLOB > FOLDER
  重複除去（docId + headingPath キー）・トークン budget（chars/3 推定、上限 1200 tokens）
  citations 空 → RRF 強制フォールバック（セーフティネット）
↓ LLM 回答生成（ストリーミング）
```

### Recentness Ranking

RRF スコアに指数減衰の freshnessBoost を加算。ファイルパスから YYYY-MM-DD / YYYYMMDD / YYYY/MM/DD 形式で日付を抽出し DB に保存。

```
finalScore = rrfScore + FRESHNESS_BOOST_MAX × exp(−daysSince / FRESHNESS_DECAY_DAYS)
// FRESHNESS_BOOST_MAX = 0.010, FRESHNESS_DECAY_DAYS = 90
// 30日: +0.0072, 1年: +0.0017, 3年: +0.0001
```

### 評価フレーム

`app/src/main/kotlin/com/minibrain/eval/` に Precision@K / Recall@K / MRR を計測する評価フレームを同梱。`assets/eval/queries.sample.json` をテンプレに「質問 → 正解ファイル」セットを記述し、`EvalRunner.run(treeUri, cases, k)` で検索パイプラインを走らせて指標を取得する。VECTOR_MIN_SCORE や RRF 重みなどのチューニングはこのフレームでオフライン計測しながら詰める想定。

```kotlin
val cases = EvalRunner.loadFromAssets(context, "eval/queries.sample.json")
val result = EvalRunner(searchPipeline).run(treeUri, cases, k = 10)
println("P@10=${result.precisionAtK}  R@10=${result.recallAtK}  MRR=${result.mrr}")
```

### Folder Embedding

インデックス時にフォルダ直下のファイル名・見出しを連結して埋め込みを生成し `folder_embeddings` テーブルに保存。ベクトル検索でフォルダ単位の意味マッチを実現。

## 画面構成

| 画面       | 役割                                                       |
| ---------- | ---------------------------------------------------------- |
| Onboarding | 初回モデルダウンロード・進捗表示                           |
| Home       | フォルダ選択・インデックス状態・統計                       |
| Chat       | Q&A（ストリーミング）・検索ステータス・引用元の折りたたみ表示 |
| Settings   | フォルダ変更・再インデックス・チャット履歴削除・モデル情報 |

## モデル情報

| モデル                                | 用途             | サイズ    | ライセンス |
| ------------------------------------- | ---------------- | --------- | ---------- |
| Gemma 4 E2B                           | テキスト生成     | 約 2.5 GB | Apache 2.0 |
| multilingual-e5-small (INT8 ONNX)     | テキスト埋め込み | 約 118 MB | MIT        |
| XLM-RoBERTa SentencePiece (tokenizer) | トークナイズ     | 約 17 MB  | MIT        |

モデルは初回起動時に `context.filesDir/models/` にダウンロードされる（Xenova/multilingual-e5-small から取得）。アプリのアンインストール時に自動削除される。

## プライバシー

- 推論はすべてオンデバイス
- ネットワーク通信は初回モデルダウンロードのみ（Wi-Fi 推奨）
- md の内容・質問・回答はデバイス外に送信しない
- チャット履歴は Room DB（アプリ内部ストレージ）にのみ保存
