#!/usr/bin/env bash
# Install VoiceConfig Agent Node as a systemd *user* service on the remote host.
#
# Usage:
#   ./scripts/install_voiceconfig_node_service.sh <host> [user]
set -euo pipefail

HOST="${1:-${VC_NODE_HOST:-}}"
USER="${2:-${VC_NODE_USER:-}}"
REMOTE_DIR="${VC_NODE_REMOTE_DIR:-voiceconfig-node}"
DATA_DIR="${VC_NODE_DATA_DIR:-~/.voiceconfig-node}"
SERVICE_NAME="voiceconfig-node.service"
LOCAL_SERVICE="$(cd "$(dirname "$0")" && pwd)/${SERVICE_NAME}"

if [[ -z "$HOST" || -z "$USER" ]]; then
  echo "Usage: $0 <host> <user>" >&2
  exit 1
fi

SSH=(ssh -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)
SCP=(scp -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)

echo "==> Copy node script"
"${SSH[@]}" "${USER}@${HOST}" "mkdir -p ~/${REMOTE_DIR} ${DATA_DIR} ~/.config/systemd/user"
"${SCP[@]}" "$(cd "$(dirname "$0")" && pwd)/voiceconfig_agent_node.py" "${USER}@${HOST}:~/${REMOTE_DIR}/voiceconfig_agent_node.py"
"${SCP[@]}" "$LOCAL_SERVICE" "${USER}@${HOST}:~/.config/systemd/user/${SERVICE_NAME}"

echo "==> Stopping nohup instance (if any)"
"${SSH[@]}" "${USER}@${HOST}" "pkill -f '^python3 .*voiceconfig_agent_node.py' 2>/dev/null || true"

echo "==> Enabling/starting user service"
"${SSH[@]}" "${USER}@${HOST}" "systemctl --user daemon-reload && systemctl --user enable --now ${SERVICE_NAME} && loginctl enable-linger ${USER} || true"

sleep 1

echo "==> Status"
"${SSH[@]}" "${USER}@${HOST}" "systemctl --user --no-pager status ${SERVICE_NAME} | head -30"

echo "==> Token"
"${SSH[@]}" "${USER}@${HOST}" "cat ${DATA_DIR}/node.token 2>/dev/null || true"
