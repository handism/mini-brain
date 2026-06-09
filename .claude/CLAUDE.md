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

### MediaPipe TextEmbedder（Embedder）

- 依存: `com.google.mediapipe:tasks-text`（こちらは現役）
- モデル: Universal Sentence Encoder Multilingual（日本語対応）
- API:
  ```kotlin
  val result = textEmbedder.embed(text)
  val floatList = result.embeddingResult().embeddings().first().floatEmbedding()
  ```

### Room DB

- `FloatArray`（ベクトル）は `ByteArray` として保存
- 変換: `EmbedderService.floatArrayToBytes()` / `bytesToFloatArray()`
- コサイン類似度は全件メモリロードで計算（個人用途 = 数千チャンク以下を想定）
- DB バージョン: 3（v2→v3 マイグレーションで `documents` テーブルに `headings`, `first_para`, `tags` カラムを追加）

### Storage Access Framework (SAF)

- フォルダ選択: `ActivityResultContracts.OpenDocumentTree()`
- 永続アクセス権: `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
- ファイル読み込み: `DocumentFile.fromTreeUri()` → `ContentResolver.openInputStream()`

## アーキテクチャ

```
UI (Compose) → ViewModel → AgentPipeline → (LlmService / EmbedderService / Room)
                         → RagPipeline   → (general フォールバック)
                         → Repository   → Room
```

依存性注入は `MiniBrainApp` での手動シングルトン。Hilt は使わない。

### 検索フロー（AgentPipeline）

```
質問
↓ planSearch
  ├─ 日付キーワード → diary_lookup（LLM 不要）
  ├─ ファイル名一致 → file_lookup（LLM 不要）
  └─ その他        → LLM が SearchPlan JSON を生成
↓ executeSearch（intent 別）
  ├─ diary_lookup   : DateResolver で日付解決 → searchByPath
  ├─ file_lookup    : searchByPath → getByDoc
  ├─ topic_research : BM25 + ベクトル検索（targetFolders で絞り込み）
  └─ general        : RagPipeline.retrieveTopChunks() に委譲
↓ answer: LLM がソース種別を意識して回答生成
```

## モデルファイルのパス

```
context.filesDir/models/gemma-4-E2B-it.litertlm      # LLM（約 2.5 GB）
context.filesDir/models/universal_sentence_encoder_multilingual.tflite  # Embedder（約 280 MB）
```

モデルは `ModelDownloader` が Range リクエスト対応でダウンロードし、レジュームをサポートする。

## よく変更するファイル

| 変更内容               | 対象ファイル                                          |
| ---------------------- | ----------------------------------------------------- |
| 検索計画ロジック       | `ai/agent/AgentPipeline.kt`                           |
| 日付解決               | `ai/agent/DateResolver.kt`                            |
| プロンプトテンプレート | `ai/agent/AgentPipeline.kt`（buildAnswerPrompt）      |
| LLM 生成パラメータ     | `ai/llm/LlmService.kt`                                |
| チャンク分割ロジック   | `data/md/MarkdownChunker.kt`                          |
| ファイルメタ抽出       | `data/md/MarkdownMetaExtractor.kt`                    |
| インデックス処理       | `data/repo/DocumentRepository.kt`                     |
| チャット履歴管理       | `data/repo/ChatRepository.kt`                         |
| チャット UI            | `ui/screens/ChatScreen.kt` + `ui/vm/ChatViewModel.kt` |

## 注意事項

- `ChunkDao.getAll()` は全チャンクをメモリにロードする。数万チャンクになる場合は ANN（HNSW 等）への置換を検討
- `EmbedderService` と `LlmService` の初期化はバックグラウンドスレッド（`Dispatchers.Default`）で行う
- `EmbedderService.embed()` は内部で `Mutex` を使ってシリアライズされているため、並列呼び出しは安全だがスループットは出ない
- クラウド API の追加は禁止（プライバシー要件）
- `AgentPipeline.planSearch()` は `diary_lookup` / `file_lookup` をクライアントサイドで判定して LLM 呼び出しを省略する。LLM 計画が必要な場合は `topic_research` / `general` となり計 2 回の LLM 呼び出しが発生する
- DB マイグレーション: 既存 `documents` レコードの `headings` / `first_para` / `tags` は次回差分インデックス時に自動補完される。強制補完は Settings → 再インデックスで可能
