#!/usr/bin/env bash
# 真机调试环境一键准备：安装最新 APK、启动 App、写回无障碍开关、开启 Mock LLM/AutoConfirm。
# 用法: scripts/real_device_setup.sh [serial] [apk]
set -euo pipefail
export MSYS_NO_PATHCONV=1
SERIAL="${1:-192.168.31.111:39865}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="com.voiceconfig.app"
SERVICE="com.voiceconfig.app/com.voiceconfig.app.service.AgentAccessibilityService"

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
sleep 2
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$SERVICE"
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
sleep 2
adb -s "$SERIAL" shell am broadcast -a com.voiceconfig.app.DEBUG_MOCK_LLM --ez enabled true >/dev/null
adb -s "$SERIAL" shell am broadcast -a com.voiceconfig.app.DEBUG_AUTO_CONFIRM --ez enabled true >/dev/null
echo "真机准备完成: $SERIAL"
