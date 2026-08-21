#!/usr/bin/env python3
"""
Probe whether an app can be brought to foreground while the screen is locked/off.

This simulates the Shizuku/shell execution path used by VoiceConfig:
    am start -n <package>/<activity>

Usage:
    python scripts/check_lock_screen_launch.py <serial> [component]
Example:
    python scripts/check_lock_screen_launch.py emulator-5554 com.android.settings/.Settings
"""
import subprocess
import sys
import time


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT)


def screen_is_off(serial):
    out = adb(serial, ["shell", "dumpsys", "power"])
    for line in out.splitlines():
        if "mWakefulness=" in line:
            return "Asleep" in line or "Dozing" in line
    return False


def top_activity(serial):
    for _ in range(10):
        out = adb(serial, ["shell", "dumpsys", "activity", "activities"])
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("topResumedActivity="):
                return line.split("topResumedActivity=", 1)[1]
            if line.startswith("ResumedActivity:"):
                return line.split("ResumedActivity:", 1)[1].strip()
        time.sleep(0.5)
    return "unknown"


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else None
    component = sys.argv[2] if len(sys.argv) > 2 else "com.android.settings/.Settings"
    turned_off = False

    # Ensure screen is off.
    if not screen_is_off(serial):
        print("Turning screen off...")
        adb(serial, ["shell", "input", "keyevent", "26"])
        turned_off = True
        time.sleep(2)
    print("Screen off:", screen_is_off(serial))

    print(f"Launching {component} while screen is off/locked...")
    try:
        out = adb(serial, ["shell", "am", "start", "-W", "-n", component])
        print(out.strip())
    except subprocess.CalledProcessError as e:
        print("am start failed:", e.output)
        if turned_off:
            adb(serial, ["shell", "input", "keyevent", "26"])
        return 1

    time.sleep(3)
    top = top_activity(serial)
    off = screen_is_off(serial)
    print("Top activity after launch:", top)
    print("Screen still off:", off)

    # Restore screen so the developer is not left with a black display.
    adb(serial, ["shell", "input", "keyevent", "26"])
    time.sleep(1)

    if component.split("/", 1)[0] in top:
        print("PASS: target app reached foreground while screen was off/locked")
        print("NOTE: the app is in foreground; whether it can do internal actions (e.g. check-in) depends on the app's own UI/deep-link/accessibility support.")
        return 0
    else:
        print("FAIL: target app did not become the foreground activity")
        return 1


if __name__ == "__main__":
    sys.exit(main())
