#!/usr/bin/env bash
# Phase 5 真实场景端到端回归（基于 debug broadcast + agent_scenario_eval）。
# 用法: scripts/phase5_e2e_regression.sh [serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1

SERIAL="${1:-192.168.31.109:42097}"
SCENARIOS="$(dirname "$0")/phase5_e2e_scenarios.json"

echo "==> Phase 5 E2E on $SERIAL"
python "$(dirname "$0")/agent_scenario_eval.py" \
  --serial "$SERIAL" \
  suite \
  --scenarios "$SCENARIOS"
