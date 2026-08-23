#!/usr/bin/env python3
"""异步 Agent 场景监控脚本。

用法:
  python scripts/agent_monitor.py \
    --serial 100.101.16.52:38773 \
    --text "帮我在瑞幸咖啡点一杯冰美式，到店取" \
    --expect "美式" \
    --stop-packages com.lucky.luckyclient \
    --timeout 60 \
    --out-dir "$TEMP/vc_monitor_luckin"

功能:
- 启动前先 kill 目标 App（让冷启动弹窗重新出现）
- 启动言控并发送 Agent 指令
- 每 3 秒拉取 trace 并保存截图
- 检测到期望文本立即成功结束（从 Agent 自身 tool_result 中判断，避免外部 uiautomator 与 Agent 冲突）
- 检测到卡死/长时间无进展提前结束
- 结束后只 stop VoiceConfig，不杀目标 App
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
REMOTE_UI_DUMP = "/sdcard/window_dump.xml"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True)


def stop_packages(serial, packages):
    for pkg in packages:
        adb(serial, ["shell", "am", "force-stop", pkg])
        time.sleep(0.2)


def launch_app_wait(serial):
    adb(serial, ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"])
    time.sleep(2)


def send_scenario(serial, text):
    adb(serial, [
        "shell", "am", "broadcast",
        "-a", BROADCAST,
        "--es", "text", text,
        "--ez", "send", "true",
        "--ez", "newSession", "true",
    ])


def pull_trace(serial):
    proc = adb(serial, ["exec-out", "run-as", PACKAGE, "cat", REMOTE_TRACE])
    return proc.stdout if proc.returncode == 0 else ""


def parse_entries(text):
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


def get_ui_text(serial):
    """返回当前界面可见文本列表（通过 uiautomator dump + pull）。"""
    local = os.path.join(tempfile.gettempdir(), "vc_monitor_ui.xml")
    try:
        adb(serial, ["shell", "uiautomator", "dump", REMOTE_UI_DUMP])
        proc = adb(serial, ["pull", REMOTE_UI_DUMP, local])
        if proc.returncode != 0:
            return []
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
        return parts
    except Exception:
        return []


def get_foreground_package(serial):
    proc = adb(serial, ["shell", "dumpsys", "activity", "activities"])
    if proc.returncode != 0:
        return None
    text = proc.stdout or ""
    m = None
    for pat in [r"topResumedActivity=.*?\s([A-Za-z0-9_.]+)/", r"mResumedActivity=.*?\s([A-Za-z0-9_.]+)/"]:
        m = __import__("re").search(pat, text)
        if m:
            return m.group(1)
    return None


def save_screenshot(serial, out_dir, index):
    path = os.path.join(out_dir, f"screenshot_{index:03d}.png")
    remote = "/sdcard/vc_monitor_screenshot.png"
    try:
        adb(serial, ["shell", "screencap", "-p", remote])
        proc = adb(serial, ["pull", remote, path])
        return path if proc.returncode == 0 and os.path.exists(path) else None
    except Exception:
        return None


def check_repeat(actions, max_repeats=6):
    if len(actions) < max_repeats:
        return False
    recent = actions[-max_repeats:]
    return len(set(recent)) == 1


UI_TOOLS = {"get_screen_state", "read_ui", "read_screen"}


def trace_contains_expect(entries, expects):
    """从 Agent 自身读取 UI 的 tool_result 中查找期望文本。

    只检查真正的读屏工具，避免 open_app 的“已打开并确认”等普通工具结果误触发成功。
    不要额外调用 adb uiautomator dump，避免和 Agent 内部的 uiautomator/Shizuku
    争夺唯一的 UiAutomationService，导致 exit=137 / already registered。
    """
    for e in entries:
        if e.get("type") != "tool_result" or e.get("tool") not in UI_TOOLS:
            continue
        msg = e.get("message") or ""
        for exp in expects:
            if exp in msg:
                return exp
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--text", required=True)
    parser.add_argument("--expect", default=None, help="出现该文本即认为成功")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--stop-packages", default="", help="运行前 kill 的包名，逗号分隔")
    parser.add_argument("--clear-data", default="", help="运行前清空数据的包名，逗号分隔（冷启动更彻底）")
    parser.add_argument("--out-dir", default=None)
    args = parser.parse_args()

    expects = [e.strip() for e in (args.expect or "").split(",") if e.strip()]
    out_dir = args.out_dir or os.path.join(tempfile.gettempdir(), "vc_monitor")
    os.makedirs(out_dir, exist_ok=True)
    log_path = os.path.join(out_dir, "monitor.log")
    result_path = os.path.join(out_dir, "result.json")

    def log(msg):
        line = f"[{time.strftime('%H:%M:%S')}] {msg}"
        print(line, flush=True)
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    stop_packages(args.serial, [PACKAGE] + [p for p in args.stop_packages.split(",") if p.strip()])
    for pkg in [p for p in args.clear_data.split(",") if p.strip()]:
        adb(args.serial, ["shell", "pm", "clear", pkg])
    launch_app_wait(args.serial)

    before = pull_trace(args.serial)
    before_count = len(before.splitlines())
    start_time = time.time()
    send_scenario(args.serial, args.text)
    log(f"已发送指令: {args.text}")
    log(f"输出目录: {out_dir}")

    prompt_marker = time.time() + 3
    deadline = time.time() + args.timeout
    last_tool_count = 0
    last_llm_count = 0
    last_round_count = 0
    pending_llm = False
    last_progress_time = time.time()
    last_actions = []
    screenshot_index = 0
    result = {
        "ok": False,
        "reason": "",
        "duration_sec": None,
        "expect": expects,
        "out_dir": out_dir,
    }

    try:
        while time.time() < deadline:
            time.sleep(3)
            now = time.time()

            trace_text = pull_trace(args.serial)
            entries = parse_entries(trace_text)
            with open(os.path.join(out_dir, "trace_latest.jsonl"), "w", encoding="utf-8") as f:
                f.write(trace_text)
            new_entries = entries[before_count:]
            tool_calls = [e for e in new_entries if e.get("type") == "tool_call"]
            llm_requests = [e for e in new_entries if e.get("type") == "llm_request"]
            llm_responses = [e for e in new_entries if e.get("type") == "llm_response"]
            round_timings = [e for e in new_entries if e.get("type") == "round_timing"]
            finished = [e for e in new_entries if e.get("type") == "run_finished"]
            run_starts = [e for e in new_entries if e.get("type") == "run_start"]
            if llm_requests:
                pending_llm = True
            if llm_responses:
                pending_llm = False

            if run_starts:
                last_actions = [e.get("tool", "") for e in tool_calls]
                if len(tool_calls) != last_tool_count or len(llm_responses) != last_llm_count or len(round_timings) != last_round_count:
                    last_tool_count = len(tool_calls)
                    last_llm_count = len(llm_responses)
                    last_round_count = len(round_timings)
                    last_progress_time = now
                    log(f"进展: {last_tool_count} 个工具调用, LLM响应 {last_llm_count}, 轮次 {last_round_count}, 最近: {last_actions[-3:] if last_actions else '-'}")

                if now - prompt_marker > 3 and last_tool_count == 0:
                    # 已等待超过3秒仍没有任何工具调用
                    pass

                if finished:
                    last = finished[-1]
                    log(f"Agent 已结束: ok={last.get('ok')} msg={last.get('message')}")
                    result.update(ok=bool(last.get("ok")), reason=last.get("message", ""))
                    break

                # 期望文本检测：必须确认前台是目标 App，且至少已经进行过实际交互
                # 注意：不再调用外部 uiautomator dump，避免与 Agent 读屏冲突。
                if expects and len(tool_calls) >= 3:
                    fg = get_foreground_package(args.serial)
                    hit = trace_contains_expect(new_entries, expects)
                    if fg and hit:
                        log(f"✅ 前台={fg} Agent 读屏结果中出现期望文本: {hit}")
                        shot = save_screenshot(args.serial, out_dir, screenshot_index)
                        if shot:
                            screenshot_index += 1
                            log(f"成功截图已保存: {shot}")
                        result.update(ok=True, reason=f"trace tool_result contains {hit}, fg={fg}")
                        break

                # 截图（每12秒一张，避免过多）
                if now - prompt_marker > 0 and int(now) % 12 == 0 and screenshot_index < 20:
                    path = save_screenshot(args.serial, out_dir, screenshot_index)
                    if path:
                        screenshot_index += 1
                        log(f"截图已保存: {path}")

                # 卡死检测：15秒没有新进展（LLM正在思考时不判卡死）
                if now - last_progress_time > 15 and tool_calls and not pending_llm:
                    log(f"⚠️ 已 {int(now - last_progress_time)} 秒没有新工具调用，提前结束")
                    shot = save_screenshot(args.serial, out_dir, screenshot_index)
                    if shot:
                        screenshot_index += 1
                        log(f"卡死截图已保存: {shot}")
                    result.update(reason="stalled_no_new_tool_calls")
                    break

                # 重复动作检测
                if len(last_actions) >= 8 and check_repeat(last_actions[-6:]):
                    log(f"⚠️ 检测到重复动作，提前结束: {last_actions[-6:]}")
                    result.update(reason="repeated_actions")
                    break
            else:
                if now - prompt_marker > 6:
                    log("还未检测到新的 Agent run，等待中...")
                    prompt_marker = now
        else:
            result["reason"] = "timeout"
            log(f"⏱️ 超时 {args.timeout}s")

        result["duration_sec"] = round(time.time() - start_time, 1)
    finally:
        # 默认只停止言控自身，不再强杀目标 App，避免影响用户使用和登录状态。
        stop_packages(args.serial, [PACKAGE])
        with open(result_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        log(f"监控结束, result={result_path}")

    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
