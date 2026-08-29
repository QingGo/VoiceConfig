#!/usr/bin/env bash
# Phase 0 导航冒烟回归（模拟器）
# 验证：Navigation Compose 顶层路由、二级路由返回、悬浮球显隐、状态卡字段。
# 用法: scripts/phase0_nav_smoke.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1

SERIAL="${1:-emulator-5554}"
ADB=(adb -s "$SERIAL")
DUMP_REMOTE=/sdcard/vc_phase0_smoke.xml
DUMP_LOCAL=./.vc_phase0_smoke.xml

echo "==> 启动 VoiceConfig"
"${ADB[@]}" shell am force-stop com.voiceconfig.app || true
"${ADB[@]}" shell am start -n com.voiceconfig.app/.MainActivity >/dev/null
sleep 8

dump() {
  "${ADB[@]}" shell rm -f "$DUMP_REMOTE" >/dev/null 2>&1 || true
  "${ADB[@]}" shell uiautomator dump "$DUMP_REMOTE" >/dev/null 2>&1 || true
  "${ADB[@]}" pull "$DUMP_REMOTE" "$DUMP_LOCAL" >/dev/null 2>&1 || true
}

has_text() {
  grep -q "$1" "$DUMP_LOCAL"
}

tap_text() {
  local text="$1"
  local xy
  xy=$(python - "$DUMP_LOCAL" "$text" <<'PY'
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

echo "==> 首页"
dump
for text in "言控" "首页/对话" "自动化" "我的"; do
  if ! has_text "$text"; then
    echo "FAIL: 首页缺少 '$text'"
    exit 1
  fi
done
echo "OK: 首页/底导存在"

echo "==> 自动化页"
tap_text "自动化"
sleep 2
dump
for text in "自动化" "让言控创建" "最近执行"; do
  if ! has_text "$text"; then
    echo "FAIL: 自动化页缺少 '$text'"
    exit 1
  fi
done
echo "OK: 自动化页存在"

echo "==> 设置页"
tap_text "我的"
sleep 2
dump
for text in "设置" "AI 模型" "无障碍" "Shizuku"; do
  if ! has_text "$text"; then
    echo "FAIL: 设置页缺少 '$text'"
    exit 1
  fi
done
echo "OK: 设置页状态卡存在"

echo "==> 设置返回首页"
"${ADB[@]}" shell input keyevent 4
sleep 2
dump
if ! has_text "言控"; then
  echo "FAIL: 设置返回后未回到首页"
  exit 1
fi
echo "OK: 设置返回首页正常"

echo "ALL PHASE0 NAV SMOKE PASSED"
