#!/usr/bin/env python3
"""Real-device smoke test for VoiceConfig.

Usage:
    python scripts/smoke_test.py [adb_serial]

It installs the debug APK, launches the app, opens the template library,
selects a built-in template, generates the task, and verifies the result
appears without a crash.
"""

import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DEVICE = sys.argv[1] if len(sys.argv) > 1 else None
ROOT = pathlib.Path(__file__).resolve().parent.parent
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".MainActivity"


def run_adb(*args, check=False):
    cmd = ["adb"]
    if DEVICE:
        cmd += ["-s", DEVICE]
    cmd += list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        print("ADB error:", " ".join(cmd))
        print(proc.stderr)
        sys.exit(1)
    return proc


def get_ui_xml():
    run_adb("shell", "uiautomator", "dump", "/sdcard/voiceconfig_ui.xml")
    proc = run_adb("shell", "cat", "/sdcard/voiceconfig_ui.xml")
    text = proc.stdout
    start = text.find("<?xml")
    if start < 0:
        print("DEBUG uiautomator stdout:", repr(text[:500]))
        raise RuntimeError("uiautomator dump returned no XML")
    return ET.fromstring(text[start:])


def find_node(root, text=None, desc=None, cls=None):
    for node in root.iter("node"):
        if text and text in node.get("text", ""):
            return node
        if desc and desc in node.get("content-desc", ""):
            return node
        if cls and cls == node.get("class", ""):
            return node
    return None


def bounds_center(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(node):
    center = bounds_center(node)
    if not center:
        raise RuntimeError("node has no bounds")
    run_adb("shell", "input", "tap", str(center[0]), str(center[1]))


def main():
    if not APK.exists():
        print(f"FAIL: APK not found at {APK}")
        sys.exit(1)

    print("[1/7] Install APK")
    run_adb("install", "-r", str(APK), check=True)

    print("[2/7] Clear app data, clear logcat, and launch app")
    run_adb("shell", "pm", "clear", PACKAGE)
    run_adb("logcat", "-c")
    for attempt in range(3):
        run_adb("shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}", check=True)
        time.sleep(4)
        top = run_adb("shell", "dumpsys", "activity", "activities").stdout
        if re.search(r"topResumedActivity=.*" + re.escape(PACKAGE), top) or re.search(r"ResumedActivity:.*" + re.escape(PACKAGE), top):
            break
        print(f"  retry launch (attempt {attempt + 1})")

    pid = run_adb("shell", "pidof", PACKAGE).stdout.strip()
    if not pid:
        print("FAIL: app process is not running after launch")
        sys.exit(1)
    top = run_adb("shell", "dumpsys", "activity", "activities").stdout
    if not (re.search(r"topResumedActivity=.*" + re.escape(PACKAGE), top) or re.search(r"ResumedActivity:.*" + re.escape(PACKAGE), top)):
        print("FAIL: app is not the foreground activity")
        sys.exit(1)
    print(f"  app pid={pid}")

    print("[3/7] Open template library")
    root = get_ui_xml()
    template_button = find_node(root, text="模板")
    if template_button is None:
        print("FAIL: template button not found")
        sys.exit(1)
    tap(template_button)
    time.sleep(1)

    root = get_ui_xml()
    if find_node(root, text="模板库") is None:
        print("FAIL: template library did not open")
        sys.exit(1)

    print("[4/7] Select a built-in template")
    root = get_ui_xml()
    template = find_node(root, text="喝水提醒") or find_node(root, text="打开瑞幸咖啡")
    if template is None:
        print("FAIL: no built-in template found in template library")
        sys.exit(1)
    tap(template)
    time.sleep(1)

    print("[5/7] Verify create panel opens with template prefilled")
    root = get_ui_xml()
    if find_node(root, text="生成任务") is None:
        print("FAIL: create panel did not open after selecting template")
        sys.exit(1)
    if find_node(root, text="模板库") is not None:
        print("FAIL: template library still open after selecting template")
        sys.exit(1)

    print("[6/7] Tap generate task")
    root = get_ui_xml()
    generate_btn = find_node(root, text="生成任务")
    if generate_btn is None:
        print("FAIL: generate button not found")
        sys.exit(1)
    tap(generate_btn)
    time.sleep(5)

    print("[7/7] Verify task created / parse result and check crash")
    root = get_ui_xml()
    ok = (
        find_node(root, text="任务已创建") is not None
        or find_node(root, text="我的任务") is not None
        or find_node(root, text="解析成功") is not None
        or find_node(root, text="已识别") is not None
    )
    if not ok:
        print("FAIL: expected task creation / parse result not found")
        sys.exit(1)

    log = run_adb("logcat", "-d", "-t", "300").stdout
    if "FATAL EXCEPTION" in log and f"Process: {PACKAGE}" in log:
        print("FAIL: crash detected in logcat")
        print(log[-2000:])
        sys.exit(1)

    print("SMOKE TEST PASSED")


if __name__ == "__main__":
    main()
