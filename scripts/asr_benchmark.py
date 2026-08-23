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
import wave

try:
    from benchmark_store import BenchmarkStore
except ImportError:
    from scripts.benchmark_store import BenchmarkStore

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".AsrBenchmarkActivity"
BENCH_TAG = "AsrBenchmark"
TIMING_TAG = "VoiceConfigAsrTiming"
TRANSCRIBE_TAG = "TranscribeCppJni"
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


def launch_bench(serial, model_id, threads=None, warm=False, lang=None, provider=None):
    args = [
        "shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}",
        "--es", "wav", REMOTE_WAV,
        "--es", "model", model_id,
    ]
    if threads:
        args += ["--es", "threads", str(threads)]
    if warm:
        args += ["--ez", "warm", "true"]
    if lang:
        args += ["--es", "lang", lang]
    if provider:
        args += ["--es", "provider", provider]
    proc = adb(serial, args)
    if proc.returncode != 0:
        raise RuntimeError(f"launch failed: {proc.stderr}")


def collect_result(serial, wait_seconds=45):
    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        time.sleep(1)
        proc = adb(serial, ["logcat", "-d", "-s", BENCH_TAG, TIMING_TAG, TRANSCRIBE_TAG])
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
        if "TranscribeCppJni" in line:
            result["transcribe_timing"] = line
            m = re.search(r"loadMs=([0-9.]+) melMs=([0-9.]+) encodeMs=([0-9.]+) decodeMs=([0-9.]+)", line)
            if m:
                result["transcribe_load_ms"] = float(m.group(1))
                result["transcribe_mel_ms"] = float(m.group(2))
                result["transcribe_encode_ms"] = float(m.group(3))
                result["transcribe_decode_ms"] = float(m.group(4))
    return result


def load_cases(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["cases"]



def save_results(path, results):
    import json as _json
    with open(path, "w", encoding="utf-8") as f:
        _json.dump({"results": results}, f, ensure_ascii=False, indent=2)
    print(f"saved results to {path}")

def wav_duration_sec(path):
    try:
        with wave.open(path, "rb") as w:
            return w.getnframes() / float(w.getframerate())
    except Exception:
        return None


def collect_mem_pss_kb(serial):
    proc = adb(serial, ["shell", "dumpsys", "meminfo", PACKAGE])
    text = proc.stdout or ""
    m = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if m:
        return int(m.group(1))
    return None


def run_one(serial, wav_path, model_id, threads=None, warm=False, lang=None, provider=None):
    push_wav(serial, wav_path)
    clear_logcat(serial)
    launch_bench(serial, model_id, threads=threads, warm=warm, lang=lang, provider=provider)
    output = collect_result(serial)
    return parse_bench(output)


def check_keywords(text, keywords):
    if not keywords:
        return None
    lower = (text or "").lower()
    return all(k.lower() in lower for k in keywords)


def edit_distance(a, b):
    """Levenshtein distance (used for CER/WER)."""
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def cer(reference, hypothesis):
    if not reference:
        return 0.0 if not hypothesis else 1.0
    return edit_distance(reference, hypothesis) / len(reference)


def wer(reference, hypothesis):
    ref = (reference or "").split()
    hyp = (hypothesis or "").split()
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def check_regression_gate(results, args):
    """Return list of human-readable gate failures (empty = pass)."""
    failures = []
    # 只有带标注文本的用例才参与 CER/WER 门禁；8k/方言等无参考文本的用例不注入 1.0。
    cers = [r["cer"] for r in results if r.get("expected_text") and r.get("cer") is not None]
    rtf = [r["rtf"] for r in results if r.get("rtf") is not None]
    totals = [r["total_ms"] for r in results if r.get("total_ms") is not None]
    keyword_pass = sum(1 for r in results if r.get("keyword_ok"))

    if args.max_cer is not None:
        avg = sum(cers) / len(cers) if cers else None
        if avg is not None and avg > args.max_cer:
            failures.append(f"avg CER {avg:.3f} > max {args.max_cer}")
    if args.max_rtf is not None:
        avg = sum(rtf) / len(rtf) if rtf else None
        if avg is not None and avg > args.max_rtf:
            failures.append(f"avg RTF {avg:.3f} > max {args.max_rtf}")
    if args.max_avg_total_ms is not None:
        avg = sum(totals) / len(totals) if totals else None
        if avg is not None and avg > args.max_avg_total_ms:
            failures.append(f"avg totalMs {avg:.1f} > max {args.max_avg_total_ms}")
    if args.min_keyword_pass is not None and keyword_pass < args.min_keyword_pass:
        failures.append(f"keyword pass {keyword_pass}/{len(results)} < min {args.min_keyword_pass}")

    if failures:
        print("\n--- regression gate: FAIL ---")
        for f in failures:
            print("  " + f)
    else:
        print("\n--- regression gate: PASS ---")
    return failures


def apply_profile_defaults(args):
    """Load device-profile gate thresholds when the user does not override them."""
    if not args.profile:
        return
    path = args.profile_file or os.path.join(os.path.dirname(os.path.abspath(__file__)), "asr_device_profiles.json")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print(f"warning: cannot load device profile {path}: {e}")
        return
    profile = data.get("profiles", {}).get(args.profile)
    if not profile:
        print(f"warning: unknown device profile '{args.profile}'")
        return
    if args.abi is None:
        args.abi = profile.get("abi")
    gates = profile.get("gates", {})
    if args.max_cer is None:
        args.max_cer = gates.get("max_cer")
    if args.max_rtf is None:
        args.max_rtf = gates.get("max_rtf")
    if args.max_avg_total_ms is None:
        args.max_avg_total_ms = gates.get("max_avg_total_ms")
    if args.min_keyword_pass is None:
        args.min_keyword_pass = gates.get("min_keyword_pass")
    print(f"device profile '{args.profile}': abi={args.abi} gates cer<={args.max_cer} rtf<={args.max_rtf} avg_ms<={args.max_avg_total_ms} kw>={args.min_keyword_pass}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--wav", default=None, help="单个 WAV 路径；配合 --cases 时作为兜底")
    parser.add_argument("--cases", default=None, help="固定测试集 JSON")
    parser.add_argument("--wav-dir", default=None, help="固定测试集对应 WAV 目录")
    parser.add_argument("--model", default="sherpa-ctc-2025")
    parser.add_argument("--threads", type=int, default=None, help="覆盖 CPU 线程数")
    parser.add_argument("--warm", action="store_true", help="先预热再识别（验证 warm 路径）")
    parser.add_argument("--lang", default=None, help="Cohere Transcribe 语言代码，如 zh/en/de/ja")
    parser.add_argument("--provider", default=None, help="ONNX Runtime provider，如 cpu/xnnpack/nnapi/qnn")
    parser.add_argument("--iterations", type=int, default=1)
    parser.add_argument("--output", default=None, help="将结果保存为 JSON 文件")
    parser.add_argument("--bench-dir", default="benchmark_results", help="持久化目录（JSON + SQLite）")
    parser.add_argument("--git-sha", default=None, help="构建版本 Git SHA；默认自动获取")
    parser.add_argument("--build-id", default=None, help="构建编号 / 日期")
    parser.add_argument("--device-id", default=None, help="设备型号或标识，如 M2102K1C")
    parser.add_argument("--soc", default=None, help="SoC，如 SM8350")
    parser.add_argument("--abi", default=None, help="ABI，如 arm64-v8a")
    parser.add_argument("--profile", default=None, help="设备档案：arm64-flagship / arm64-midrange / emulator-x86_64")
    parser.add_argument("--profile-file", default=None, help="设备档案 JSON 路径；默认 scripts/asr_device_profiles.json")
    parser.add_argument("--notes", default=None, help="本次运行备注")
    parser.add_argument("--no-db", action="store_true", help="不写入 benchmark_results 数据库")
    parser.add_argument("--list", action="store_true", help="列出已保存的 benchmark 运行")
    parser.add_argument("--max-cer", type=float, default=None, help="回归门禁：平均 CER 上限")
    parser.add_argument("--max-rtf", type=float, default=None, help="回归门禁：平均 RTF 上限")
    parser.add_argument("--max-avg-total-ms", type=float, default=None, help="回归门禁：平均总耗时上限")
    parser.add_argument("--min-keyword-pass", type=int, default=None, help="回归门禁：最少关键词通过数")
    parser.add_argument("--fail-on-gate", action="store_true", help="门禁失败时以非零退出")
    args = parser.parse_args()

    if args.git_sha is None:
        try:
            args.git_sha = subprocess.check_output(
                ["git", "rev-parse", "--short", "HEAD"], stderr=subprocess.DEVNULL, text=True
            ).strip()
        except Exception:
            args.git_sha = None

    apply_profile_defaults(args)

    store = None if args.no_db else BenchmarkStore(args.bench_dir)
    if args.list:
        if store is not None:
            store.list_runs()
        return 0

    def make_meta():
        return {
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "serial": args.serial,
            "device_id": args.device_id,
            "soc": args.soc,
            "abi": args.abi,
            "profile": args.profile,
            "git_sha": args.git_sha,
            "build_id": args.build_id,
            "model": args.model,
            "threads": args.threads,
            "provider": args.provider,
            "warm": args.warm,
            "lang": args.lang,
            "iterations": args.iterations,
            "notes": args.notes,
        }

    if args.cases:
        cases = load_cases(args.cases)
        all_results = []
        for case in cases:
            case_id = case["id"]
            wav = case.get("wav") or case.get("file")
            if wav and args.wav_dir and not os.path.isabs(wav):
                wav = os.path.join(args.wav_dir, wav)
            if wav is None:
                if args.wav_dir:
                    candidate = os.path.join(args.wav_dir, f"{case_id}.wav")
                    if os.path.exists(candidate):
                        wav = candidate
            if wav is None:
                wav = args.wav
            if wav is None or not os.path.exists(wav):
                print(f"skip {case_id}: no wav file")
                continue
            print(f"--- {case_id} ---")
            parsed = run_one(args.serial, wav, args.model, args.threads, args.warm, args.lang, args.provider)
            parsed["case_id"] = case_id
            parsed["expected_text"] = case.get("text")
            parsed["keywords"] = case.get("keywords", [])
            parsed["keyword_ok"] = check_keywords(parsed.get("text"), case.get("keywords", []))
            parsed["cer"] = cer(case.get("text", ""), parsed.get("text", ""))
            parsed["wer"] = wer(case.get("text", ""), parsed.get("text", ""))
            duration = wav_duration_sec(wav)
            parsed["audio_duration_sec"] = duration
            if parsed.get("total_ms") is not None and duration:
                parsed["rtf"] = parsed["total_ms"] / 1000.0 / duration
            parsed["mem_pss_kb"] = collect_mem_pss_kb(args.serial)
            all_results.append(parsed)
            print(f"result: {parsed.get('text', parsed.get('error', 'NO_RESULT'))}")
            if parsed.get("timing"):
                print(parsed["timing"])
            if parsed.get("transcribe_timing"):
                print(parsed["transcribe_timing"])
            if parsed.get("total_ms") is not None:
                print(f"totalMs={parsed['total_ms']}")
            print(f"keyword_ok={parsed['keyword_ok']}")
            print(f"cer={parsed['cer']:.3f} wer={parsed['wer']:.3f}")
            if parsed.get("rtf") is not None:
                print(f"rtf={parsed['rtf']:.2f}")
            if parsed.get("mem_pss_kb") is not None:
                print(f"mem_pss_kb={parsed['mem_pss_kb']}")
        if all_results:
            passed = sum(1 for r in all_results if r.get("keyword_ok"))
            totals = [r["total_ms"] for r in all_results if r.get("total_ms") is not None]
            print("\n--- summary ---")
            print(f"cases={len(all_results)} keyword_pass={passed} "
                  f"avg_total_ms={sum(totals) / len(totals) if totals else 0:.1f}")
        if args.output:
            save_results(args.output, all_results)
        if store is not None:
            meta = make_meta()
            if args.cases:
                meta["cases_file"] = args.cases
            if args.wav_dir:
                meta["wav_dir"] = args.wav_dir
            store.save_run(meta, all_results)
        if all_results:
            gate_failures = check_regression_gate(all_results, args)
            if args.fail_on_gate and gate_failures:
                return 2
        return 0

    if not args.wav:
        print("需要 --wav 或 --cases")
        return 1

    results = []
    for i in range(args.iterations):
        print(f"--- iteration {i + 1} ---")
        parsed = run_one(args.serial, args.wav, args.model, args.threads, args.warm, args.lang, args.provider)
        duration = wav_duration_sec(args.wav)
        parsed["audio_duration_sec"] = duration
        if parsed.get("total_ms") is not None and duration:
            parsed["rtf"] = parsed["total_ms"] / 1000.0 / duration
        parsed["mem_pss_kb"] = collect_mem_pss_kb(args.serial)
        results.append(parsed)
        if "text" in parsed:
            print(f"result: {parsed['text']}")
        if "error" in parsed:
            print(f"error: {parsed['error']}")
        if "timing" in parsed and parsed["timing"]:
            print(parsed["timing"])
        if parsed.get("transcribe_timing"):
            print(parsed["transcribe_timing"])
        if "total_ms" in parsed:
            print(f"totalMs={parsed['total_ms']}")
        if "warmup_ms" in parsed:
            print(f"warmupMs={parsed['warmup_ms']}")
        if parsed.get("rtf") is not None:
            print(f"rtf={parsed['rtf']:.2f}")
        if parsed.get("mem_pss_kb") is not None:
            print(f"mem_pss_kb={parsed['mem_pss_kb']}")

    totals = [r.get("total_ms") for r in results if r.get("total_ms")]
    if totals:
        print(f"\n--- summary ---")
        print(f"runs={len(totals)} avg_total_ms={sum(totals) / len(totals):.1f} "
              f"min={min(totals)} max={max(totals)}")


    if args.output:
        save_results(args.output, results)
    if store is not None:
        meta = make_meta()
        meta["wav"] = args.wav
        store.save_run(meta, results)

if __name__ == "__main__":
    sys.exit(main())
