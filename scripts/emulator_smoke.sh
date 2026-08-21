#!/usr/bin/env bash
export MSYS_NO_PATHCONV=1
# 言控 emulator UI smoke test
# Usage: scripts/emulator_smoke.sh [serial]
set -euo pipefail

SERIAL="${1:-emulator-5554}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
TMP_DUMP=/sdcard/window_dump.xml
LOCAL_DUMP=./smoke_ui.xml
ADB=(adb -s "$SERIAL")

tap_center() {
  local desc="$1"
  local xy
  xy=$(python - "$LOCAL_DUMP" "$desc" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
desc = sys.argv[2]
m = re.search(r'content-desc="' + re.escape(desc) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    sys.exit(2)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print(f"{x} {y}")
PY
)
  "${ADB[@]}" shell input tap $xy
}

tap_text() {
  local text="$1"
  local xy
  xy=$(python - "$LOCAL_DUMP" "$text" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
text = sys.argv[2]
m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    sys.exit(2)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print(f"{x} {y}")
PY
)
  "${ADB[@]}" shell input tap $xy
}

echo "==> Install APK"
"${ADB[@]}" install -r "$APK"

echo "==> Launch app"
"${ADB[@]}" shell am force-stop com.voiceconfig.app || true
"${ADB[@]}" shell pm clear com.voiceconfig.app >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant com.voiceconfig.app android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant com.voiceconfig.app android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -n com.voiceconfig.app/.MainActivity
sleep 6

dump() {
  "${ADB[@]}" shell rm -f "$TMP_DUMP" >/dev/null 2>&1 || true
  "${ADB[@]}" shell uiautomator dump "$TMP_DUMP" >/dev/null 2>&1 || true
  "${ADB[@]}" shell cat "$TMP_DUMP" > "$LOCAL_DUMP" 2>/dev/null || true
}

echo "==> Home screen smoke"
dump
for text in "自动化" "高级能力" "模板" "说话" "还没有创建任务"; do
  if ! grep -q "$text" "$LOCAL_DUMP"; then
    echo "FAIL: home missing '$text'"
    exit 1
  fi
done
echo "OK: home texts present"

echo "==> Open template library"
tap_text "模板"
sleep 1
dump
if ! grep -q "模板库" "$LOCAL_DUMP"; then
  echo "FAIL: template library did not open"
  exit 1
fi
echo "OK: template library opened"
"${ADB[@]}" shell input keyevent 4
sleep 1

echo "==> Open create panel via FAB"
tap_center "说话"
sleep 1
dump
if ! grep -q "生成任务" "$LOCAL_DUMP"; then
  echo "FAIL: create panel did not open"
  exit 1
fi
echo "OK: create panel opened"

echo "==> Close panel"
"${ADB[@]}" shell input keyevent 4
sleep 1

echo "==> Open advanced page via tab"
tap_text "高级能力"
sleep 1
dump
for text in "高级能力" "对话" "任务" "运行日志" "新建" "输入指令"; do
  if ! grep -q "$text" "$LOCAL_DUMP"; then
    echo "FAIL: agent page missing '$text'"
    exit 1
  fi
done
echo "OK: agent page texts present"

echo "==> New session"
tap_text "新建"
sleep 1
dump
if ! grep -q "输入指令" "$LOCAL_DUMP"; then
  echo "FAIL: new session did not enter chat"
  exit 1
fi
echo "OK: new session created"

echo "ALL SMOKE TESTS PASSED"
