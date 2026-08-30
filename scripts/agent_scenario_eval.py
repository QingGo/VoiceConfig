#!/usr/bin/env python3
"""VoiceConfig Agent 场景 Replay / 自动化评测框架。

用法示例:

  # 运行一个场景（需要通过 ADB 连接真机/模拟器）
  python scripts/agent_scenario_eval.py --serial emulator-5554 run --text "帮我打开设置"

  # 运行一组场景
  python scripts/agent_scenario_eval.py --serial emulator-5554 suite \
      --scenarios scenarios.json

  # 分析本地已拉取的 trace
  python scripts/agent_scenario_eval.py analyze --trace agent_trace.log

  # 从设备拉取 trace 并分析最近运行
  python scripts/agent_scenario_eval.py --serial emulator-5554 pull --analyze

scenarios.json 格式:
[
  {"name": "打开设置", "text": "帮我打开设置", "expect": "设置", "timeout": 60}
]
"""

import argparse
import collections
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".MainActivity"
REMOTE_TRACE = "files/agent_trace/agent_trace.log"
BROADCAST = "com.voiceconfig.app.DEBUG_AGENT_INPUT"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ADB error: {' '.join(cmd)}\n{proc.stderr}")
    return proc.stdout


def pull_trace(serial, local_path):
    """从应用私有目录拉取 agent_trace.log。"""
    data = adb(serial, ["exec-out", "run-as", PACKAGE, "cat", REMOTE_TRACE])
    Path(local_path).write_text(data, encoding="utf-8")
    return local_path


def get_ui_text(serial):
    """拉取当前 uiautomator dump，返回所有可见文本 + contentDescription。"""
    dump_remote = "/sdcard/window_dump.xml"
    local = os.path.join(tempfile.gettempdir(), "voiceconfig_ui_dump.xml")
    try:
        adb(serial, ["shell", "uiautomator", "dump", dump_remote])
        adb(serial, ["pull", dump_remote, local])
    except RuntimeError:
        return ""
    try:
        import xml.etree.ElementTree as ET
        root = ET.parse(local).getroot()
        parts = []
        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            desc = node.attrib.get("content-desc", "")
            if text:
                parts.append(text)
            if desc:
                parts.append(desc)
        return chr(10).join(parts)
    except Exception:
        return ""


def verify_expected(serial, expected):
    """场景级验证：目标关键词必须出现在当前界面 UI 中。"""
    if not expected:
        return None
    text = get_ui_text(serial)
    if not text:
        return False
    return expected.lower() in text.lower()


def foreground_package(serial):
    """返回当前前台 Activity 的包名。"""
    out = adb(serial, ["shell", "dumpsys", "activity", "activities"])
    m = re.search(r"topResumedActivity=ActivityRecord\{[^}]* u0 (\S+)", out)
    if m:
        return m.group(1).split("/")[0]
    m = re.search(r"ResumedActivity: ActivityRecord\{[^}]* u0 (\S+)", out)
    if m:
        return m.group(1).split("/")[0]
    return ""


def verify_scenario(serial, scenario):
    """严格场景断言：前台包名 / 关键 UI 文本 / 不允许出现的浮层文本。"""
    checks = []
    ok = True

    expected_fg = scenario.get("expectedForeground")
    if expected_fg:
        actual_fg = foreground_package(serial)
        passed = actual_fg == expected_fg
        checks.append({"type": "foreground", "expected": expected_fg, "actual": actual_fg, "ok": passed})
        ok = ok and passed

    terminal_texts = scenario.get("terminalText") or []
    if terminal_texts:
        ui = get_ui_text(serial)
        missing = [t for t in terminal_texts if t.lower() not in ui.lower()]
        passed = not missing
        checks.append({"type": "terminal_text", "expected": terminal_texts, "missing": missing, "ok": passed})
        ok = ok and passed

    absent_texts = scenario.get("absentText") or []
    if absent_texts:
        ui = get_ui_text(serial)
        present = [t for t in absent_texts if t.lower() in ui.lower()]
        passed = not present
        checks.append({"type": "absent_text", "expected": absent_texts, "present": present, "ok": passed})
        ok = ok and passed

    return {"scenarioVerified": ok, "scenarioChecks": checks}


def launch_app(serial):
    adb(serial, ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"])
    # 动态注册的 debug receiver 在 MainActivity onCreate 中完成；等待 Activity 真正起来再广播。
    time.sleep(1.0)


def send_scenario(serial, text, new_session=True, pre_stop_packages=()):
    """通过 debug broadcast 发送一个 Agent 场景。"""
    if pre_stop_packages:
        stop_packages(serial, pre_stop_packages)
    launch_app(serial)
    args = [
        "shell", "am", "broadcast",
        "-a", BROADCAST,
        "--es", "text", text,
        "--ez", "send", "true",
        "--ez", "newSession", "true" if new_session else "false",
    ]
    adb(serial, args)


def parse_trace(path):
    entries = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return entries


def extract_runs(entries):
    """从 trace 中切分出每次用户输入到 run_finished 的片段。"""
    runs = []
    current = None
    for entry in entries:
        etype = entry.get("type")
        if etype == "user_input":
            if current is not None:
                runs.append(current)
            current = {"user_input": entry, "entries": []}
        elif current is not None and etype == "run_finished":
            current["run_finished"] = entry
            runs.append(current)
            current = None
        elif current is not None:
            current["entries"].append(entry)
    if current is not None:
        runs.append(current)
    return runs


def summarize_run(run):
    tool_calls = [e for e in run["entries"] if e.get("type") == "tool_call"]
    tool_results = [e for e in run["entries"] if e.get("type") == "tool_result"]
    declines = [e for e in run["entries"] if e.get("type") == "tool_declined"]
    finished = run.get("run_finished") or {}
    ok = bool(finished.get("ok"))
    message = finished.get("message", "")
    result = {
        "input": run["user_input"].get("text", ""),
        "ok": ok,
        "message": message,
        "tool_count": len(tool_calls),
        "failed_tools": [e.get("tool") for e in tool_results if not e.get("ok")],
        "declined": len(declines),
        "duration_ms": finished.get("duration_ms"),
    }
    result["failure_category"] = classify_failure(result)
    result["waiting"] = bool(finished.get("waiting"))
    return result


def classify_failure(summary):
    """把失败尽量归类，便于后续按原因修复。"""
    if summary.get("ok"):
        return "PASS"
    message = (summary.get("message") or "")
    if "超时" in message:
        return "TIMEOUT"
    if "重复" in message:
        return "REPEAT_LOOP"
    if summary.get("declined", 0) > 0:
        return "SAFETY_BLOCK"
    if summary.get("failed_tools"):
        return "TOOL_FAILURE"
    return "TARGET_NOT_ACHIEVED"


def analyze_trace(path):
    entries = parse_trace(path)
    runs = extract_runs(entries)
    summaries = [summarize_run(r) for r in runs]
    return summaries


def compute_metrics(summaries):
    total = len(summaries)
    passed = sum(1 for r in summaries if r["ok"])
    avg_steps = round(sum(r["tool_count"] for r in summaries) / total, 2) if total else 0
    failed_reasons = collections.Counter()
    declined = 0
    for r in summaries:
        if not r["ok"]:
            reason = (r.get("message") or "unknown")[:200]
            failed_reasons[reason] += 1
        declined += r.get("declined", 0)
    return {
        "total": total,
        "passed": passed,
        "success_rate": round(passed / total * 100, 1) if total else 0,
        "avg_tool_count": avg_steps,
        "avg_duration_ms": round(sum(r.get("duration_ms") or 0 for r in summaries) / total, 1) if total else 0,
        "declined_sensitive_count": declined,
        "human_intervention_rate": round(declined / total * 100, 1) if total else 0,
        "top_failure_reasons": [{"reason": k, "count": v} for k, v in failed_reasons.most_common(10)],
    }


def stop_app(serial):
    try:
        adb(serial, ["shell", "am", "force-stop", PACKAGE])
    except Exception:
        pass


def stop_packages(serial, packages):
    for pkg in packages:
        try:
            adb(serial, ["shell", "am", "force-stop", pkg])
        except Exception:
            pass


def run_and_evaluate(serial, text, timeout=90, expected=None, pre_stop_packages=(), scenario=None):
    local = os.path.join(tempfile.gettempdir(), "voiceconfig_agent_trace_eval.log")
    # 每次运行前拉取当前设备最新 trace 作为基线，避免跨设备临时文件污染。
    try:
        pull_trace(serial, local)
    except RuntimeError:
        pass
    before = Path(local).read_text(encoding="utf-8") if Path(local).exists() else ""
    before_count = len(before.splitlines())
    try:
        send_scenario(serial, text, pre_stop_packages=pre_stop_packages)
        # 等待执行结束：轮询 run_finished 数量增加。
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(3)
            try:
                data = pull_trace(serial, local)
            except RuntimeError:
                continue
            entries = parse_trace(data)
            if len(entries) > before_count:
                # 找到本次 user_input 到 run_finished
                new_entries = entries[before_count:]
                runs = extract_runs(new_entries)
                if runs and runs[-1].get("run_finished") is not None:
                    result = summarize_run(runs[-1])
                    if expected:
                        verified = verify_expected(serial, expected)
                        result["expected"] = expected
                        result["verified"] = verified
                        result["ok"] = result["ok"] and bool(verified)
                        result["verification"] = "PASS" if verified else "FAIL"
                        if not verified:
                            result["failure_category"] = "VERIFY_FAIL"
                    else:
                        result["verification"] = "NO_EXPECTED"
                    if scenario:
                        extra = verify_scenario(serial, scenario)
                        result.update(extra)
                        if scenario.get("requireWaiting") and not result.get("waiting"):
                            result["ok"] = False
                            result.setdefault("scenarioChecks", []).append(
                                {"type": "waiting", "expected": True, "actual": False, "ok": False}
                            )
                            result["scenarioVerified"] = False
                            result["failure_category"] = "SCENARIO_VERIFY_FAIL"
                        if not extra.get("scenarioVerified", False):
                            result["ok"] = False
                            result["failure_category"] = "SCENARIO_VERIFY_FAIL"
                    return result
        return {"input": text, "ok": False, "message": "timeout", "tool_count": 0, "failed_tools": [], "declined": 0, "verification": "TIMEOUT"}
    finally:
        # 部分 MIUI 设备 force-stop 后无障碍服务会掉线；设置 VC_KEEP_APP=1
        # 可保留 App 进程，用于连续真实场景回归。
        if os.environ.get("VC_KEEP_APP") != "1":
            stop_app(serial)


def load_scenarios(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None, help="ADB serial")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="运行单个场景")
    p_run.add_argument("--text", required=True)
    p_run.add_argument("--timeout", type=int, default=90)
    p_run.add_argument("--expect", default=None, help="场景级验证关键词")
    p_run.add_argument("--stop-packages", default=None, help="运行前强制停止的包名，逗号分隔，例如 com.lucky.luckyclient")

    p_suite = sub.add_parser("suite", help="运行一组场景")
    p_suite.add_argument("--scenarios", required=True)
    p_suite.add_argument("--timeout", type=int, default=90)
    p_suite.add_argument("--stop-packages", default=None, help="运行前强制停止的包名，逗号分隔")

    p_analyze = sub.add_parser("analyze", help="分析本地 trace")
    p_analyze.add_argument("--trace", required=True)

    p_metrics = sub.add_parser("metrics", help="分析 trace 并输出汇总指标")
    p_metrics.add_argument("--trace", required=True)

    p_pull = sub.add_parser("pull", help="从设备拉取 trace")
    p_pull.add_argument("--output", default=None, help="输出文件路径，默认临时目录")
    p_pull.add_argument("--analyze", action="store_true")

    args = parser.parse_args()

    if args.command == "run":
        pre_stop = tuple(p for p in (args.stop_packages or "").split(",") if p.strip())
        result = run_and_evaluate(args.serial, args.text, args.timeout, args.expect, pre_stop)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["ok"] else 1

    if args.command == "suite":
        scenarios = load_scenarios(args.scenarios)
        stop_default = tuple(p for p in (args.stop_packages or "").split(",") if p.strip())
        results = []
        for sc in scenarios:
            print(f"==> {sc.get('name', sc['text'])}")
            pre_stop = tuple(sc.get("preStop", [])) or stop_default
            result = run_and_evaluate(args.serial, sc["text"], sc.get("timeout", args.timeout), sc.get("expect"), pre_stop, scenario=sc)
            result["name"] = sc.get("name", sc["text"])
            if "expected" not in result:
                result["expected"] = sc.get("expect")
            results.append(result)
        ok_count = sum(1 for r in results if r["ok"])
        print("\n=== 汇总 ===")
        print(json.dumps({"total": len(results), "passed": ok_count, "results": results}, ensure_ascii=False, indent=2))
        return 0 if ok_count == len(results) else 1

    if args.command == "analyze":
        summaries = analyze_trace(args.trace)
        print(json.dumps(summaries, ensure_ascii=False, indent=2))
        return 0

    if args.command == "metrics":
        summaries = analyze_trace(args.trace)
        metrics = compute_metrics(summaries)
        print(json.dumps(metrics, ensure_ascii=False, indent=2))
        return 0

    if args.command == "pull":
        if not args.serial:
            print("pull 需要 --serial")
            return 1
        output = args.output or os.path.join(tempfile.gettempdir(), "voiceconfig_agent_trace_eval.log")
        path = pull_trace(args.serial, output)
        print(f"pulled to {path}")
        if args.analyze:
            summaries = analyze_trace(path)
            print(json.dumps(summaries, ensure_ascii=False, indent=2))
        return 0


if __name__ == "__main__":
    sys.exit(main())
