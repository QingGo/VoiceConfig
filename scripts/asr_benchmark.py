#!/usr/bin/env python3
"""Automated ASR benchmark on emulator/device using AsrBenchmarkActivity.

Usage:
    python scripts/asr_benchmark.py [device] [model_id] [wav_dir]
"""
import pathlib
import re
import subprocess
import sys
import time

DEVICE = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
MODEL = sys.argv[2] if len(sys.argv) > 2 else "sherpa-zh-14m-2023"
WAV_DIR = pathlib.Path(sys.argv[3] if len(sys.argv) > 3 else "test_audio")
PACKAGE = "com.voiceconfig.app"


def adb(*args):
    cmd = ["adb", "-s", DEVICE] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True)


def main():
    wavs = sorted(WAV_DIR.glob("*.wav"))
    if not wavs:
        print("no wav files")
        sys.exit(1)
    print(f"Benchmark model={MODEL} device={DEVICE}")
    for wav in wavs:
        remote_tmp = f"/data/local/tmp/bench_{wav.name}"
        remote_app = f"/data/data/{PACKAGE}/files/bench_{wav.name}"
        adb("push", str(wav.resolve()), remote_tmp)
        adb("shell", "run-as", PACKAGE, "cp", remote_tmp, f"files/bench_{wav.name}")
        adb("logcat", "-c")
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.AsrBenchmarkActivity",
            "--es", "wav", remote_app, "--es", "model", MODEL)
        deadline = time.time() + 30
        result = None
        while time.time() < deadline:
            time.sleep(2)
            log = adb("logcat", "-d", "-s", "AsrBenchmark:*").stdout
            m = re.search(r"RESULT model=\S+ text=(.*)", log)
            e = re.search(r"ERROR model=\S+ message=(.*)", log)
            if m:
                result = ("OK", m.group(1))
                break
            if e:
                result = ("ERROR", e.group(1))
                break
        print(f"{wav.name}: {result}")
        # close activity if still open
        adb("shell", "am", "force-stop", PACKAGE)


if __name__ == "__main__":
    main()
