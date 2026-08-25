#!/usr/bin/env bash
# Install/update the minimal VoiceConfig Agent Node on a remote Linux host.
#
# Usage:
#   ./scripts/install_voiceconfig_node.sh <host> [user]
#   VC_NODE_PORT=8787 ./scripts/install_voiceconfig_node.sh 100.91.244.17 zeng
set -euo pipefail

HOST="${1:-${VC_NODE_HOST:-}}"
USER="${2:-${VC_NODE_USER:-}}"
PORT="${VC_NODE_PORT:-8787}"
REMOTE_DIR="${VC_NODE_REMOTE_DIR:-voiceconfig-node}"
DATA_DIR="${VC_NODE_DATA_DIR:-~/.voiceconfig-node}"
SCRIPT_NAME="voiceconfig_agent_node.py"
LOCAL_SCRIPT="$(cd "$(dirname "$0")" && pwd)/${SCRIPT_NAME}"

if [[ -z "$HOST" ]]; then
  echo "Usage: $0 <host> [user]" >&2
  exit 1
fi
if [[ -z "$USER" ]]; then
  USER="$USER"
fi
if [[ -z "$USER" ]]; then
  echo "Error: username is required (pass as second arg or set VC_NODE_USER)" >&2
  exit 1
fi

SSH=(ssh -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)
SCP=(scp -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=8)

echo "==> Preparing remote directories on ${USER}@${HOST}"
"${SSH[@]}" "${USER}@${HOST}" "mkdir -p ~/${REMOTE_DIR} ${DATA_DIR}"

echo "==> Copying ${SCRIPT_NAME}"
"${SCP[@]}" "$LOCAL_SCRIPT" "${USER}@${HOST}:~/${REMOTE_DIR}/${SCRIPT_NAME}"

echo "==> Restarting node on port ${PORT}"
REMOTE_CMD="cd ~/${REMOTE_DIR} && \
  pkill -f '^python3 .*voiceconfig_agent_node.py' 2>/dev/null || true; \
  nohup python3 ${SCRIPT_NAME} --host 0.0.0.0 --port ${PORT} --data-dir ${DATA_DIR} > ${DATA_DIR}/server.log 2>&1 & \
  echo started"
"${SSH[@]}" "${USER}@${HOST}" "$REMOTE_CMD"

sleep 1

echo "==> Token"
TOKEN="$("${SSH[@]}" "${USER}@${HOST}" "cat ${DATA_DIR}/node.token")"
echo "  ${TOKEN}"

echo "==> Health check"
curl -sS -m 8 -H "Authorization: Bearer ${TOKEN}" "http://${HOST}:${PORT}/health" || true
echo
echo "Node URL: http://${HOST}:${PORT}"
echo "Token file on remote: ${DATA_DIR}/node.token"
