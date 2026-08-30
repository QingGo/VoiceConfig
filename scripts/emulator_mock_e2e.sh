#!/usr/bin/env bash
# 模拟器离线 Mock LLM E2E：无需 DeepSeek API Key，直接跑核心 Agent 链路。
# 用法: scripts/emulator_mock_e2e.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1
SERIAL="${1:-emulator-5554}"
PYTHON="${PYTHON:-python}"
"$PYTHON" "$(dirname "$0")/agent_scenario_eval.py" \
  --serial "$SERIAL" \
  suite \
  --scenarios "$(dirname "$0")/emulator_mock_scenarios.json" \
  --mock-llm
