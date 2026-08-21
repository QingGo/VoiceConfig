#!/usr/bin/env python3
"""Reproduce ASR cold-start vs warm-start difference on emulator/device.

Runs the same wav twice in the same app process:
  1. First run after force-stop (cold native lib load).
  2. Second run without killing the process (warm).

Usage:
    python scripts/reproduce_asr_cold_start.py [device] [model_id] [wav]
"""
import pathlib
import re
import subprocess
import sys
import time

DEVICE = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
MODEL = sys.argv[2] if len(sys.argv) > 2 else "sherpa-zh-14m-2023"
WAV = pathlib.Path(sys.argv[3] if len(sys.argv) > 3 else "test_audio/0.wav")
PACKAGE = "com.voiceconfig.app"


def adb(*args):
    cmd = ["adb", "-s", DEVICE] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True)


def run_once(tag):
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.AsrBenchmarkActivity",
        "--es", "wav", remote_app, "--es", "model", MODEL)
    deadline = time.time() + 30
    while time.time() < deadline:
        time.sleep(1)
        log = adb("logcat", "-d", "-s", "AsrBenchmark:*").stdout
        m = re.search(r"RESULT model=\S+ text=(.*)", log)
        e = re.search(r"ERROR model=\S+ message=(.*)", log)
        if m:
            print(f"[{tag}] RESULT: {m.group(1)}")
            return m.group(1)
        if e:
            print(f"[{tag}] ERROR: {e.group(1)}")
            return None
    print(f"[{tag}] TIMEOUT")
    return None


def main():
    global remote_app
    remote_tmp = f"/data/local/tmp/bench_{WAV.name}"
    remote_app = f"/data/data/{PACKAGE}/files/bench_{WAV.name}"
    adb("push", str(WAV.resolve()), remote_tmp)
    adb("shell", "run-as", PACKAGE, "cp", remote_tmp, f"files/bench_{WAV.name}")

    # Cold: kill app first
    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    first = run_once("cold")

    # Warm: do NOT force-stop; start again in same process
    time.sleep(1)
    second = run_once("warm")

    print("---")
    print(f"cold: {first}")
    print(f"warm: {second}")
    if first is not None and second is not None and first != second:
        print("REPRODUCED: cold and warm results differ")
        return 1
    print("No difference in this run")
    return 0


if __name__ == "__main__":
    sys.exit(main())
