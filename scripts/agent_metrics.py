#!/usr/bin/env python3
"""汇总 Agent trace 的每轮/工具/LLM 耗时与缓存指标。

用法:
  python scripts/agent_metrics.py --trace app/files/agent_trace/agent_trace.log
  python scripts/agent_metrics.py --serial 100.101.16.52:41419
  python scripts/agent_metrics.py --trace <file> --json results.json --verbose

分析内容:
- 每次 run 的总耗时、轮数、LLM 调用次数、工具调用次数
- LLM 平均/累计 ttft、thinking、output、wait
- 工具执行总耗时、按工具分布
- verify 耗时
- KV cache hit/miss、token、request bytes
- 每轮各阶段耗时
"""

import argparse
import json
import os
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

PACKAGE = "com.voiceconfig.app"
REMOTE_TRACE = "files/agent_trace/agent_trace.log"


def pull_trace(serial):
    cmd = ["adb", "-s", serial, "exec-out", "run-as", PACKAGE, "cat", REMOTE_TRACE]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"adb pull trace failed: {proc.stderr}")
    return proc.stdout


def parse_entries(text):
    entries = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            if isinstance(obj, dict):
                entries.append(obj)
        except json.JSONDecodeError:
            continue
    return entries


def empty_run():
    return {
        "runId": "",
        "userText": "",
        "finish": None,
        "llm_responses": [],
        "round_timings": [],
        "tool_results": [],
        "tool_calls": [],
        "errors": [],
        "started_at": None,
        "last_at": None,
    }


def canonical_int(value):
    if value is None:
        return 0
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def mean(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return 0.0
    return sum(vals) / len(vals)


def analyze(entries):
    runs = defaultdict(empty_run)
    for e in entries:
        run_id = e.get("runId", "unknown")
        run = runs[run_id]
        run["runId"] = run_id
        t = e.get("time")
        if t:
            if run["started_at"] is None:
                run["started_at"] = t
            run["last_at"] = t
        typ = e.get("type")
        if typ == "run_start":
            run["userText"] = e.get("userText", "")
        elif typ == "run_finished":
            run["finish"] = e
        elif typ == "llm_response":
            run["llm_responses"].append(e)
        elif typ == "round_timing":
            run["round_timings"].append(e)
        elif typ == "tool_result":
            run["tool_results"].append(e)
        elif typ == "tool_call":
            run["tool_calls"].append(e)
        elif typ in ("llm_error", "tool_error", "repeat_detected", "run_timeout", "run_cancelled", "perception_loop_detected"):
            run["errors"].append(e)
    return runs


def run_summary(run):
    round_timings = sorted(run["round_timings"], key=lambda x: canonical_int(x.get("round")))
    llm = run["llm_responses"]
    tools = run["tool_results"]
    finish = run["finish"] or {}
    llm_wait = sum(canonical_int(x.get("llm_wait_ms")) for x in round_timings)
    ttft_list = [canonical_int(x.get("ttft_ms")) for x in llm]
    thinking_list = [canonical_int(x.get("thinking_ms")) for x in llm]
    output_list = [canonical_int(x.get("output_ms")) for x in llm]
    tool_exec_total = sum(canonical_int(x.get("tool_exec_ms")) for x in round_timings)
    verify_total = sum(canonical_int(x.get("verify_ms")) for x in round_timings)
    other_total = sum(canonical_int(x.get("other_ms")) for x in round_timings)
    round_total = sum(canonical_int(x.get("total_ms")) for x in round_timings)

    cache_hit = sum(canonical_int(x.get("prompt_cache_hit_tokens")) for x in llm)
    cache_miss = sum(canonical_int(x.get("prompt_cache_miss_tokens")) for x in llm)
    prompt_tokens = sum(canonical_int(x.get("prompt_tokens")) for x in llm)
    completion_tokens = sum(canonical_int(x.get("completion_tokens")) for x in llm)
    total_tokens = sum(canonical_int(x.get("total_tokens")) for x in llm)
    request_bytes = sum(canonical_int(x.get("request_bytes")) for x in llm)

    tool_stats = defaultdict(lambda: {"count": 0, "ok": 0, "fail": 0, "duration_ms": 0})
    for tr in tools:
        name = tr.get("tool", "?")
        dur = canonical_int(tr.get("duration_ms"))
        tool_stats[name]["count"] += 1
        tool_stats[name]["duration_ms"] += dur
        if tr.get("ok"):
            tool_stats[name]["ok"] += 1
        else:
            tool_stats[name]["fail"] += 1

    rounds = canonical_int(finish.get("tool_call_count", 0))
    return {
        "runId": run["runId"],
        "userText": run["userText"],
        "ok": finish.get("ok"),
        "finish_message": (finish.get("message") or "")[:200],
        "duration_ms": canonical_int(finish.get("duration_ms")),
        "rounds": len(round_timings) or len(llm),
        "llm_calls": len(llm),
        "tool_calls": len(tools),
        "llm_wait_ms": llm_wait,
        "llm_ttft_avg_ms": round(mean(ttft_list), 1),
        "llm_ttft_total_ms": sum(ttft_list),
        "llm_thinking_avg_ms": round(mean(thinking_list), 1),
        "llm_thinking_total_ms": sum(thinking_list),
        "llm_output_avg_ms": round(mean(output_list), 1),
        "llm_output_total_ms": sum(output_list),
        "tool_exec_total_ms": tool_exec_total,
        "verify_total_ms": verify_total,
        "other_total_ms": other_total,
        "round_total_ms": round_total,
        "cache_hit_tokens": cache_hit,
        "cache_miss_tokens": cache_miss,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "request_bytes": request_bytes,
        "cache_hit_rate": round(cache_hit / (cache_hit + cache_miss), 4) if (cache_hit + cache_miss) else 0.0,
        "tools": {k: dict(v) for k, v in sorted(tool_stats.items(), key=lambda kv: -kv[1]["duration_ms"])},
        "round_timings": [
            {
                "round": canonical_int(x.get("round")),
                "total_ms": canonical_int(x.get("total_ms")),
                "llm_wait_ms": canonical_int(x.get("llm_wait_ms")),
                "ttft_ms": canonical_int(x.get("llm_ttft_ms")),
                "thinking_ms": canonical_int(x.get("llm_thinking_ms")),
                "output_ms": canonical_int(x.get("llm_output_ms")),
                "tool_exec_ms": canonical_int(x.get("tool_exec_ms")),
                "verify_ms": canonical_int(x.get("verify_ms")),
                "other_ms": canonical_int(x.get("other_ms")),
                "tool_calls": canonical_int(x.get("tool_calls")),
                "phase": x.get("phase", "normal"),
            }
            for x in round_timings
        ],
        "errors": run["errors"],
    }


def print_run(run, verbose=False):
    print("=" * 78)
    print(f"Run {run['runId']}  ok={run['ok']}")
    print(f"  目标: {run['userText']}")
    if run["finish_message"]:
        print(f"  结束: {run['finish_message'][:120]}")
    print(f"  总耗时 {run['duration_ms']} ms | 轮数 {run['rounds']} | LLM {run['llm_calls']} | 工具 {run['tool_calls']}")
    print(f"  LLM wait {run['llm_wait_ms']} ms | tool {run['tool_exec_total_ms']} ms | verify {run['verify_total_ms']} ms | other {run['other_total_ms']} ms | round_sum {run['round_total_ms']} ms")
    print(f"  TTFT avg {run['llm_ttft_avg_ms']} ms (total {run['llm_ttft_total_ms']}) | thinking avg {run['llm_thinking_avg_ms']} ms (total {run['llm_thinking_total_ms']}) | output avg {run['llm_output_avg_ms']} ms (total {run['llm_output_total_ms']})")
    print(f"  cache hit {run['cache_hit_tokens']} / miss {run['cache_miss_tokens']} ({run['cache_hit_rate']*100:.1f}%) | req {run['request_bytes']} B | total {run['total_tokens']} tok")
    if run["tools"]:
        print("  工具耗时:")
        for name, s in run["tools"].items():
            print(f"    {name:25s} x{s['count']:<3d} {s['duration_ms']:>7d} ms  ok={s['ok']}/{s['count']}")
    if verbose and run["round_timings"]:
        print("  每轮:")
        for rt in run["round_timings"]:
            print(f"    R{rt['round']:>2d} total={rt['total_ms']:>6d} llm={rt['llm_wait_ms']:>6d} ttft={rt['ttft_ms']:>5d} think={rt['thinking_ms']:>5d} out={rt['output_ms']:>5d} tool={rt['tool_exec_ms']:>6d} verify={rt['verify_ms']:>5d} other={rt['other_ms']:>6d} calls={rt['tool_calls']} {rt['phase']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", help="本地 agent_trace.log 路径")
    parser.add_argument("--serial", help="通过 adb 从真机拉取 trace")
    parser.add_argument("--json", help="同时输出 JSON 汇总到该文件")
    parser.add_argument("--run-id", help="只看指定 runId")
    parser.add_argument("--last", type=int, default=0, help="只看最后 N 个 run")
    parser.add_argument("--verbose", action="store_true", help="输出每轮明细")
    args = parser.parse_args()

    if not args.trace and not args.serial:
        parser.error("需要 --trace 或 --serial")

    text = ""
    if args.serial:
        text = pull_trace(args.serial)
    else:
        text = Path(args.trace).read_text(encoding="utf-8", errors="replace")

    entries = parse_entries(text)
    runs = analyze(entries)
    summaries = [run_summary(r) for r in runs.values()]

    if args.run_id:
        summaries = [x for x in summaries if x["runId"] == args.run_id]
    if args.last > 0:
        summaries = summaries[-args.last:]
    summaries.sort(key=lambda r: r["duration_ms"] or 0)

    if not summaries:
        print("没有找到可汇总的 run")
        return 0

    n = len(summaries)
    total_dur = sum(x["duration_ms"] for x in summaries)
    total_llm = sum(x["llm_calls"] for x in summaries)
    total_tool = sum(x["tool_calls"] for x in summaries)
    total_llm_wait = sum(x["llm_wait_ms"] for x in summaries)
    total_tool_exec = sum(x["tool_exec_total_ms"] for x in summaries)
    total_verify = sum(x["verify_total_ms"] for x in summaries)
    total_cache_hit = sum(x["cache_hit_tokens"] for x in summaries)
    total_cache_miss = sum(x["cache_miss_tokens"] for x in summaries)
    total_req = sum(x["request_bytes"] for x in summaries)
    print("=" * 78)
    print(f"汇总: {n} runs")
    print(f"  平均总耗时 {total_dur/n:.0f} ms | 平均 LLM {total_llm/n:.1f} 次 | 平均工具 {total_tool/n:.1f} 次")
    print(f"  平均 LLM wait {total_llm_wait/n:.0f} ms | 平均 tool exec {total_tool_exec/n:.0f} ms | 平均 verify {total_verify/n:.0f} ms")
    if total_cache_hit + total_cache_miss:
        print(f"  cache hit {total_cache_hit} / miss {total_cache_miss} ({total_cache_hit/(total_cache_hit+total_cache_miss)*100:.1f}%) | req {total_req} B")
    else:
        print(f"  cache hit/miss 无数据 | req {total_req} B")

    for run in sorted(summaries, key=lambda x: x["duration_ms"] or 0, reverse=True):
        print_run(run, verbose=args.verbose)

    if args.json:
        Path(args.json).write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nJSON 已写入: {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
