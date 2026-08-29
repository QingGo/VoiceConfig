#!/usr/bin/env bash
# 真实场景回归：必须实际打开 App 并验证前台。
# 用法: scripts/phase5_real_e2e.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1
SERIAL="${1:-192.168.31.109:42097}"
export VC_KEEP_APP=1
python "$(dirname "$0")/agent_scenario_eval.py" \
  --serial "$SERIAL" \
  suite \
  --scenarios "$(dirname "$0")/phase5_real_scenarios.json"
