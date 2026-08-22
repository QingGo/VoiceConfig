#!/usr/bin/env python3
"""VoiceConfig ASR 离线/真机基准脚本。

用法:
  python scripts/asr_benchmark.py --serial emulator-5554 \
      --wav test_audio/example.wav --model sherpa-ctc-2025 --iterations 3

会:
  1. adb push WAV 到设备
  2. 启动 AsrBenchmarkActivity
  3. 捕获 logcat 中的 AsrBenchmark / VoiceConfigAsrTiming
  4. 输出每次识别的文本与耗时
"""

import argparse
import re
import subprocess
import sys
import time

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".AsrBenchmarkActivity"
BENCH_TAG = "AsrBenchmark"
TIMING_TAG = "VoiceConfigAsrTiming"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True)


def clear_logcat(serial):
    adb(serial, ["logcat", "-c"])


def push_wav(serial, wav_path):
    proc = adb(serial, ["push", wav_path, "/sdcard/voiceconfig_asr_test.wav"])
    if proc.returncode != 0:
        raise RuntimeError(f"push failed: {proc.stderr}")
    print(f"pushed {wav_path}")


def launch_bench(serial, model_id):
    proc = adb(serial, [
        "shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}",
        "--es", "wav", "/sdcard/voiceconfig_asr_test.wav",
        "--es", "model", model_id,
    ])
    if proc.returncode != 0:
        raise RuntimeError(f"launch failed: {proc.stderr}")


def collect_result(serial, wait_seconds=45):
    deadline = time.time() + wait_seconds
    lines = []
    while time.time() < deadline:
        time.sleep(1)
        proc = adb(serial, ["logcat", "-d", "-s", BENCH_TAG, TIMING_TAG])
        text = proc.stdout or ""
        # 只要看到活动结束/错误或 timing 行就认为完成
        if "RESULT model=" in text or "ERROR model=" in text:
            return text
        if "AsrBenchmark" in text and "totalMs" in text:
            return text
    return "".join(lines)


def parse_bench(text):
    result = {}
    m = re.search(r"RESULT model=\S+ text=(.*?) totalMs=(\d+)", text)
    if m:
        result["text"] = m.group(1)
        result["total_ms"] = int(m.group(2))
    m = re.search(r"ERROR model=\S+ message=(.*?) totalMs=(\d+)", text)
    if m:
        result["error"] = m.group(1)
        result["total_ms"] = int(m.group(2))
    for line in text.splitlines():
        if "VoiceConfigAsrTiming" in line:
            result["timing"] = line
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--wav", required=True)
    parser.add_argument("--model", default="sherpa-ctc-2025")
    parser.add_argument("--iterations", type=int, default=1)
    args = parser.parse_args()

    push_wav(args.serial, args.wav)
    results = []
    for i in range(args.iterations):
        print(f"--- iteration {i + 1} ---")
        clear_logcat(args.serial)
        launch_bench(args.serial, args.model)
        output = collect_result(args.serial)
        parsed = parse_bench(output)
        results.append(parsed)
        if "text" in parsed:
            print(f"result: {parsed['text']}")
        if "error" in parsed:
            print(f"error: {parsed['error']}")
        if "timing" in parsed and parsed["timing"]:
            print(parsed["timing"])
        if "total_ms" in parsed:
            print(f"totalMs={parsed['total_ms']}")

    if results:
        totals = [r.get("total_ms") for r in results if r.get("total_ms")]
        if totals:
            print(f"\n--- summary ---")
            print(f"runs={len(totals)} avg_total_ms={sum(totals)/len(totals):.1f} "
                  f"min={min(totals)} max={max(totals)}")


if __name__ == "__main__":
    sys.exit(main())
