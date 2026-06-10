# Mini Brain

内部ストレージの Markdown ファイルを知識源として、自然言語で質問するとオンデバイス AI が回答する個人用エージェント型 RAG アプリ。

すべての推論はデバイス上で完結するため、個人情報・機密情報を含むメモも安全に扱える。

## 特徴

- **完全プライベート** — 質問・回答・md の内容はクラウドに送信しない。ネットワーク通信は初回モデルダウンロードのみ
- **ReAct エージェント検索** — LLM が glob / list_dir / read_file / grep / vector_search / rrf_search / timeline_search を自由に組み合わせて多段探索。レイテンシより精度を優先
- **Query Classifier** — 一般知識の質問は RAG をスキップして直接 LLM が回答。検索コストを削減
- **Recentness Ranking** — ファイルパスから日付を抽出し、RRF スコアに指数減衰のフレッシュネス加点を適用（新しい情報を優先）
- **Timeline Search** — 期間表現（去年の夏・2024年3月など）を DateRange に解釈し、期間指定でファイルを収集する `timeline_search` ツールを提供
- **Folder Embedding** — フォルダ単位の仮想埋め込みを生成。ベクトル検索でフォルダ全体の意味をキャプチャ
- **DCI（Directory/Content Intelligence）** — フォルダ・ファイル名を主軸にした検索。日記は日付ファイルを直読み、ノートはパス絞り込みから全文取得
- **日付形式不問** — YYYY/MM/DD・YYYYMMDD・YYYY-MM-DD など命名規則が混在していても DB 側で吸収して一致ファイルの docId を解決
- **ハイブリッド検索** — BM25（FTS4）+ ベクトル検索（USE Multilingual）を RRF でマージ
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
| Embedder     | MediaPipe TextEmbedder + USE Multilingual |
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
3. モデルのダウンロードが完了するまで待機（LLM 約 2.5 GB + Embedder 約 280 MB）
4. ホーム画面で md ファイルが入ったフォルダを選択
5. インデックス作成が完了したらチャット画面で質問

## プロジェクト構成

```
app/src/main/kotlin/com/minibrain/
├── MiniBrainApp.kt          # Application — シングルトン DI
├── MainActivity.kt
├── ai/
│   ├── agent/
│   │   ├── AgentPipeline.kt     # ReAct ループのオーケストレーション・hint 生成
│   │   ├── AgentTypes.kt        # AgentTool / Observation / PlannerDecision 等の型定義
│   │   ├── AgentTraceEvent.kt   # トレースイベント型（PlannerDecision/ToolCall/Observation）
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
│   │   └── EmbedderService.kt   # MediaPipe TextEmbedder ラッパー
│   └── rag/
│       ├── RagPipeline.kt       # RAG（RRF フォールバック・Citation 型定義）
│       └── CosineSimilarity.kt  # コサイン類似度（純 Kotlin）
├── data/
│   ├── db/                      # Room スキーマ・DAO（v5: documents+folder_embeddings）
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

レイテンシより精度を優先した **ReAct ループ + DCI（Directory/Content Intelligence）** を採用。LLM がツールを自由に組み合わせて多段探索し、パス・ファイル名→全文読み込みを基本戦略とする。

```
質問
↓ QueryClassifier
  GENERAL_KNOWLEDGE → RAG スキップ → LLM 直接回答
  TEMPORAL_SUMMARIZATION / MEMORY_SEARCH → 以下へ
↓ buildPlannerHint（DB 先行解析）
  ├─ 期間クエリ: resolveDateRange → DateRange を hint に注入 / timeline_search 推奨
  ├─ 日付クエリ: YYYYMMDD 8桁で DB 検索 → 一致した docId を hint に注入
  │              未一致 → glob hint を列挙
  └─ ファイル名一致: [d=ID] fileName を hint に追加
↓ ReAct ループ（最大 6 回）
  Planner LLM がツールを選択（DSL key:value 形式で出力）:
  ├─ glob(pattern)                    : パターンでファイル列挙
  ├─ list_dir(folder)                 : フォルダ直下のサブフォルダ・ファイル一覧
  ├─ read_file(docId|path)            : ファイル全文取得（巨大ファイルは LLM 要約）
  ├─ grep(query, scope?)              : FTS4 BM25 キーワード検索
  ├─ vector_search(query, k)          : USE Multilingual 意味類似検索
  ├─ rrf_search(query, k)             : BM25 + ベクトル RRF 融合
  └─ timeline_search(start, end, k)   : 期間指定で documentDate フィルタ
  → パース失敗 2 回連続 → RRF 即時フォールバック
↓ CitationIntegrator
  優先度: READ_FILE > GREP > VECTOR > RRF > GLOB > FOLDER
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

| モデル           | 用途             | サイズ    | ライセンス |
| ---------------- | ---------------- | --------- | ---------- |
| Gemma 4 E2B      | テキスト生成     | 約 2.5 GB | Apache 2.0 |
| USE Multilingual | テキスト埋め込み | 約 280 MB | Apache 2.0 |

モデルは初回起動時に `context.filesDir/models/` にダウンロードされる。アプリのアンインストール時に自動削除される。

## プライバシー

- 推論はすべてオンデバイス
- ネットワーク通信は初回モデルダウンロードのみ（Wi-Fi 推奨）
- md の内容・質問・回答はデバイス外に送信しない
- チャット履歴は Room DB（アプリ内部ストレージ）にのみ保存
