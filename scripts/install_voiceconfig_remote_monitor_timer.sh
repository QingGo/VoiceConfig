#!/usr/bin/env bash
# Install a systemd user timer on the remote node host that periodically runs
# the read-only monitor and logs alerts to journald.
#
# Usage:
#   ./scripts/install_voiceconfig_remote_monitor_timer.sh <host> [user]
set -euo pipefail

HOST="${1:-${VC_NODE_HOST:-}}"
USER="${2:-${VC_NODE_USER:-}}"
REMOTE_DIR="${VC_NODE_REMOTE_DIR:-voiceconfig-node}"
DATA_DIR="${VC_NODE_DATA_DIR:-~/.voiceconfig-node}"
SCRIPT_NAME="voiceconfig_remote_monitor.py"
LOCAL_SCRIPT="$(cd "$(dirname "$0")" && pwd)/${SCRIPT_NAME}"
SERVICE_NAME="voiceconfig-monitor.service"
TIMER_NAME="voiceconfig-monitor.timer"
LOCAL_SERVICE="$(cd "$(dirname "$0")" && pwd)/${SERVICE_NAME}"
LOCAL_TIMER="$(cd "$(dirname "$0")" && pwd)/${TIMER_NAME}"

if [[ -z "$HOST" || -z "$USER" ]]; then
  echo "Usage: $0 <host> <user>" >&2
  exit 1
fi

SSH=(ssh -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)
SCP=(scp -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)

echo "==> Copying monitor script and units"
"${SSH[@]}" "${USER}@${HOST}" "mkdir -p ~/${REMOTE_DIR} ~/.config/systemd/user"
"${SCP[@]}" "$LOCAL_SCRIPT" "${USER}@${HOST}:~/${REMOTE_DIR}/${SCRIPT_NAME}"
"${SCP[@]}" "$LOCAL_SERVICE" "${USER}@${HOST}:~/.config/systemd/user/${SERVICE_NAME}"
"${SCP[@]}" "$LOCAL_TIMER" "${USER}@${HOST}:~/.config/systemd/user/${TIMER_NAME}"

echo "==> Enabling timer"
"${SSH[@]}" "${USER}@${HOST}" "systemctl --user daemon-reload && systemctl --user enable --now ${TIMER_NAME} && loginctl enable-linger ${USER} || true"

sleep 1
echo "==> Timer status"
"${SSH[@]}" "${USER}@${HOST}" "systemctl --user --no-pager list-timers ${TIMER_NAME} | head -10"
