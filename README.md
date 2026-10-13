# Mini Brain

端末内の Markdown ノートを知識源に、自然言語の質問へオンデバイス AI が答える Android 向けの個人用 RAG アプリ。推論はすべて端末内で完結するため、個人情報を含むメモも外に出さずに扱えます。

## 特徴

- **完全プライベート** — 質問・回答・ノートの内容は端末の外に送らない。通信はモデルのダウンロード時だけ
- **ハイブリッド検索** — BM25（FTS4）・メタデータ（ファイル名 / パス / タグ / 日付）・ベクトル（multilingual-e5-small）を並行に検索し、重み付き RRF で融合。LLM によるクエリ展開・HyDE・Reranker で精度を上げる
- **日付に強い** — 「去年の夏」「先月」「一昨年の3月」などの期間表現を解釈。ファイル名・フロントマター・見出し・本文の日付から、いつの話かを拾う
- **エージェント型の再探索** — 検索結果で答えられないと判断したら、ReAct で `grep` / `read_file` / `timeline_search` などのツールを使って探し直す
- **一覧の質問** — 「〇〇を全部挙げて」「△△フォルダにあるもの」には、該当フォルダのノートを漏れなく並べる
- **差分インデックス** — 変更・追加されたノートだけを再インデックス。消したノートは索引からも消える
- **チャット** — ストリーミング回答（Markdown の表・引用も整形）、停止・再生成、引用元ファイルの表示、履歴の検索と削除（取り消し可）
- **モデル切り替え** — 回答用 LLM を Gemma 4 E2B（既定）/ E4B から選べる
- **表示** — Material 3 の dynamic color、テーマは「端末に従う / ライト / ダーク」

## インストール

Android 12 以上の arm64 端末（RAM 6 GB 以上、空き容量 4 GB 以上推奨）に対応しています。

1. 端末のブラウザで [Releases](https://github.com/handism/mini-brain/releases) を開き、最新版の `mini-brain-x.y.z.apk` をダウンロードする
2. APK を開き、提供元の許可を求められたら許可してインストールする（Play プロテクトの警告は「詳細」→「インストールする」）

更新も同じ手順で上書きできます。[Obtainium](https://github.com/ImranR98/Obtainium) にこのリポジトリを登録すると自動で受け取れます。

> [!WARNING]
> `./gradlew installDebug` で入れた debug ビルドがあると、署名鍵が違うため Releases の APK で上書きできません。debug ビルドをアンインストールしてから入れてください（ダウンロード済みのモデル・インデックス・チャット履歴も消えます）。

## 使い方

1. Wi-Fi に接続してアプリを起動し、「ダウンロード開始」でモデルを取得（約 2.6 GB）
2. Markdown ノートが入ったフォルダを選ぶ
3. インデックスが終わったらチャットで質問する（次回以降はチャット画面から始まる）

## 開発

- Android Studio Narwhal (2025.1.1) 以降（AGP 9.x）、JDK 17

```bash
./gradlew assembleDebug        # ビルド
./gradlew installDebug         # USB デバッグを有効にした実機へインストール
./gradlew testDebugUnitTest    # ユニットテスト
```

設計の背景は [ADR.md](ADR.md)、実装上の制約と検索フローの詳細は [agents.md](agents.md) を参照してください。

### スタック

| カテゴリ | 技術 |
| --- | --- |
| 言語 / UI | Kotlin / Jetpack Compose + Material 3 |
| 最小 SDK | API 31（Android 12） |
| LLM | Gemma 4 E2B / E4B via LiteRT-LM |
| Embedder | multilingual-e5-small（INT8 ONNX）via ONNX Runtime + 純 Kotlin tokenizer |
| DB / 設定 | Room（FTS4 + ベクトル）/ DataStore |
| ファイル | Storage Access Framework |

### 検索の流れ

```
質問
↓ QueryClassifier — 一般知識の質問は検索せず LLM が直接回答
↓ SearchPipeline
   クエリ展開 + HyDE（LLM）
   → BM25 / メタデータ / ベクトルを並行検索
   → 重み付き RRF（meta 1.5 / BM25 1.2 / vector 1.0）、1 ファイル 1 件にまとめる
   → LlmReranker（上位 20 件 → 10 件）
   → 期間クエリ・一覧の質問・フォルダ指定ではその文書を先頭に固定
↓ CoverageChecker — 答えられるか判定。足りなければ ReAct で再探索
↓ 回答生成（上位 3 文書は全文を渡す。ストリーミング）
```

入口は [AgentPipeline.kt](app/src/main/kotlin/com/minibrain/ai/agent/AgentPipeline.kt) です。

```
app/src/main/kotlin/com/minibrain/
├── ai/search/   # 検索パイプライン（展開・HyDE・Reranker）
├── ai/agent/    # AgentPipeline・CoverageCheck・ReAct・日付解釈・回答プロンプト
├── ai/rag/      # RRF・コサイン類似度・リクエスト内キャッシュ
├── ai/llm/      # LiteRT-LM ラッパー・モデルのダウンロードと切り替え
├── ai/embed/    # ONNX Runtime + E5 tokenizer
├── data/        # Room・Markdown の読み込みとチャンク分割・リポジトリ
├── eval/        # 検索精度の評価
├── di/          # 手動 DI（AppContainer）
└── ui/          # Compose 画面・ViewModel・テーマ
```

### 検索精度の評価

架空のノートと評価クエリを [eval/](eval/README.md) に同梱しています。設定 → 開発者 →「検索精度の評価」で評価セットの JSON を選ぶと Recall / MRR などを出します。エミュレータで自動実行するなら `scripts/eval-emulator.sh`（`EVAL_ANSWER=true` で回答まで生成して事実の含有率も測る）。検索まわりを変えたら前後で流して比べてください。

### リリース（メンテナ向け）

`v1.2.3` 形式のタグを push すると、GitHub Actions が署名済み APK を Release に添付します（`-` を含むタグは prerelease）。

```bash
git tag v1.0.0 && git push origin v1.0.0
```

初回だけ Actions の Secrets に `RELEASE_KEYSTORE_BASE64`（`base64 -i release.jks` の出力）・`RELEASE_KEYSTORE_PASSWORD`・`RELEASE_KEY_ALIAS`・`RELEASE_KEY_PASSWORD` を登録します。keystore は `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 36500 -alias mini-brain` で作れます。なくすと既存のインストールを上書きできなくなるので、リポジトリ外にバックアップしてください。

## モデル

| モデル | 用途 | サイズ | ライセンス |
| --- | --- | --- | --- |
| Gemma 4 E2B | 回答生成（既定） | 約 2.5 GB | Apache 2.0 |
| Gemma 4 E4B | 回答生成（設定で選んだとき） | 約 3.7 GB | Apache 2.0 |
| multilingual-e5-small (INT8 ONNX) | 埋め込み | 約 118 MB | MIT |
| XLM-RoBERTa SentencePiece | トークナイズ | 約 17 MB | MIT |

モデルはアプリの内部ストレージ（`filesDir/models/`）に保存され、アンインストールで消えます。

## プライバシー

- 推論はすべて端末内
- 通信はモデルのダウンロード（初回と、モデルを切り替えたとき）だけ
- ノートの内容・質問・回答・チャット履歴は端末の外に送らない
