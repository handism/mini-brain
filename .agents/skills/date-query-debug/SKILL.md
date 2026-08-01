---
name: date-query-debug
description: 期間クエリ・日付クエリ（「先月の〜」「いつ行った？」等）で「情報が含まれていません」と回答された場合の調査手順。DateResolver / SearchPipeline / CoverageChecker のログを辿って原因を切り分ける。
---

# 日付・期間クエリの不具合調査

期間クエリで「情報が含まれていません」と返答された場合、以下の順で切り分ける。

```bash
adb logcat -s CoverageChecker:D SearchPipeline:D AgentPipeline:D DateResolver:D
```

1. **DateResolver で `dateRange` が解決されているか**
   解決されていなければ `ai/agent/DateResolver.kt` のパターンを確認する。

2. **SearchPipeline で `dateRangeSearch range=... hits=N` の N が 0 になっていないか**
   0 の場合は `documentDate` が NULL の文書が多い → Settings → 再インデックスを実行する。

3. **SearchPipeline で `dateRange pin: pinned=N final=M` が出ているか**
   出ていなければ pin ロジック（`DATE_RANGE_PIN_COUNT = 5`、ADR-025）に到達していない。

4. **CoverageChecker で `short-circuit: date query with dated candidate` が出ているか**
   出ていなければ短絡判定が効かず ReAct ループにフォールバックしている（ADR-026）。

## 関連する実装ポイント

- `SearchPipeline.search` の dateRange pin（ADR-025）
- `CoverageChecker.check` の 2 段短絡（日付プレフィックス / topicMatch、ADR-026）
- `AgentPipeline.buildAnswerPrompt` の「日付を拾う優先順位 3 段」注入
