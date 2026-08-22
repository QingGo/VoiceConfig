#!/usr/bin/env python3
"""VoiceConfig ASR 离线/真机基准脚本。

用法:
  # 单个 WAV
  python scripts/asr_benchmark.py --serial emulator-5554 \
      --wav test_audio/example.wav --model sherpa-ctc-2025 --iterations 3

  # 固定用例集（自动匹配关键词）
  python scripts/asr_benchmark.py --serial emulator-5554 \
      --cases scripts/asr_test_cases.json --wav-dir test_audio --model sherpa-bilingual-zh-en-2023

流程:
  1. adb push WAV 到设备
  2. 启动 AsrBenchmarkActivity
  3. 捕获 logcat 中的 AsrBenchmark / VoiceConfigAsrTiming
  4. 输出识别文本、耗时、关键词匹配结果
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".AsrBenchmarkActivity"
BENCH_TAG = "AsrBenchmark"
TIMING_TAG = "VoiceConfigAsrTiming"
REMOTE_WAV = "/data/user/0/com.voiceconfig.app/files/asr_test.wav"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True)


def clear_logcat(serial):
    adb(serial, ["logcat", "-c"])


def push_wav(serial, wav_path):
    """通过 base64 + run-as 写入 App 私有 files 目录，避开 scoped storage 权限。"""
    with open(wav_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    remote_cmd = f"run-as {PACKAGE} sh -c 'base64 -d > {REMOTE_WAV}'"
    proc = subprocess.run(
        ["adb"] + (["-s", serial] if serial else []) + ["shell", remote_cmd],
        input=b64,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"push failed: {proc.stderr}")
    print(f"pushed {wav_path} to app private files")


def launch_bench(serial, model_id, threads=None, warm=False):
    args = [
        "shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}",
        "--es", "wav", REMOTE_WAV,
        "--es", "model", model_id,
    ]
    if threads:
        args += ["--es", "threads", str(threads)]
    if warm:
        args += ["--ez", "warm", "true"]
    proc = adb(serial, args)
    if proc.returncode != 0:
        raise RuntimeError(f"launch failed: {proc.stderr}")


def collect_result(serial, wait_seconds=45):
    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        time.sleep(1)
        proc = adb(serial, ["logcat", "-d", "-s", BENCH_TAG, TIMING_TAG])
        text = proc.stdout or ""
        if "RESULT model=" in text or "ERROR model=" in text:
            return text
        if "AsrBenchmark" in text and "totalMs" in text:
            return text
    return ""


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
    m = re.search(r"WARMUP model=\S+ warmupMs=(\d+)", text)
    if m:
        result["warmup_ms"] = int(m.group(1))
    for line in text.splitlines():
        if "VoiceConfigAsrTiming" in line:
            result["timing"] = line
    return result


def load_cases(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["cases"]


def run_one(serial, wav_path, model_id, threads=None, warm=False):
    push_wav(serial, wav_path)
    clear_logcat(serial)
    launch_bench(serial, model_id, threads=threads, warm=warm)
    output = collect_result(serial)
    return parse_bench(output)


def check_keywords(text, keywords):
    if not keywords:
        return None
    lower = (text or "").lower()
    return all(k.lower() in lower for k in keywords)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--wav", default=None, help="单个 WAV 路径；配合 --cases 时作为兜底")
    parser.add_argument("--cases", default=None, help="固定测试集 JSON")
    parser.add_argument("--wav-dir", default=None, help="固定测试集对应 WAV 目录")
    parser.add_argument("--model", default="sherpa-ctc-2025")
    parser.add_argument("--threads", type=int, default=None, help="覆盖 CPU 线程数")
    parser.add_argument("--warm", action="store_true", help="先预热再识别（验证 warm 路径）")
    parser.add_argument("--iterations", type=int, default=1)
    args = parser.parse_args()

    if args.cases:
        cases = load_cases(args.cases)
        all_results = []
        for case in cases:
            case_id = case["id"]
            wav = None
            if args.wav_dir:
                candidate = os.path.join(args.wav_dir, f"{case_id}.wav")
                if os.path.exists(candidate):
                    wav = candidate
            if wav is None:
                wav = args.wav
            if wav is None:
                print(f"skip {case_id}: no wav file")
                continue
            print(f"--- {case_id} ---")
            parsed = run_one(args.serial, wav, args.model, args.threads, args.warm)
            parsed["case_id"] = case_id
            parsed["expected_text"] = case.get("text")
            parsed["keywords"] = case.get("keywords", [])
            parsed["keyword_ok"] = check_keywords(parsed.get("text"), case.get("keywords", []))
            all_results.append(parsed)
            print(f"result: {parsed.get('text', parsed.get('error', 'NO_RESULT'))}")
            if parsed.get("timing"):
                print(parsed["timing"])
            if parsed.get("total_ms") is not None:
                print(f"totalMs={parsed['total_ms']}")
            print(f"keyword_ok={parsed['keyword_ok']}")
        if all_results:
            passed = sum(1 for r in all_results if r.get("keyword_ok"))
            totals = [r["total_ms"] for r in all_results if r.get("total_ms") is not None]
            print("\n--- summary ---")
            print(f"cases={len(all_results)} keyword_pass={passed} "
                  f"avg_total_ms={sum(totals) / len(totals) if totals else 0:.1f}")
        return 0

    if not args.wav:
        print("需要 --wav 或 --cases")
        return 1

    results = []
    for i in range(args.iterations):
        print(f"--- iteration {i + 1} ---")
        parsed = run_one(args.serial, args.wav, args.model, args.threads, args.warm)
        results.append(parsed)
        if "text" in parsed:
            print(f"result: {parsed['text']}")
        if "error" in parsed:
            print(f"error: {parsed['error']}")
        if "timing" in parsed and parsed["timing"]:
            print(parsed["timing"])
        if "total_ms" in parsed:
            print(f"totalMs={parsed['total_ms']}")
        if "warmup_ms" in parsed:
            print(f"warmupMs={parsed['warmup_ms']}")

    totals = [r.get("total_ms") for r in results if r.get("total_ms")]
    if totals:
        print(f"\n--- summary ---")
        print(f"runs={len(totals)} avg_total_ms={sum(totals) / len(totals):.1f} "
              f"min={min(totals)} max={max(totals)}")


if __name__ == "__main__":
    sys.exit(main())
