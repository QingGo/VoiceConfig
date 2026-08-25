#!/usr/bin/env bash
# Install/update the minimal VoiceConfig Agent Node on a remote Linux host.
#
# Usage:
#   ./scripts/install_voiceconfig_node.sh <host> [user]
#   VC_NODE_PORT=8787 VC_NODE_BIND=tailscale ./scripts/install_voiceconfig_node.sh 100.91.244.17 zeng
#
# VC_NODE_BIND:
#   tailscale (default) - bind only to the Tailscale IPv4 address
#   host                - bind 0.0.0.0 (insecure; only for non-production testing)
set -euo pipefail

HOST="${1:-${VC_NODE_HOST:-}}"
USER="${2:-${VC_NODE_USER:-}}"
PORT="${VC_NODE_PORT:-8787}"
BIND_MODE="${VC_NODE_BIND:-tailscale}"
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

NODE_CONFIG_JSON='{"bind_tailscale":true,"port":8787,"allowed_commands":["hostname","uname","uptime","free","df","ps","os_release","network","tailscale"]}'

echo "==> Preparing remote directories on ${USER}@${HOST}"
"${SSH[@]}" "${USER}@${HOST}" "mkdir -p ~/${REMOTE_DIR} ${DATA_DIR}"

echo "==> Writing per-node config if absent"
"${SSH[@]}" "${USER}@${HOST}" "if [ ! -f ${DATA_DIR}/node.json ]; then printf '%s\n' '${NODE_CONFIG_JSON}' > ${DATA_DIR}/node.json && chmod 600 ${DATA_DIR}/node.json; fi"

echo "==> Copying ${SCRIPT_NAME}"
"${SCP[@]}" "$LOCAL_SCRIPT" "${USER}@${HOST}:~/${REMOTE_DIR}/${SCRIPT_NAME}"

if [[ "$BIND_MODE" == "tailscale" ]]; then
  BIND_ARGS="--bind-tailscale"
  BIND_LABEL="Tailscale-only"
else
  BIND_ARGS="--host 0.0.0.0"
  BIND_LABEL="0.0.0.0 (INsecure, not recommended)"
fi

echo "==> Restarting node on port ${PORT} (${BIND_LABEL})"
REMOTE_CMD="cd ~/${REMOTE_DIR} && \
  pkill -f '^python3 .*voiceconfig_agent_node.py' 2>/dev/null || true; \
  nohup python3 ${SCRIPT_NAME} ${BIND_ARGS} --port ${PORT} --data-dir ${DATA_DIR} > ${DATA_DIR}/server.log 2>&1 & \
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
echo "Bind: ${BIND_LABEL}"
echo "Token file on remote: ${DATA_DIR}/node.token"
