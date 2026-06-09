# Mini Brain

内部ストレージの Markdown ファイルを知識源として、自然言語で質問するとオンデバイス AI が回答する個人用エージェント型 RAG アプリ。

すべての推論はデバイス上で完結するため、個人情報・機密情報を含むメモも安全に扱える。

## 特徴

- **完全プライベート** — 質問・回答・md の内容はクラウドに送信しない。ネットワーク通信は初回モデルダウンロードのみ
- **エージェント型検索** — LLM が Vault 全体の構造を俯瞰して検索計画を立て、意図に応じた戦略でファイルを取得
- **日付・ファイル名検索** — 「昨日何していた？」「Datadogについて何か書いていた？」などを高精度で回答
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
│   │   ├── AgentPipeline.kt     # エージェント型検索のオーケストレーション
│   │   ├── SearchPlan.kt        # 検索計画データクラス
│   │   └── DateResolver.kt      # 相対日付（昨日・先週等）→ 絶対日付変換
│   ├── llm/
│   │   ├── LlmService.kt        # LiteRT-LM Engine ラッパー
│   │   └── ModelDownloader.kt   # レジューム対応ダウンロード
│   ├── embed/
│   │   └── EmbedderService.kt   # MediaPipe TextEmbedder ラッパー
│   └── rag/
│       ├── RagPipeline.kt       # RAG（general フォールバック）
│       └── CosineSimilarity.kt  # コサイン類似度（純 Kotlin）
├── data/
│   ├── db/                      # Room スキーマ・DAO
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

```
質問
↓
planSearch（AgentPipeline）
  ├─ 日付キーワード検出（昨日/先週等）→ diary_lookup  ←クライアント判定（高速）
  ├─ ファイル名一致検出              → file_lookup   ←クライアント判定（高速）
  └─ その他                          → LLM が計画生成 → topic_research / general
↓
executeSearch（intent 別の戦略）
  ├─ diary_lookup   : 日付文字列でファイル名検索 → チャンク取得
  ├─ file_lookup    : ファイル名一致でチャンク取得
  ├─ topic_research : BM25 + ベクトル検索（対象フォルダで絞り込み）
  └─ general        : RagPipeline.retrieveTopChunks()（BM25 + ベクトル + RRF）
↓
answer（LLM による回答生成）
```

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
