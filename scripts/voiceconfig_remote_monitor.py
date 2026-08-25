#!/usr/bin/env python3
"""Lightweight read-only monitoring client for VoiceConfig remote nodes.

Fetches /api/monitor and prints a compact status. Can be used from cron,
systemd timer, or Android-side scripts for the R5 server-ops loop.

Usage:
  python scripts/voiceconfig_remote_monitor.py --url http://100.91.244.17:8787 \
      --token-file ~/.voiceconfig-node/node.token
"""

import argparse
import json
import sys
from typing import Optional
import urllib.error
import subprocess
import urllib.request
from pathlib import Path


def tailscale_ipv4() -> str:
    out = subprocess.check_output(["tailscale", "ip", "-4"], text=True, timeout=5)
    for line in out.splitlines():
        line = line.strip()
        if line:
            return line
    raise RuntimeError("Tailscale IPv4 not found")


def fetch_monitor(url: str, token: str) -> dict:
    req = urllib.request.Request(url.rstrip("/") + "/api/monitor")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_percent(text: str) -> Optional[float]:
    """Extract the first percentage from a command output line."""
    for line in text.splitlines():
        if "%" in line:
            parts = line.split()
            for part in parts:
                if part.endswith("%"):
                    try:
                        return float(part[:-1])
                    except ValueError:
                        pass
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=None)
    parser.add_argument("--auto-tailscale", action="store_true", help="Use Tailscale IPv4 automatically")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--token", default=None)
    parser.add_argument("--token-file", default=None)
    parser.add_argument("--disk-threshold", type=float, default=90.0)
    parser.add_argument("--mem-threshold", type=float, default=90.0)
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args()

    token = args.token
    if not token and args.token_file:
        token = Path(args.token_file).expanduser().read_text(encoding="utf-8").strip()
    if not token:
        print("token is required (--token or --token-file)", file=sys.stderr)
        return 2

    url = args.url
    if not url and args.auto_tailscale:
        url = "http://%s:%d" % (tailscale_ipv4(), args.port)
    if not url:
        url = "http://100.91.244.17:8787"

    try:
        data = fetch_monitor(url, token)
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False))
        return 2

    snapshot = data.get("snapshot") or {}
    alerts = []
    disk_pct = parse_percent(snapshot.get("df", ""))
    if disk_pct is not None and disk_pct >= args.disk_threshold:
        alerts.append(f"disk {disk_pct:.0f}% >= {args.disk_threshold:.0f}%")
    mem_pct = parse_percent(snapshot.get("free", ""))
    if mem_pct is not None and mem_pct >= args.mem_threshold:
        alerts.append(f"memory {mem_pct:.0f}% >= {args.mem_threshold:.0f}%")

    result = {
        "ok": bool(data.get("ok")),
        "node": data.get("node"),
        "hostname": snapshot.get("hostname", ""),
        "alerts": alerts,
        "snapshot": snapshot,
        "missing_commands": data.get("missing_commands", []),
    }
    if args.as_json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print("node=%s host=%s" % (data.get("node"), snapshot.get("hostname", "")))
        for name in ("uptime", "free", "df", "ps", "tailscale"):
            value = snapshot.get(name, "")
            if value:
                first = value.splitlines()[0] if value.splitlines() else value
                print("%s: %s" % (name, first[:160]))
        if alerts:
            print("ALERTS:")
            for alert in alerts:
                print(" - " + alert)
        else:
            print("no alerts")
    return 1 if alerts else 0


if __name__ == "__main__":
    sys.exit(main())
