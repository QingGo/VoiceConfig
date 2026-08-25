#!/usr/bin/env python3
"""VoiceConfig Agent Node (security-hardened read-only prototype).

A deliberately small, read-only, token-protected HTTP node intended for
experiments on personal Linux machines (Raspberry Pi, servers, NAS).

Security scope (R1):
- No arbitrary shell execution; commands are fixed argv mappings.
- Per-node allowlist in ~/.voiceconfig-node/node.json.
- Bearer token required; token store supports rotation and revocation.
- Stable node identity file.
- Optional TLS/mTLS using Python's ssl module.
- Optional Tailscale-only binding.
- Every request (including rejected auth) is written to JSONL audit.
"""

import argparse
import json
import os
import platform
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

NODE_VERSION = "0.4.0"

# Built-in command set. The node never executes user-supplied argv; it maps a
# whitelisted command name to a fixed argv list.
COMMAND_SPECS = {
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


def hostname() -> str:
    try:
        return socket.gethostname()
    except Exception:
        return "unknown-host"


def atomic_write_text(path: Path, text: str, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.chmod(mode)
    tmp.replace(path)


def atomic_write_json(path: Path, obj: dict, mode: int = 0o600) -> None:
    atomic_write_text(path, json.dumps(obj, ensure_ascii=False, indent=2) + "\n", mode)


def read_json(path: Path, default=None):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


# ---------------------------------------------------------------------------
# Node identity
# ---------------------------------------------------------------------------

def load_or_create_identity(data_dir: Path) -> dict:
    """Return a stable identity dict. Identity is never used for auth."""
    path = data_dir / "identity.json"
    identity = read_json(path)
    if identity and isinstance(identity, dict) and identity.get("node_id"):
        return identity
    identity = {
        "node_id": "node_" + uuid.uuid4().hex[:12],
        "hostname": hostname(),
        "created_at": now_ms(),
        "version": NODE_VERSION,
    }
    atomic_write_json(path, identity)
    return identity


# ---------------------------------------------------------------------------
# Node config
# ---------------------------------------------------------------------------

def load_node_config(data_dir: Path) -> dict:
    """Load optional per-node node.json.

    Configuration keys:
      bind_host: str
      bind_tailscale: bool
      port: int
      allowed_commands: list[str]  (subset of COMMAND_SPECS)
      tls: {enabled, cert, key, client_ca}
    """
    config = read_json(data_dir / "node.json", {}) or {}
    if not isinstance(config, dict):
        config = {}
    allowed = config.get("allowed_commands")
    if allowed is None:
        config["allowed_commands"] = sorted(COMMAND_SPECS.keys())
    elif isinstance(allowed, list):
        config["allowed_commands"] = sorted({c for c in allowed if c in COMMAND_SPECS})
    else:
        config["allowed_commands"] = sorted(COMMAND_SPECS.keys())
    return config


# ---------------------------------------------------------------------------
# Token store
# ---------------------------------------------------------------------------

def _generate_token() -> str:
    return uuid.uuid4().hex + uuid.uuid4().hex


def load_or_create_token_store(data_dir: Path) -> dict:
    """Load or create a multi-token store.

    Store layout:
      {"tokens": [{"token": "...", "created_at": ..., "revoked_at": null, "label": "..."}]}
    """
    path = data_dir / "tokens.json"
    store = read_json(path)
    if store and isinstance(store, dict) and isinstance(store.get("tokens"), list):
        if any(not t.get("revoked_at") for t in store["tokens"]):
            return store
    token = _generate_token()
    store = {
        "tokens": [{
            "token": token,
            "created_at": now_ms(),
            "revoked_at": None,
            "label": "initial",
        }]
    }
    atomic_write_json(path, store)
    atomic_write_text(data_dir / "node.token", token + "\n", 0o600)
    return store


def save_token_store(data_dir: Path, store: dict) -> None:
    atomic_write_json(data_dir / "tokens.json", store)


def token_is_valid(store: dict, token: str) -> bool:
    if not token:
        return False
    for item in store.get("tokens", []):
        if item.get("token") == token and not item.get("revoked_at"):
            return True
    return False


def active_tokens(store: dict):
    return [t for t in store.get("tokens", []) if not t.get("revoked_at")]


def revoke_token(store: dict, token: str) -> bool:
    found = False
    for item in store.get("tokens", []):
        if item.get("token") == token and not item.get("revoked_at"):
            item["revoked_at"] = now_ms()
            found = True
    return found


def rotate_token(store: dict, current_token: str) -> tuple:
    """Create a new active token and revoke the current one.

    Returns (new_token, old_revoked).
    """
    new_token = _generate_token()
    old_revoked = revoke_token(store, current_token)
    store["tokens"].append({
        "token": new_token,
        "created_at": now_ms(),
        "revoked_at": None,
        "label": "rotated-from-" + current_token[:8],
    })
    return new_token, old_revoked


def sync_primary_token_file(data_dir: Path, store: dict) -> None:
    """Keep node.token pointing at the newest active token for SSH tooling."""
    active = active_tokens(store)
    if active:
        latest = max(active, key=lambda t: t.get("created_at", 0))
        atomic_write_text(data_dir / "node.token", latest["token"] + "\n", 0o600)
    else:
        atomic_write_text(data_dir / "node.token", "", 0o600)

# ---------------------------------------------------------------------------
# Command execution
# ---------------------------------------------------------------------------

def run_allowed(command: str, allowed_commands) -> dict:
    if command not in allowed_commands:
        return {
            "ok": False,
            "error": "command_not_allowed",
            "allowed": sorted(allowed_commands),
        }
    cmd = COMMAND_SPECS[command]
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


# ---------------------------------------------------------------------------
# Tasks
# ---------------------------------------------------------------------------

def load_tasks(path: Path) -> dict:
    return read_json(path, {}) or {}


def save_tasks(path: Path, tasks: dict) -> None:
    atomic_write_json(path, tasks)


def recover_interrupted_tasks(tasks: dict) -> int:
    """Mark running tasks as interrupted after a restart.

    Pending/acknowledged tasks are preserved so clients can re-ACK/resume.
    """
    recovered = 0
    for task in tasks.values():
        if task.get("status") == "running":
            task["status"] = "interrupted"
            task["recovered_at"] = now_ms()
            task["updated_at"] = now_ms()
            recovered += 1
    if recovered:
        return recovered
    return 0


# ---------------------------------------------------------------------------
# Tailscale IP discovery
# ---------------------------------------------------------------------------

def tailscale_ipv4() -> str:
    """Return the first Tailscale IPv4, or raise if not available."""
    try:
        out = subprocess.check_output(["tailscale", "ip", "-4"], text=True, timeout=5)
        for line in out.splitlines():
            line = line.strip()
            if line:
                return line
    except Exception:
        pass
    try:
        out = subprocess.check_output(["tailscale", "status"], text=True, timeout=5)
        for line in out.splitlines():
            parts = line.split()
            if parts and parts[0].startswith("100."):
                return parts[0]
    except Exception:
        pass
    raise RuntimeError("Could not determine Tailscale IPv4; is Tailscale up?")


def resolve_bind_host(host_arg, bind_tailscale: bool) -> str:
    if bind_tailscale:
        return tailscale_ipv4()
    return host_arg or "0.0.0.0"


# ---------------------------------------------------------------------------
# Audit
# ---------------------------------------------------------------------------

def audit_log(path: Path, entry: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def token_prefix(token: str) -> str:
    return (token or "")[:8]

# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "VoiceConfigNode/" + NODE_VERSION

    def log_message(self, fmt, *args):
        # Keep default HTTP logging quiet; every request is audited explicitly.
        pass

    @property
    def identity(self):
        return self.server.identity

    def _send_json(self, status: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _extract_token(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return auth[len("Bearer "):].strip()
        return (self.headers.get("X-VoiceConfig-Token") or "").strip()

    def _check_auth(self) -> bool:
        token = self._extract_token()
        with self.server.tokens_lock:
            return token_is_valid(self.server.token_store, token)

    def _audit(self, entry_type: str, ok: bool, extra=None):
        token = self._extract_token()
        entry = {
            "at": now_ms(),
            "type": entry_type,
            "ok": ok,
            "node": self.identity.get("node_id"),
            "hostname": self.identity.get("hostname"),
            "method": self.command,
            "path": self.path,
            "peer": self.client_address[0] if self.client_address else None,
            "token_prefix": token_prefix(token),
        }
        if extra:
            entry.update(extra)
        audit_log(self.server.audit_path, entry)

    def _read_json(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length else b"{}"
            return json.loads(raw.decode("utf-8") or "{}")
        except Exception as e:
            return {"__error__": str(e)}

    def _execute_task(self, task_id: str, command: str) -> None:
        with self.server.tasks_lock:
            task = self.server.tasks.get(task_id)
            if task is not None:
                task["status"] = "running"
                task["started_at"] = now_ms()
                task["attempts"] = int(task.get("attempts", 0)) + 1
                save_tasks(self.server.tasks_path, self.server.tasks)
        result = run_allowed(command, self.server.allowed_commands)
        with self.server.tasks_lock:
            task = self.server.tasks.get(task_id)
            if task is not None:
                task["status"] = "done" if result.get("ok") else "failed"
                task["finished_at"] = now_ms()
                task["result"] = result
                save_tasks(self.server.tasks_path, self.server.tasks)
        self._audit("task_finished", bool(result.get("ok")), {
            "task_id": task_id,
            "command": command,
        })

    def _start_task(self, task_id: str) -> bool:
        """Start a task exactly once under the task lock."""
        with self.server.tasks_lock:
            task = self.server.tasks.get(task_id)
            if task is None:
                return False
            if task.get("status") in ("pending", "acknowledged", "interrupted"):
                # Mark as acknowledged so repeated start calls are no-ops.
                if task.get("status") == "pending":
                    task["acked_at"] = task.get("acked_at") or now_ms()
                task["status"] = "queued"
                save_tasks(self.server.tasks_path, self.server.tasks)
                thread = threading.Thread(
                    target=self._execute_task,
                    args=(task_id, task.get("command", "")),
                    daemon=True,
                )
                thread.start()
                return True
        return False

    def _find_task_by_idempotency_key(self, key: str):
        with self.server.tasks_lock:
            for task in self.server.tasks.values():
                if task.get("idempotency_key") == key and not key in (None, ""):
                    return task
        return None

    def do_GET(self):
        if not self._check_auth():
            self._audit("auth_denied", False)
            self._send_json(401, {"ok": False, "error": "unauthorized"})
            return
        self._audit("request", True)
        if self.path == "/health":
            self._send_json(200, {
                "ok": True,
                "node": self.identity.get("node_id"),
                "hostname": self.identity.get("hostname", hostname()),
                "version": NODE_VERSION,
                "arch": platform.uname().machine,
                "python": sys.version.split()[0],
                "identity": self.identity,
                "allowed_commands": sorted(self.server.allowed_commands),
                "tls": self.server.tls_enabled,
                "bind_host": self.server.bind_host,
                "port": self.server.server_port,
            })
        elif self.path == "/api/status":
            self._send_json(200, {
                "ok": True,
                "node": self.identity.get("node_id"),
                "hostname": self.identity.get("hostname", hostname()),
                "version": NODE_VERSION,
                "identity": self.identity,
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
            self._audit("auth_denied", False)
            self._send_json(401, {"ok": False, "error": "unauthorized"})
            return
        self._audit("request", True)
        body = self._read_json()
        if "__error__" in body:
            self._send_json(400, {"ok": False, "error": "bad_json", "detail": body["__error__"]})
            return
        if self.path == "/api/exec":
            command = str(body.get("command", ""))
            result = run_allowed(command, self.server.allowed_commands)
            result["at"] = now_ms()
            result["node"] = self.identity.get("node_id")
            self._audit("exec", bool(result.get("ok")), {
                "command": command,
            })
            self._send_json(200 if result.get("ok") else 403, result)
        elif self.path == "/api/tasks":
            command = str(body.get("command", ""))
            if command not in self.server.allowed_commands:
                self._audit("task_denied", False, {"command": command})
                self._send_json(403, {
                    "ok": False,
                    "error": "command_not_allowed",
                    "allowed": sorted(self.server.allowed_commands),
                })
                return
            idempotency_key = str(body.get("idempotency_key", "") or "").strip()
            auto_start = bool(body.get("auto_start", True))
            if idempotency_key:
                existing = self._find_task_by_idempotency_key(idempotency_key)
                if existing is not None:
                    if existing.get("command") != command:
                        self._send_json(409, {
                            "ok": False,
                            "error": "idempotency_key_conflict",
                            "message": "same key with different command",
                            "task_id": existing.get("id"),
                        })
                        return
                    if auto_start:
                        self._start_task(existing.get("id"))
                    self._audit("task_idempotent_replay", True, {
                        "task_id": existing.get("id"),
                        "command": command,
                        "idempotency_key": idempotency_key,
                    })
                    self._send_json(200, {
                        "ok": True,
                        "duplicate": True,
                        "task": existing,
                    })
                    return
            task_id = "task_" + uuid.uuid4().hex[:12]
            now = now_ms()
            with self.server.tasks_lock:
                self.server.tasks[task_id] = {
                    "id": task_id,
                    "command": command,
                    "status": "pending",
                    "created_at": now,
                    "updated_at": now,
                    "started_at": None,
                    "finished_at": None,
                    "acked_at": None,
                    "idempotency_key": idempotency_key or None,
                    "progress": 0,
                    "checkpoint": None,
                    "attempts": 0,
                    "result": None,
                    "auto_start": auto_start,
                }
                save_tasks(self.server.tasks_path, self.server.tasks)
            if auto_start:
                self._start_task(task_id)
            self._audit("task_created", True, {
                "task_id": task_id,
                "command": command,
                "idempotency_key": idempotency_key or None,
                "auto_start": auto_start,
            })
            task = self.server.tasks.get(task_id)
            self._send_json(202, {
                "ok": True,
                "task_id": task_id,
                "status": task.get("status", "pending"),
                "task": task,
            })
        elif self.path.startswith("/api/tasks/"):
            self._handle_task_post(self.path, body)
        elif self.path == "/api/admin/rotate-token":
            current = self._extract_token()
            with self.server.tokens_lock:
                new_token, old_revoked = rotate_token(self.server.token_store, current)
                save_token_store(self.server.data_dir, self.server.token_store)
                sync_primary_token_file(self.server.data_dir, self.server.token_store)
            self._audit("rotate_token", old_revoked, {"old_prefix": token_prefix(current)})
            self._send_json(200, {
                "ok": True,
                "token": new_token,
                "old_revoked": old_revoked,
                "note": "Old token is now revoked. Use the new token immediately.",
            })
        elif self.path == "/api/admin/revoke-token":
            target = str(body.get("token", ""))
            with self.server.tokens_lock:
                revoked = revoke_token(self.server.token_store, target)
                if revoked:
                    save_token_store(self.server.data_dir, self.server.token_store)
                    sync_primary_token_file(self.server.data_dir, self.server.token_store)
            self._audit("revoke_token", revoked, {"target_prefix": token_prefix(target)})
            self._send_json(200, {"ok": revoked, "revoked": revoked})
        else:
            self._send_json(404, {"ok": False, "error": "not_found"})

    def _handle_task_post(self, path: str, body: dict) -> None:
        parts = [part for part in path.strip("/").split("/") if part]
        # Expected: api/tasks/<task_id>/<action>
        if len(parts) < 4:
            self._send_json(404, {"ok": False, "error": "not_found"})
            return
        task_id = parts[2]
        action = parts[3]
        with self.server.tasks_lock:
            task = self.server.tasks.get(task_id)
        if task is None:
            self._send_json(404, {"ok": False, "error": "task_not_found", "task_id": task_id})
            return

        if action == "ack":
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
                if task is not None and task.get("status") == "pending":
                    task["status"] = "acknowledged"
                    task["acked_at"] = now_ms()
                    task["updated_at"] = now_ms()
                    save_tasks(self.server.tasks_path, self.server.tasks)
                    should_start = True
                else:
                    should_start = False
            self._audit("task_ack", True, {"task_id": task_id})
            if should_start:
                self._start_task(task_id)
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
            self._send_json(200, {"ok": True, "task": task})
            return

        if action == "checkpoint":
            progress = int(body.get("progress", task.get("progress", 0) or 0))
            checkpoint = body.get("checkpoint")
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
                if task is not None:
                    task["progress"] = max(0, min(100, progress))
                    task["checkpoint"] = checkpoint
                    task["updated_at"] = now_ms()
                    save_tasks(self.server.tasks_path, self.server.tasks)
            self._audit("task_checkpoint", True, {
                "task_id": task_id,
                "progress": progress,
            })
            self._send_json(200, {"ok": True, "task": task})
            return

        if action == "resume":
            retry_failed = bool(body.get("retry", False))
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
                if task is not None:
                    status = task.get("status")
                    can_resume = status in ("interrupted", "pending", "acknowledged") or (
                        retry_failed and status == "failed"
                    )
                    if can_resume:
                        task["status"] = "pending"
                        task["updated_at"] = now_ms()
                        if status == "failed":
                            task["result"] = None
                            task["finished_at"] = None
                        save_tasks(self.server.tasks_path, self.server.tasks)
                        # An explicit resume is an explicit start request.
                        should_start = True
                    else:
                        should_start = False
            self._audit("task_resume", should_start, {"task_id": task_id, "retry_failed": retry_failed})
            if should_start:
                self._start_task(task_id)
            with self.server.tasks_lock:
                task = self.server.tasks.get(task_id)
            self._send_json(200 if should_start else 409, {
                "ok": should_start,
                "task": task,
                "error": None if should_start else "task_not_resumable",
            })
            return

        self._send_json(404, {"ok": False, "error": "unknown_task_action", "action": action})

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Allow", "GET, POST, OPTIONS")
        self.end_headers()


# ---------------------------------------------------------------------------
# Server bootstrap
# ---------------------------------------------------------------------------

def build_ssl_context(config: dict):
    tls = config.get("tls") or {}
    if not tls.get("enabled"):
        return None
    cert = tls.get("cert")
    key = tls.get("key")
    client_ca = tls.get("client_ca")
    if not cert or not key:
        raise RuntimeError("TLS enabled but tls.cert/tls.key not configured")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=cert, keyfile=key)
    if client_ca:
        context.load_verify_locations(cafile=client_ca)
        context.verify_mode = ssl.CERT_REQUIRED
    return context


def main():
    parser = argparse.ArgumentParser(description="VoiceConfig read-only agent node")
    parser.add_argument("--host", default=None, help="Bind address (overrides node.json bind_host)")
    parser.add_argument("--port", type=int, default=None, help="Port (overrides node.json port)")
    parser.add_argument("--data-dir", default=str(Path.home() / ".voiceconfig-node"))
    parser.add_argument("--bind-tailscale", action="store_true", help="Bind only to the Tailscale IPv4 address")
    parser.add_argument("--tls-cert", default=None)
    parser.add_argument("--tls-key", default=None)
    parser.add_argument("--tls-client-ca", default=None)
    args = parser.parse_args()

    data_dir = Path(args.data_dir).expanduser()
    data_dir.mkdir(parents=True, exist_ok=True)

    identity = load_or_create_identity(data_dir)
    config = load_node_config(data_dir)
    if args.tls_cert or args.tls_key or args.tls_client_ca:
        config.setdefault("tls", {}).update({
            "enabled": True,
            "cert": args.tls_cert or (config.get("tls") or {}).get("cert"),
            "key": args.tls_key or (config.get("tls") or {}).get("key"),
            "client_ca": args.tls_client_ca or (config.get("tls") or {}).get("client_ca"),
        })

    if args.host is not None:
        # Explicit CLI bind address wins over config's Tailscale-only default.
        bind_tailscale = args.bind_tailscale
    else:
        bind_tailscale = bool(config.get("bind_tailscale"))
    bind_host = resolve_bind_host(args.host or config.get("bind_host"), bind_tailscale)
    port = args.port or config.get("port") or 8787
    allowed_commands = list(config.get("allowed_commands") or sorted(COMMAND_SPECS.keys()))

    token_store = load_or_create_token_store(data_dir)
    audit_path = data_dir / "audit.jsonl"
    tasks_path = data_dir / "tasks.json"

    server = ThreadingHTTPServer((bind_host, port), Handler)
    server.data_dir = data_dir
    server.identity = identity
    server.allowed_commands = allowed_commands
    server.token_store = token_store
    server.tokens_lock = threading.Lock()
    server.audit_path = audit_path
    server.tasks_path = tasks_path
    server.tasks_lock = threading.Lock()
    server.tasks = load_tasks(tasks_path)
    recovered = recover_interrupted_tasks(server.tasks)
    if recovered:
        save_tasks(tasks_path, server.tasks)
    server.bind_host = bind_host
    server.tls_enabled = bool((config.get("tls") or {}).get("enabled"))

    ssl_context = build_ssl_context(config)
    if ssl_context is not None:
        server.socket = ssl_context.wrap_socket(server.socket, server_side=True)

    scheme = "https" if ssl_context is not None else "http"
    print(f"VoiceConfig Agent Node v{NODE_VERSION}")
    print(f"Node ID: {identity['node_id']}")
    print(f"Listening on {scheme}://{bind_host}:{server.server_port}")
    print(f"Data dir: {data_dir}")
    print(f"Tailscale-only: {bind_tailscale}")
    print(f"TLS: {'enabled' if ssl_context is not None else 'disabled'}")
    print(f"Allowed commands: {', '.join(sorted(allowed_commands))}")
    print(f"Recovered interrupted tasks: {recovered}")
    active = active_tokens(token_store)
    print(f"Active tokens: {len(active)} (do NOT print token contents in logs)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
