#!/usr/bin/env python3
"""Verify Shizuku execution foreground-activity verification on a real device.

This script:
  1. Reads the app database to find an OPEN_APP task (optionally filtered by package).
  2. Opens the app UI, opens that task's overflow menu, and taps "立即执行".
  3. Waits for execution, then reads the latest execution log.
  4. PASSes only if the log message contains "已验证前台为 <targetPackage>".

Usage:
    python scripts/check_execution_verification.py [adb_serial] [--package com.tencent.wework]
Example:
    python scripts/check_execution_verification.py <adb-serial> --package com.tencent.wework
"""

import argparse
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.voiceconfig.app"
ACTIVITY = ".MainActivity"
DB_NAME = "voice_config.db"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        print("ADB error:", " ".join(cmd))
        print(proc.stderr)
        sys.exit(1)
    return proc.stdout


def pull_db(serial):
    """Copy Room DB + WAL + SHM out of the app sandbox and open read-only."""
    # Use a fresh temp dir per pull: Room's -shm/-wal files are locked by an
    # already-open sqlite connection on Windows, so reusing a dir can fail.
    tmpdir = tempfile.mkdtemp(prefix="voiceconfig_exec_check_")
    files = {}
    for suffix in ["", "-wal", "-shm"]:
        remote = f"databases/{DB_NAME}{suffix}"
        local = os.path.join(tmpdir, DB_NAME + suffix)
        # exec-out avoids CRLF translation that corrupts binary files.
        with open(local, "wb") as fh:
            proc = subprocess.run(
                ["adb"] + (["-s", serial] if serial else []) +
                ["exec-out", "run-as", PACKAGE, "cat", remote],
                stdout=fh,
                stderr=subprocess.PIPE,
            )
            if proc.returncode != 0:
                print(f"Failed to pull {remote}: {proc.stderr.decode(errors='replace')}")
                return None
        files[suffix] = local
    return sqlite3.connect(files[""])


def get_open_app_tasks(con, package_filter=None):
    cur = con.cursor()
    cur.execute(
        "SELECT id, title, rawText, targetPackage, targetActivity, executionMode, "
        "scheduleType, time, date, daysOfWeek, intervalMinutes "
        "FROM tasks WHERE actionType='OPEN_APP' ORDER BY id"
    )
    rows = cur.fetchall()
    if package_filter:
        rows = [r for r in rows if r[3] == package_filter]
    return rows


def format_schedule_text(row):
    """Return the schedule text shown in the UI, mirroring MainActivity.formatScheduleText."""
    _, _, _, _, _, _, sched_type, time_s, date_s, days_s, interval_min = row
    time_text = time_s if time_s else ""
    if sched_type == "DAILY":
        return f"每天 {time_text}"
    if sched_type == "WEEKLY":
        day_names = []
        if days_s:
            days = [d.strip() for d in days_s.split(",") if d.strip()]
            workdays = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"}
            if days and set(days) == workdays:
                return f"每周工作日 {time_text}"
            if len(days) == 7:
                return f"每天 {time_text}"
            short = {"MONDAY": "周一", "TUESDAY": "周二", "WEDNESDAY": "周三", "THURSDAY": "周四", "FRIDAY": "周五", "SATURDAY": "周六", "SUNDAY": "周日"}
            day_names = [short.get(d, d) for d in days]
        if day_names:
            return f"每周{''.join(day_names)} {time_text}"
        return f"每周 {time_text}"
    if sched_type == "ONCE":
        date_text = date_s or "今天"
        return f"{date_text} {time_text}"
    if sched_type == "INTERVAL":
        if interval_min and int(interval_min) % 60 == 0:
            return f"每 {int(interval_min) // 60} 小时"
        return f"每 {interval_min} 分钟"
    return time_text


def get_ui_root(serial):
    adb(serial, ["shell", "uiautomator", "dump", "/sdcard/voiceconfig_exec_ui.xml"])
    out = adb(serial, ["shell", "cat", "/sdcard/voiceconfig_exec_ui.xml"])
    start = out.find("<?xml")
    if start < 0:
        raise RuntimeError("uiautomator dump returned no XML")
    return ET.fromstring(out[start:])


def node_bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2


def center(bounds):
    x1, y1, x2, y2 = bounds
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_nodes_with_text(root, text):
    return [n for n in root.iter("node") if n.get("text", "").strip() == text]


def find_clickable_overlapping(root, y_center, min_x=900):
    """Return rightmost clickable node whose vertical span contains y_center."""
    candidates = []
    for n in root.iter("node"):
        if n.get("clickable", "") != "true":
            continue
        b = node_bounds(n)
        if not b:
            continue
        x1, y1, x2, y2 = b
        if y1 <= y_center <= y2 and x1 >= min_x:
            candidates.append((x1, x2, n))
    if not candidates:
        return None
    candidates.sort(key=lambda t: t[1])  # rightmost by x2
    return candidates[-1][2]


def tap(serial, x, y):
    adb(serial, ["shell", "input", "tap", str(x), str(y)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("serial", nargs="?", default=None)
    parser.add_argument("--package", default=None, help="Filter task by targetPackage, e.g. com.tencent.wework")
    parser.add_argument("--task-id", type=int, default=None, help="Exact task id to execute")
    parser.add_argument("--timeout", type=float, default=8.0, help="Seconds to wait for execution")
    args = parser.parse_args()

    con = pull_db(args.serial)
    if con is None:
        print("FAIL: could not read app database")
        sys.exit(1)

    tasks = get_open_app_tasks(con, args.package)
    if args.task_id is not None:
        tasks = [t for t in tasks if t[0] == args.task_id]
    if not tasks:
        print("FAIL: no matching OPEN_APP task found in database")
        print("Tasks found:", get_open_app_tasks(con, None))
        sys.exit(1)

    task = tasks[0]
    task_id, title, raw_text, target_pkg, _, _, _, _, _, _, _ = task
    # The UI shows a generated short title, e.g. "打开企业微信", not the raw text.
    display_names = {
        "com.tencent.wework": "企业微信",
        "com.tencent.mm": "微信",
        "com.ss.android.lark": "飞书",
        "com.alibaba.android.rimet": "钉钉",
        "com.eg.android.AlipayGphone": "支付宝",
    }
    ui_title = "打开" + display_names.get(target_pkg, target_pkg or "页面")
    print(f"Target task: id={task_id}, title={title!r}, package={target_pkg}, ui_title={ui_title!r}")

    print("Launching app UI...")
    adb(args.serial, ["shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}"])
    time.sleep(3)

    # Find the task row by matching both the short title and the schedule text.
    root = get_ui_root(args.serial)
    schedule_text = format_schedule_text(task)
    print(f"Looking for UI row: title={ui_title!r}, schedule={schedule_text!r}")
    all_text_nodes = [n for n in root.iter("node") if n.get("text", "").strip()]
    title_node = None
    for i, n in enumerate(all_text_nodes):
        if n.get("text", "").strip() != ui_title:
            continue
        # The schedule text is normally the next text node in the same card.
        if i + 1 < len(all_text_nodes) and all_text_nodes[i + 1].get("text", "").strip() == schedule_text:
            title_node = n
            break
    if title_node is None:
        # Give a clear hint when the device is locked (UI automation cannot unlock it).
        lock_hints = ["Draw pattern", "EMERGENCY", "Face unlock", "Tap above"]
        all_text = " ".join(n.get("text", "") for n in root.iter("node"))
        if "com.android.systemui" in (root.get("package", "") or "") or any(h in all_text for h in lock_hints):
            print("FAIL: device is locked; please unlock it and re-run the script")
        else:
            print("FAIL: task row not visible on home screen")
        sys.exit(1)

    title_bounds = node_bounds(title_node)
    if not title_bounds:
        print("FAIL: task title has no bounds")
        sys.exit(1)
    _, title_y1, _, title_y2 = title_bounds
    title_y = (title_y1 + title_y2) // 2

    menu = find_clickable_overlapping(root, title_y, min_x=1000)
    if menu is None:
        print("FAIL: task overflow menu not found")
        sys.exit(1)
    menu_bounds = node_bounds(menu)
    print(f"Opening task menu at {center(menu_bounds)}")
    tap(args.serial, *center(menu_bounds))
    time.sleep(1)

    root = get_ui_root(args.serial)
    run_now = find_nodes_with_text(root, "立即执行")
    if not run_now:
        print("FAIL: 立即执行 menu item not found")
        sys.exit(1)
    run_bounds = node_bounds(run_now[0])
    print(f"Tapping 立即执行 at {center(run_bounds)}")
    tap(args.serial, *center(run_bounds))

    print(f"Waiting {args.timeout}s for execution + verification...")
    time.sleep(args.timeout)

    con = pull_db(args.serial)
    if con is None:
        print("FAIL: could not re-read app database after execution")
        sys.exit(1)
    cur = con.cursor()
    cur.execute(
        "SELECT id, taskId, status, executionMode, message FROM execution_logs "
        "WHERE taskId=? ORDER BY id DESC LIMIT 1",
        (task_id,),
    )
    row = cur.fetchone()
    if row is None:
        print("FAIL: no execution log found for the task")
        sys.exit(1)
    log_id, log_task, status, mode, message = row
    print(f"Latest log: id={log_id}, task={log_task}, status={status}, mode={mode}, message={message!r}")

    expected = f"已验证前台为 {target_pkg}"
    if status in ("SUCCESS", "FALLBACK") and message and expected in message:
        print(f"PASS: Shizuku execution verified foreground {target_pkg}")
        return 0

    print("FAIL: execution did not produce the expected foreground verification")
    return 1


if __name__ == "__main__":
    sys.exit(main())
