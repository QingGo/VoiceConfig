#!/usr/bin/env bash
# 模拟器离线 Mock LLM E2E：无需 DeepSeek API Key，直接跑核心 Agent 链路。
# 用法: scripts/emulator_mock_e2e.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1
SERIAL="${1:-emulator-5554}"
PYTHON="${PYTHON:-python}"

# 先启动 App，让 AccessibilityService 所在进程存在，否则部分模拟器会在 stop 后丢失开关。
adb -s "$SERIAL" shell am start -W -n com.voiceconfig.app/.MainActivity >/dev/null 2>&1 || true
sleep 2

# 写回无障碍开关，并等待系统绑定服务。
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services com.voiceconfig.app/com.voiceconfig.app.service.AgentAccessibilityService || true
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1 || true
sleep 3

# 保持 App 进程，避免 force-stop 后模拟器重置无障碍开关。
export VC_KEEP_APP=1

"$PYTHON" "$(dirname "$0")/agent_scenario_eval.py" \
  --serial "$SERIAL" \
  suite \
  --scenarios "$(dirname "$0")/emulator_mock_scenarios.json" \
  --mock-llm
