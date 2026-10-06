#!/usr/bin/env bash
# エミュレータで検索評価を回し、レポートを eval/reports/ に保存する（ADR-045）。
#
#   scripts/eval-emulator.sh [queries.json]   # 既定は eval/queries.json
#
# 初回の準備（一度だけ）:
#   - AVD `minibrain-eval`（system-images;android-35;google_apis;arm64-v8a、RAM 8GB 以上）
#   - eval/models/ にモデル 3 点（ModelDownloader と同じファイル名）
#
# 環境変数: AVD（既定 minibrain-eval）/ ANDROID_HOME / JAVA_HOME / EVAL_GPU=true で LLM を GPU で試す
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QUERIES="${1:-$ROOT/eval/queries.json}"
NOTES="$ROOT/eval/notes"
MODELS="$ROOT/eval/models"
AVD="${AVD:-minibrain-eval}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.minibrain
DEVICE_NOTES=Documents/minibrain-eval
MODEL_FILES=(gemma-4-E2B-it.litertlm multilingual-e5-small-q.onnx e5-tokenizer.json)

log() { printf '\033[1m[eval]\033[0m %s\n' "$*"; }

[[ -f "$QUERIES" ]] || { echo "評価セットがありません: $QUERIES" >&2; exit 1; }
for f in "${MODEL_FILES[@]}"; do
  [[ -f "$MODELS/$f" ]] || { echo "モデルがありません: $MODELS/$f" >&2; exit 1; }
done

# 1. エミュレータ（動いていなければヘッドレスで起動）
if ! "$ADB" devices | grep -q '^emulator-.*device$'; then
  log "エミュレータ $AVD を起動します"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save \
    > "${TMPDIR:-/tmp}/minibrain-emulator.log" 2>&1 &
fi
SERIAL="$("$ADB" wait-for-device && "$ADB" devices | awk '/^emulator-.*device$/ {print $1; exit}')"
ADB="$ADB -s $SERIAL"
until [[ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 3; done
log "端末: $SERIAL"

# 2. ビルドとインストール（入れ直してもフォルダの権限とモデルは残る）
log "ビルドしてインストールします"
(cd "$ROOT" && ANDROID_SERIAL="$SERIAL" ./gradlew -q :app:installDebug :app:installDebugAndroidTest)

# 3. モデル（サイズが違うときだけ送る。2.5GB あるので毎回は送らない）
$ADB shell run-as $PKG mkdir -p files/models files/eval
for f in "${MODEL_FILES[@]}"; do
  local_size=$(stat -f%z "$MODELS/$f")
  remote_size=$($ADB shell run-as $PKG stat -c%s "files/models/$f" 2>/dev/null | tr -d '\r' || true)
  if [[ "$local_size" != "$remote_size" ]]; then
    log "モデルを送ります: $f"
    $ADB push "$MODELS/$f" "/data/local/tmp/$f" >/dev/null
    $ADB shell "cat /data/local/tmp/$f | run-as $PKG sh -c 'cat > files/models/$f' && rm /data/local/tmp/$f"
  fi
done

# 4. ノートと評価セット（ノートは毎回入れ替え、消したファイルが残らないようにする）
log "ノートを送ります: $NOTES"
$ADB shell rm -rf "/sdcard/$DEVICE_NOTES"
$ADB shell mkdir -p "/sdcard/$DEVICE_NOTES"
$ADB push "$NOTES/." "/sdcard/$DEVICE_NOTES/" >/dev/null
# .claude などの隠しフォルダは手元の作業用なので消す
$ADB shell "find /sdcard/$DEVICE_NOTES -mindepth 1 -name '.*' -prune -exec rm -rf {} +"
$ADB shell "run-as $PKG sh -c 'cat > files/eval/queries.json'" < "$QUERIES"
$ADB shell run-as $PKG rm -f files/eval/report.md

# 5. 評価（進み具合は logcat の EmulatorEval タグ）
log "評価を始めます（logcat -s EmulatorEval で進み具合を見られます）"
$ADB logcat -c
$ADB logcat -s EmulatorEval:I -v brief &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true' EXIT
started=$(date +%s)
out=$($ADB shell am instrument -w -e class com.minibrain.eval.EmulatorEvalTest \
  -e gpu "${EVAL_GPU:-false}" $PKG.test/androidx.test.runner.AndroidJUnitRunner)
if ! grep -q '^OK (1 test)' <<<"$(tr -d '\r' <<<"$out")"; then
  echo "$out" >&2
  echo "評価に失敗しました" >&2
  exit 1
fi

# 6. レポートを回収
mkdir -p "$ROOT/eval/reports"
report="$ROOT/eval/reports/$(date +%Y%m%d-%H%M)-emulator.md"
$ADB shell run-as $PKG cat files/eval/report.md > "$report"
log "完了（$(( ($(date +%s) - started) / 60 )) 分）: $report"
head -8 "$report"
