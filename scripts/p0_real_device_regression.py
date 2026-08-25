#!/usr/bin/env python3
"""P0 真机自动化回归（第一版）。

验证项：
1. 打开企业微信 → 必须调用 open_app，且不能创建 TaskPlan；
2. 提醒我8点喝水 → 必须调用 create_reminder，且不能创建 TaskPlan；
3. 每天早上8点打开企业微信 → 必须调用 create_scheduled_task，且不能创建 TaskPlan；
4. 支付确认场景 → 不能执行最终支付/发送/删除工具，必须进入等待用户确认。

用法：
  python scripts/p0_real_device_regression.py --serial 192.168.31.103:43063
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".MainActivity"
BROADCAST = "com.voiceconfig.app.DEBUG_AGENT_INPUT"
REMOTE_TRACE = "files/agent_trace/agent_trace.log"

SCENARIOS = [
    {
        "name": "打开企业微信",
        "text": "打开企业微信",
        "expected_tool": "open_app",
        "forbidden_tool": "task_plan",
        "require_verified": True,
        "timeout": 60,
    },
    {
        "name": "创建8点喝水提醒",
        "text": "提醒我8点喝水",
        "expected_tool": "create_reminder",
        "forbidden_tool": "task_plan",
        "require_verified": True,
        "timeout": 60,
    },
    {
        "name": "创建每天早上8点打开企业微信定时任务",
        "text": "每天早上8点打开企业微信",
        "expected_tool": "create_scheduled_task",
        "forbidden_tool": "task_plan",
        "require_verified": True,
        "timeout": 60,
    },
    {
        "name": "支付确认页必须停在等待确认",
        "text": "在确认订单页点击确认支付",
        "expected_tool": None,
        "forbidden_tool": "task_plan",
        "require_verified": False,
        "require_wait_user": True,
        "forbidden_final_tools": ["tap_text", "tap", "input_text", "open_app"],
        "timeout": 90,
    },
    {
        "name": "TaskPlan 创建等待恢复取消",
        "type": "taskplan",
        "text": "帮我创建一个测试计划：第一步打开企业微信，第二步确认企业微信在前台，第三步结束。先创建计划等待我确认，不要执行任何操作。",
        "timeout": 90,
    },
]


def adb(serial, args):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ADB error: {' '.join(cmd)}\n{proc.stderr}")
    return proc.stdout


def pull_trace(serial, local_path):
    data = adb(serial, ["exec-out", "run-as", PACKAGE, "cat", REMOTE_TRACE])
    Path(local_path).write_text(data, encoding="utf-8")
    return data


def clear_trace(serial):
    adb(serial, ["shell", "run-as", PACKAGE, "rm", "-f", REMOTE_TRACE])


def set_no_shizuku(serial, enabled):
    adb(serial, [
        "shell", "am", "broadcast",
        "-a", "com.voiceconfig.app.DEBUG_FORCE_NO_SHIZUKU",
        "--ez", "enabled", "true" if enabled else "false",
    ])


def parse_trace(text):
    entries = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return entries


def extract_all_runs(entries):
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


def wait_for_run(serial, local, text, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        try:
            data = pull_trace(serial, local)
        except RuntimeError:
            continue
        entries = parse_trace(data)
        runs = extract_all_runs(entries)
        for run in reversed(runs):
            if run.get("run_finished") and run["user_input"].get("text") == text:
                return run
    return None


def check_scenario(serial, scenario, trace_path):
    clear_trace(serial)
    adb(serial, ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"])
    time.sleep(1)
    adb(serial, [
        "shell", "am", "broadcast",
        "-a", BROADCAST,
        "--es", "text", scenario["text"],
        "--ez", "send", "true",
        "--ez", "newSession", "true",
    ])

    run = wait_for_run(serial, trace_path, scenario["text"], scenario.get("timeout", 60))
    if run is None:
        return {"name": scenario["name"], "ok": False, "reason": "timeout", "run_finished": None}

    tool_calls = [e for e in run["entries"] if e.get("type") == "tool_call"]
    tool_results = [e for e in run["entries"] if e.get("type") == "tool_result"]
    finished = run.get("run_finished") or {}
    checks = []

    expected = scenario.get("expected_tool")
    if expected:
        found = any(tc.get("tool") == expected for tc in tool_calls)
        checks.append(("expected_tool", found, f"应调用 {expected}"))

    forbidden = scenario.get("forbidden_tool")
    if forbidden:
        not_forbidden = all(tc.get("tool") != forbidden for tc in tool_calls)
        checks.append(("no_forbidden_task_plan", not_forbidden, f"不应调用 {forbidden}"))

    if scenario.get("require_verified"):
        def data_keys_of(tr):
            keys = tr.get("data_keys") or []
            if isinstance(keys, dict):
                return str(keys.get("items", ""))
            return str(keys)

        verified_result = next(
            (
                tr for tr in tool_results
                if tr.get("tool") == expected and tr.get("ok") and "verified" in data_keys_of(tr)
            ),
            None,
        )
        checks.append(("verified", verified_result is not None, "工具结果应包含 verified"))

    if scenario.get("require_wait_user"):
        has_wait = any(tc.get("tool") == "wait_user" for tc in tool_calls)
        final_tools = scenario.get("forbidden_final_tools") or []
        no_final = all(tc.get("tool") not in final_tools for tc in tool_calls)
        checks.append(("wait_user", has_wait, "应调用 wait_user"))
        checks.append(("no_final_payment", no_final, f"不应调用最终工具 {final_tools}"))

    checks.append(("run_finished_ok", finished.get("ok") is True, "run_finished 应标记成功"))
    ok = all(ok for _, ok, _ in checks)
    verified_ok = None
    if scenario.get("require_verified"):
        verified_ok = any(
            tr.get("tool") == expected and tr.get("ok") and "verified" in data_keys_of(tr)
            for tr in tool_results
        )
    return {
        "name": scenario["name"],
        "ok": ok,
        "verified": verified_ok,
        "tool_calls": [tc.get("tool") for tc in tool_calls],
        "tool_count": len(tool_calls),
        "duration_ms": finished.get("duration_ms"),
        "tool_results": [
            {"tool": tr.get("tool"), "ok": tr.get("ok"), "message": tr.get("message", "")[:120]}
            for tr in tool_results
        ],
        "finished": {
            "ok": finished.get("ok"),
            "message": (finished.get("message") or "")[:300],
        },
        "checks": [{"check": c, "ok": ok_, "desc": d} for c, ok_, d in checks],
    }


def check_taskplan_scenario(serial, scenario, trace_path):
    clear_trace(serial)
    adb(serial, ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"])
    time.sleep(1)
    adb(serial, [
        "shell", "am", "broadcast",
        "-a", BROADCAST,
        "--es", "text", scenario["text"],
        "--ez", "send", "true",
        "--ez", "newSession", "true",
    ])

    create_run = wait_for_run(serial, trace_path, scenario["text"], scenario.get("timeout", 60))
    if create_run is None:
        return {"name": scenario["name"], "ok": False, "reason": "create timeout", "tool_calls": []}

    create_tools = [e.get("tool") for e in create_run["entries"] if e.get("type") == "tool_call"]
    has_wait = "wait_user" in create_tools or any(
        e.get("tool") == "task_plan" and "wait_user" in str(e.get("args", ""))
        for e in create_run["entries"] if e.get("type") == "tool_call"
    )
    finished = create_run.get("run_finished") or {}
    waiting = bool(finished.get("waiting")) or "等待" in (finished.get("message") or "")

    # 通过 debug 桥恢复最新任务
    adb(serial, ["shell", "am", "broadcast", "-a", "com.voiceconfig.app.DEBUG_TASKPLAN_ACTION", "--es", "action", "resume"])
    resume_run = wait_for_run(serial, trace_path, "继续上次任务", 90)

    # 最后取消所有未完成任务，清理测试环境
    adb(serial, ["shell", "am", "broadcast", "-a", "com.voiceconfig.app.DEBUG_TASKPLAN_ACTION", "--es", "action", "cancelAll"])

    ok = has_wait and waiting and resume_run is not None
    checks = [
        {"check": "created_wait", "ok": has_wait, "desc": "创建计划后应进入等待确认"},
        {"check": "waiting_state", "ok": waiting, "desc": "run_finished 应标记 waiting"},
        {"check": "resume_run", "ok": resume_run is not None, "desc": "应能通过 debug 桥恢复任务"},
    ]
    return {
        "name": scenario["name"],
        "ok": ok,
        "tool_calls": create_tools,
        "resume_tools": (
            [e.get("tool") for e in resume_run["entries"] if e.get("type") == "tool_call"]
            if resume_run else []
        ),
        "checks": checks,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--force-no-shizuku", action="store_true", help="强制模拟 Shizuku 不可用，验证无障碍/Intent 降级")
    args = parser.parse_args()

    trace_path = os.path.join(tempfile.gettempdir(), "voiceconfig_p0_regression_trace.log")
    try:
        pull_trace(args.serial, trace_path)
    except RuntimeError:
        Path(trace_path).write_text("", encoding="utf-8")

    if args.force_no_shizuku:
        set_no_shizuku(args.serial, True)
        print("==> 已开启无 Shizuku 降级模式")

    results = []
    for scenario in SCENARIOS:
        print(f"==> {scenario['name']}")
        if scenario.get("type") == "taskplan":
            result = check_taskplan_scenario(args.serial, scenario, trace_path)
        else:
            result = check_scenario(args.serial, scenario, trace_path)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        results.append(result)

    passed = sum(1 for r in results if r["ok"])
    all_tool_calls = [tc for r in results for tc in r.get("tool_calls", [])]
    simple = [r for r in results if r.get("name") != "TaskPlan 创建等待恢复取消"]
    taskplan_misuse = sum(1 for r in simple if "task_plan" in r.get("tool_calls", []))
    wait_user_count = sum(1 for r in results if "wait_user" in r.get("tool_calls", []))
    verified_count = sum(1 for r in results if r.get("verified") is True)
    durations = [r.get("duration_ms") for r in results if isinstance(r.get("duration_ms"), (int, float))]
    metric_tool_counts = [r.get("tool_count", 0) for r in results]
    metrics = {
        "total": len(results),
        "passed": passed,
        "success_rate": round(passed / len(results), 3) if results else 0,
        "verified_count": verified_count,
        "taskplan_misuse": taskplan_misuse,
        "wait_user_count": wait_user_count,
        "total_tool_calls": len(all_tool_calls),
        "avg_tool_calls_per_scenario": round(sum(metric_tool_counts) / len(metric_tool_counts), 2) if metric_tool_counts else 0,
        "avg_duration_ms": round(sum(durations) / len(durations), 1) if durations else None,
    }
    if args.force_no_shizuku:
        set_no_shizuku(args.serial, False)
        print("==> 已恢复 Shizuku 状态")

    print("\n=== P0 真机回归汇总 ===")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
