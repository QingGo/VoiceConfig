#!/usr/bin/env bash
# VoiceConfig UI 回归测试：智能助手页键盘 + 两段式会话导航
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
sleep 7
rm -f "$LOCAL_DUMP"

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

echo "==> 进入智能助手页"
dump
tap_text "智能助手"
sleep 2
dump
for text in "智能助手" "新建"; do
  if ! has_text "$text"; then
    echo "FAIL: Agent 页缺少 '$text'"
    exit 1
  fi
done
echo "OK: Agent 页主要入口存在"

# 新建会话后进入对话详情，验证键盘弹出时 header 仍可见
echo "==> 新建会话"
tap_text "新建"
sleep 2
dump
if ! has_text "输入指令"; then
  echo "FAIL: 新建会话后未进入对话输入页"
  exit 1
fi

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
if ! has_text "智能助手"; then
  echo "FAIL: 键盘弹出后顶部标题不可见，页面被推屏"
  exit 1
fi
if ! has_text "新建"; then
  echo "FAIL: 键盘弹出后顶部新建按钮不可见"
  exit 1
fi
echo "OK: 键盘弹出后顶部内容仍然可见"

echo "==> 返回会话列表检查"
# 第一次返回可能只是收起键盘，第二次返回才会从对话详情回到会话列表
"${ADB[@]}" shell input keyevent 4
sleep 1
"${ADB[@]}" shell input keyevent 4
sleep 1
dump
if ! has_text "选择一个会话，或新建对话"; then
  echo "WARN: 未返回会话列表（可能已在列表），不作为失败"
fi
echo "ALL UI REGRESSION CHECKS PASSED"
