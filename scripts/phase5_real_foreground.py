#!/usr/bin/env python3
"""Phase 5 真实前台验证：确保 Agent 真的把目标 App 带到前台。

用法: python scripts/phase5_real_foreground.py [serial]
"""
import os
import re
import subprocess
import sys
import time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "192.168.31.109:42097"
PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".MainActivity"
BROADCAST = "com.voiceconfig.app.DEBUG_AGENT_INPUT"

ALLOW_WECHAT = os.environ.get("ALLOW_WECHAT") == "1"

SCENARIOS = [
    ("打开瑞幸咖啡", "帮我打开瑞幸咖啡", "com.lucky.luckyclient"),
    ("打开企业微信", "帮我打开企业微信", "com.tencent.wework"),
    ("打开设置", "帮我打开设置", "com.android.settings"),
]

if ALLOW_WECHAT:
    SCENARIOS.insert(1, ("打开微信", "帮我打开微信", "com.tencent.mm"))


def adb(*args):
    cmd = ["adb", "-s", SERIAL] + list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.stdout


def foreground_package():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^}]* u0 (\S+)", out)
    if m:
        return m.group(1).split("/")[0]
    m = re.search(r"ResumedActivity: ActivityRecord\{[^}]* u0 (\S+)", out)
    if m:
        return m.group(1).split("/")[0]
    return ""


def launch_voiceconfig():
    adb("shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}")
    time.sleep(3)


def send(text):
    adb("shell", "am", "broadcast", "-a", BROADCAST,
        "--es", "text", text,
        "--ez", "send", "true",
        "--ez", "newSession", "true")
    time.sleep(12)


def main():
    passed = 0
    for name, text, expected_pkg in SCENARIOS:
        launch_voiceconfig()
        send(text)
        actual = foreground_package()
        ok = actual == expected_pkg
        status = "PASS" if ok else "FAIL"
        print(f"{status}: {name} expected={expected_pkg} actual={actual}")
        if ok:
            passed += 1
    print(f"\n{passed}/{len(SCENARIOS)} foreground scenarios passed")
    return 0 if passed == len(SCENARIOS) else 1


if __name__ == "__main__":
    sys.exit(main())
