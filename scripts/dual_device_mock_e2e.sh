#!/usr/bin/env bash
# 真机 + 模拟器双跑 Mock LLM E2E。
# 用法: scripts/dual_device_mock_e2e.sh [real_serial] [emulator_serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1
REAL_SERIAL="${1:-192.168.31.111:39865}"
EMULATOR_SERIAL="${2:-emulator-5554}"
PYTHON="${PYTHON:-python}"
DIR="$(cd "$(dirname "$0")" && pwd)"

# 保持 App 进程，避免 force-stop 后真机/模拟器无障碍设置被重置。
export VC_KEEP_APP=1

for serial in "$REAL_SERIAL" "$EMULATOR_SERIAL"; do
  echo
  echo "==================== $serial ===================="
  (cd "$DIR" && "$PYTHON" agent_scenario_eval.py \
    --serial "$serial" \
    suite \
    --scenarios emulator_mock_scenarios.json \
    --mock-llm \
    --auto-confirm)
done

echo
echo "双设备 Mock LLM E2E 全部通过。"
