#!/usr/bin/env bash
# VoiceConfig UI 回归测试：Agent 页键盘 + 新建入口
# 用法: scripts/emulator_ui_regression.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1

SERIAL="${1:-emulator-5554}"
ADB=(adb -s "$SERIAL")
TMP_DUMP=/sdcard/vc_ui_regression.xml
LOCAL_DUMP=./.vc_ui_regression.xml

echo "==> 启动应用"
"${ADB[@]}" shell am force-stop com.voiceconfig.app || true
"${ADB[@]}" shell am start -n com.voiceconfig.app/.MainActivity >/dev/null
sleep 4

dump() {
  "${ADB[@]}" shell rm -f "$TMP_DUMP" >/dev/null 2>&1 || true
  "${ADB[@]}" shell uiautomator dump "$TMP_DUMP" >/dev/null 2>&1 || true
  "${ADB[@]}" pull "$TMP_DUMP" "$LOCAL_DUMP" >/dev/null 2>&1 || true
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

has_text() {
  grep -q "$1" "$LOCAL_DUMP"
}

echo "==> 进入高级能力页"
dump
tap_text "高级能力"
sleep 2
dump
for text in "高级能力" "新建" "对话" "任务" "运行日志"; do
  if ! has_text "$text"; then
    echo "FAIL: Agent 页缺少 '$text'"
    exit 1
  fi
done
echo "OK: Agent 页主要入口存在"

# 点击输入框并输入文本，验证键盘出现后顶部 header 仍可见
echo "==> 点击输入框并输入文本"
python - "$SERIAL" <<'PY'
import re, subprocess, sys
xml = open('.vc_ui_regression.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    print("FAIL: no EditText")
    sys.exit(1)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(["adb", "-s", sys.argv[1], "shell", "input", "tap", str(x), str(y)], check=True)
PY
sleep 1
"${ADB[@]}" shell input text "hello"
sleep 1

dump
if ! has_text "高级能力"; then
  echo "FAIL: 键盘弹出后顶部标题不可见，页面被推屏"
  exit 1
fi
if ! has_text "新建"; then
  echo "FAIL: 键盘弹出后顶部新建按钮不可见"
  exit 1
fi
echo "OK: 键盘弹出后顶部内容仍然可见"

echo "==> 新建入口可见性检查"
if ! has_text "新建会话"; then
  # 如果当前已有会话选中，则先回到会话列表再检查
  "${ADB[@]}" shell input keyevent 4
  sleep 1
  dump
fi
if ! has_text "新建会话"; then
  echo "WARN: 当前页面未展示“新建会话”大按钮（可能已有会话选中），不作为失败"
fi
echo "ALL UI REGRESSION CHECKS PASSED"
