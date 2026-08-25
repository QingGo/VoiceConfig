#!/usr/bin/env python3
"""Minimal VoiceConfig Agent Node prototype.

A deliberately small, read-only, token-protected HTTP node intended for
experiments on a personal Linux machine (Raspberry Pi etc.).

Security scope:
- No arbitrary shell execution.
- Only a fixed read-only command allowlist.
- Bearer token required.
- Every request is logged to a local JSONL audit file.
"""

import argparse
import json
import os
import socket
import subprocess
import threading
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

NODE_VERSION = "0.2.0"

ALLOWED_COMMANDS = {
    "hostname": ["hostname"],
    "uname": ["uname", "-a"],
    "uptime": ["uptime"],
    "free": ["free", "-h"],
    "df": ["df", "-h"],
    "ps": ["ps", "aux"],
    "os_release": ["cat", "/etc/os-release"],
    "network": ["ip", "-4", "addr", "show"],
    "tailscale": ["tailscale", "status"],
}


def now_ms() -> int:
    return int(time.time() * 1000)


def node_id() -> str:
    try:
        return socket.gethostname()
    except Exception:
        return "unknown-node"


def load_or_create_token(path: Path) -> str:
    if path.exists():
        token = path.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = uuid.uuid4().hex + uuid.uuid4().hex
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(token + "\n", encoding="utf-8")
    path.chmod(0o600)
    return token


def audit_log(path: Path, entry: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def run_allowed(command: str) -> dict:
    if command not in ALLOWED_COMMANDS:
        return {
            "ok": False,
            "error": "command_not_allowed",
            "allowed": sorted(ALLOWED_COMMANDS.keys()),
        }
    cmd = ALLOWED_COMMANDS[command]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=15,
        )
        return {
            "ok": proc.returncode == 0,
            "command": command,
            "argv": cmd,
            "exit_code": proc.returncode,
            "stdout": proc.stdout[-4000:],
            "stderr": proc.stderr[-2000:],
            "duration_ms": 0,
        }
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout", "command": command}
    except Exception as e:
        return {"ok": False, "error": str(e), "command": command}


def load_tasks(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_tasks(path: Path, tasks: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


class Handler(BaseHTTPRequestHandler):
    server_version = "VoiceConfigNode/" + NODE_VERSION

    def log_message(self, fmt, *args):
        # Keep the default HTTP log quiet; audit is written explicitly.
        pass

    def _send_json(self, status: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_auth(self) -> bool:
        auth = self.headers.get("Authorization", "")
        token = self.server.token
        return auth == "Bearer " + token or self.headers.get("X-VoiceConfig-Token") == token

    def _read_json(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length else b"{}"
            return json.loads(raw.decode("utf-8") or "{}")
        except Exception as e:
            return {"__error__": str(e)}

    def _execute_task(self, task_id: str, command: str) -> None:
        result = run_allowed(command)
        with self.server.tasks_lock:
            task = self.server.tasks.get(task_id)
            if task is not None:
                task["status"] = "done" if result.get("ok") else "failed"
                task["finished_at"] = now_ms()
                task["result"] = result
                save_tasks(self.server.tasks_path, self.server.tasks)
        audit_log(self.server.audit_path, {
            "at": now_ms(),
            "type": "task_finished",
            "task_id": task_id,
            "command": command,
            "ok": result.get("ok", False),
        })

    def do_GET(self):
        if not self._check_auth():
            self._send_json(401, {"ok": False, "error": "unauthorized"})
            return
        if self.path == "/health":
            self._send_json(200, {
                "ok": True,
                "node": node_id(),
                "version": NODE_VERSION,
                "hostname": socket.gethostname(),
                "arch": os.uname().machine,
                "python": sys.version.split()[0],
                "allowed_commands": sorted(ALLOWED_COMMANDS.keys()),
            })
            audit_log(self.server.audit_path, {
                "at": now_ms(), "type": "health", "ok": True,
            })
        elif self.path == "/api/status":
            self._send_json(200, {
                "ok": True,
                "node": node_id(),
                "version": NODE_VERSION,
                "uptime": subprocess.run(["uptime"], capture_output=True, text=True, timeout=5).stdout.strip(),
                "memory": subprocess.run(["free", "-h"], capture_output=True, text=True, timeout=5).stdout.strip(),
                "disk": subprocess.run(["df", "-h", "/"], capture_output=True, text=True, timeout=5).stdout.strip(),
            })
        elif self.path == "/api/tasks":
            with self.server.tasks_lock:
                tasks = sorted(
                    self.server.tasks.values(),
                    key=lambda t: t.get("created_at", 0),
                    reverse=True,
                )
            self._send_json(200, {"ok": True, "tasks": tasks})
        elif self.path.startswith("/api/tasks/"):
            task_id = self.path[len("/api/tasks/"):]
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
            if task is None:
                self._send_json(404, {"ok": False, "error": "task_not_found"})
            else:
                self._send_json(200, {"ok": True, "task": task})
        else:
            self._send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        if not self._check_auth():
            self._send_json(401, {"ok": False, "error": "unauthorized"})
            return
        body = self._read_json()
        if "__error__" in body:
            self._send_json(400, {"ok": False, "error": "bad_json", "detail": body["__error__"]})
            return
        if self.path == "/api/exec":
            command = str(body.get("command", ""))
            result = run_allowed(command)
            result["at"] = now_ms()
            result["node"] = node_id()
            audit_log(self.server.audit_path, {
                "at": now_ms(),
                "type": "exec",
                "command": command,
                "ok": result.get("ok", False),
            })
            self._send_json(200 if result.get("ok") else 403, result)
        elif self.path == "/api/tasks":
            command = str(body.get("command", ""))
            if command not in ALLOWED_COMMANDS:
                self._send_json(403, {
                    "ok": False,
                    "error": "command_not_allowed",
                    "allowed": sorted(ALLOWED_COMMANDS.keys()),
                })
                return
            task_id = "task_" + uuid.uuid4().hex[:12]
            with self.server.tasks_lock:
                self.server.tasks[task_id] = {
                    "id": task_id,
                    "command": command,
                    "status": "pending",
                    "created_at": now_ms(),
                    "started_at": None,
                    "finished_at": None,
                    "result": None,
                }
                save_tasks(self.server.tasks_path, self.server.tasks)
            threading.Thread(
                target=self._execute_task,
                args=(task_id, command),
                daemon=True,
            ).start()
            audit_log(self.server.audit_path, {
                "at": now_ms(),
                "type": "task_created",
                "task_id": task_id,
                "command": command,
            })
            self._send_json(202, {"ok": True, "task_id": task_id, "status": "pending"})
        else:
            self._send_json(404, {"ok": False, "error": "not_found"})

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Allow", "GET, POST, OPTIONS")
        self.end_headers()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--data-dir", default=str(Path.home() / ".voiceconfig-node"))
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    token_path = data_dir / "node.token"
    audit_path = data_dir / "audit.jsonl"
    tasks_path = data_dir / "tasks.json"
    token = os.environ.get("VOICECONFIG_NODE_TOKEN") or load_or_create_token(token_path)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.token = token
    server.audit_path = audit_path
    server.tasks_path = tasks_path
    server.tasks_lock = threading.Lock()
    server.tasks = load_tasks(tasks_path)

    print(f"VoiceConfig Agent Node v{NODE_VERSION}")
    print(f"Listening on {args.host}:{args.port}")
    print(f"Data dir: {data_dir}")
    print(f"Token: {token}")
    print("Allowed commands: " + ", ".join(sorted(ALLOWED_COMMANDS.keys())))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
