#!/usr/bin/env bash
# 一键连接真机无线调试（VoiceConfig 开发辅助）
# 用法:
#   scripts/adb_connect.sh [IP] [PORT]
#   scripts/adb_connect.sh                  # 使用默认 IP，并尝试已知端口
#   scripts/adb_connect.sh 192.168.1.100   # 尝试已知端口
#   scripts/adb_connect.sh 192.168.1.100 42889
set -euo pipefail

LOCAL_CONF="${VOICECONFIG_ADB_HOST_FILE:-scripts/adb_host.local}"
if [ -f "$LOCAL_CONF" ]; then
  IP="$(head -n1 "$LOCAL_CONF" | tr -d '[:space:]')"
else
  IP="${1:-192.168.1.100}"
fi
PORT="${2:-}"
ADB="${ADB:-adb}"

if [ -n "$PORT" ]; then
  echo "==> adb connect ${IP}:${PORT}"
  "$ADB" connect "${IP}:${PORT}"
  "$ADB" -s "${IP}:${PORT}" wait-for-device
  "$ADB" devices -l
  exit 0
fi

# 端口会变化；按最近已知顺序尝试，连接成功后停止。
KNOWN_PORTS=(40061 41085 41583 42889 43303 39181 41659)
for p in "${KNOWN_PORTS[@]}"; do
  echo "==> try ${IP}:${p}"
  if "$ADB" connect "${IP}:${p}" 2>&1 | grep -q "connected"; then
    echo "==> connected to ${IP}:${p}"
    "$ADB" -s "${IP}:${p}" wait-for-device
    "$ADB" devices -l
    exit 0
  fi
done

echo "!! 未能连接 ${IP} 的已知端口。请先在手机开发者选项里查看新端口，然后运行:"
echo "   scripts/adb_connect.sh ${IP} <新端口>"
exit 1
