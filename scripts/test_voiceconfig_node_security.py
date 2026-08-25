#!/usr/bin/env python3
"""Unit tests for VoiceConfig Agent Node security features (R1).

Run:
    python scripts/test_voiceconfig_node_security.py
"""

import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import voiceconfig_agent_node as node


class IdentityConfigTest(unittest.TestCase):
    def test_identity_is_stable(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            a = node.load_or_create_identity(d)
            b = node.load_or_create_identity(d)
            self.assertEqual(a["node_id"], b["node_id"])
            self.assertTrue(a["node_id"].startswith("node_"))

    def test_config_filters_unknown_commands(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "node.json").write_text(json.dumps({
                "allowed_commands": ["uptime", "rm", "hostname"],
                "bind_tailscale": False,
            }), encoding="utf-8")
            cfg = node.load_node_config(d)
            self.assertEqual(cfg["allowed_commands"], ["hostname", "uptime"])
            self.assertFalse(cfg["bind_tailscale"])


class TokenStoreTest(unittest.TestCase):
    def test_create_rotate_revoke(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            store = node.load_or_create_token_store(d)
            token = d.joinpath("node.token").read_text().strip()
            self.assertTrue(node.token_is_valid(store, token))

            new_token, revoked = node.rotate_token(store, token)
            self.assertTrue(revoked)
            self.assertTrue(node.token_is_valid(store, new_token))
            self.assertFalse(node.token_is_valid(store, token))

            node.sync_primary_token_file(d, store)
            self.assertEqual(d.joinpath("node.token").read_text().strip(), new_token)

            self.assertTrue(node.revoke_token(store, new_token))
            self.assertFalse(node.token_is_valid(store, new_token))

    def test_run_allowed_respects_per_node_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            cfg = node.load_node_config(d)
            cfg["allowed_commands"] = ["hostname"]
            result = node.run_allowed("uptime", cfg["allowed_commands"])
            self.assertFalse(result["ok"])
            self.assertEqual(result["error"], "command_not_allowed")
            self.assertIn("hostname", result["allowed"])


class HttpSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.data_dir = Path(cls.tmp.name)
        cls.store = node.load_or_create_token_store(cls.data_dir)
        cls.token = cls.data_dir.joinpath("node.token").read_text().strip()
        (cls.data_dir / "node.json").write_text(json.dumps({
            "allowed_commands": ["hostname", "uptime"],
            "bind_host": "127.0.0.1",
        }), encoding="utf-8")
        cls.server = node.ThreadingHTTPServer(("127.0.0.1", 0), node.Handler)
        cls.server.data_dir = cls.data_dir
        cls.server.identity = node.load_or_create_identity(cls.data_dir)
        cls.server.allowed_commands = ["hostname"]
        cls.server.token_store = node.load_or_create_token_store(cls.data_dir)
        cls.server.tokens_lock = threading.Lock()
        cls.server.audit_path = cls.data_dir / "audit.jsonl"
        cls.server.tasks_path = cls.data_dir / "tasks.json"
        cls.server.tasks_lock = threading.Lock()
        cls.server.tasks = {}
        cls.server.bind_host = "127.0.0.1"
        cls.server.tls_enabled = False
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def setUp(self):
        # Give each test a fresh active token so tests are independent.
        with self.server.tokens_lock:
            fresh = node._generate_token()
            self.server.token_store["tokens"].append({
                "token": fresh,
                "created_at": node.now_ms(),
                "revoked_at": None,
                "label": "test",
            })
            node.save_token_store(self.data_dir, self.server.token_store)
            node.sync_primary_token_file(self.data_dir, self.server.token_store)
        self.token = fresh

    def _request(self, method, path, body=None, token=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (self.port, path),
            data=data,
            method=method,
        )
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def test_auth_required_and_health(self):
        status, _ = self._request("GET", "/health")
        self.assertEqual(status, 401)
        status, body = self._request("GET", "/health", token=self.token)
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertTrue(body["identity"]["node_id"].startswith("node_"))

    def test_allowlist_and_rotation(self):
        status, _ = self._request("POST", "/api/exec", {"command": "hostname"}, token=self.token)
        self.assertEqual(status, 200)

        status, body = self._request("POST", "/api/exec", {"command": "uptime"}, token=self.token)
        self.assertEqual(status, 403)
        self.assertEqual(body["error"], "command_not_allowed")

        status, body = self._request("POST", "/api/admin/rotate-token", {}, token=self.token)
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        new_token = body["token"]

        status, _ = self._request("GET", "/health", token=self.token)
        self.assertEqual(status, 401)
        status, _ = self._request("GET", "/health", token=new_token)
        self.assertEqual(status, 200)

        status, body = self._request("POST", "/api/admin/revoke-token", {"token": new_token}, token=new_token)
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        status, _ = self._request("GET", "/health", token=new_token)
        self.assertEqual(status, 401)

    def test_audit_contains_denied_and_admin_events(self):
        audit_path = self.data_dir / "audit.jsonl"
        lines = audit_path.read_text(encoding="utf-8").splitlines()
        types = [json.loads(line)["type"] for line in lines if line.strip()]
        self.assertIn("auth_denied", types)
        self.assertIn("rotate_token", types)
        self.assertIn("revoke_token", types)


class TaskProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.data_dir = Path(cls.tmp.name)
        cls.store = node.load_or_create_token_store(cls.data_dir)
        cls.token = cls.data_dir.joinpath("node.token").read_text().strip()
        (cls.data_dir / "node.json").write_text(json.dumps({
            "allowed_commands": ["hostname", "uptime"],
            "bind_host": "127.0.0.1",
        }), encoding="utf-8")
        cls.server = node.ThreadingHTTPServer(("127.0.0.1", 0), node.Handler)
        cls.server.data_dir = cls.data_dir
        cls.server.identity = node.load_or_create_identity(cls.data_dir)
        cls.server.allowed_commands = ["hostname", "uptime"]
        cls.server.token_store = node.load_or_create_token_store(cls.data_dir)
        cls.server.tokens_lock = threading.Lock()
        cls.server.audit_path = cls.data_dir / "audit.jsonl"
        cls.server.tasks_path = cls.data_dir / "tasks.json"
        cls.server.tasks_lock = threading.Lock()
        cls.server.tasks = {}
        cls.server.bind_host = "127.0.0.1"
        cls.server.tls_enabled = False
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def _request(self, method, path, body=None, token=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (self.port, path),
            data=data,
            method=method,
        )
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def test_submit_ack_checkpoint_query(self):
        status, body = self._request(
            "POST", "/api/tasks",
            {"command": "hostname", "auto_start": False, "idempotency_key": "proto-1"},
            token=self.token,
        )
        self.assertEqual(status, 202, body)
        task_id = body["task"]["id"]
        self.assertEqual(body["task"]["status"], "pending")
        self.assertEqual(body["task"]["idempotency_key"], "proto-1")

        # Idempotent replay returns the same task.
        status, replay = self._request(
            "POST", "/api/tasks",
            {"command": "hostname", "auto_start": False, "idempotency_key": "proto-1"},
            token=self.token,
        )
        self.assertEqual(status, 200, replay)
        self.assertTrue(replay.get("duplicate"))
        self.assertEqual(replay["task"]["id"], task_id)

        # Same key with a different command must be rejected.
        status, conflict = self._request(
            "POST", "/api/tasks",
            {"command": "uptime", "auto_start": False, "idempotency_key": "proto-1"},
            token=self.token,
        )
        self.assertEqual(status, 409, conflict)

        # Checkpoint before ACK is allowed and persists.
        status, ck = self._request(
            "POST", "/api/tasks/%s/checkpoint" % task_id,
            {"progress": 42, "checkpoint": {"step": "prepared"}},
            token=self.token,
        )
        self.assertEqual(status, 200, ck)
        self.assertEqual(ck["task"]["progress"], 42)
        self.assertEqual(ck["task"]["checkpoint"]["step"], "prepared")

        # ACK starts the task; final GET must show the stored checkpoint/progress.
        status, ack = self._request("POST", "/api/tasks/%s/ack" % task_id, {}, token=self.token)
        self.assertEqual(status, 200, ack)
        status, query = self._request("GET", "/api/tasks/%s" % task_id, token=self.token)
        self.assertEqual(status, 200, query)
        self.assertEqual(query["task"]["id"], task_id)
        self.assertIn(query["task"]["status"], ("acknowledged", "queued", "running", "done", "failed"))
        self.assertEqual(query["task"]["progress"], 42)

    def test_resume_interrupted_task(self):
        status, body = self._request(
            "POST", "/api/tasks",
            {"command": "hostname", "auto_start": False, "idempotency_key": "proto-resume"},
            token=self.token,
        )
        task_id = body["task"]["id"]
        self.assertEqual(status, 202)

        # Simulate server restart recovery.
        with self.server.tasks_lock:
            self.server.tasks[task_id]["status"] = "running"
        recovered = node.recover_interrupted_tasks(self.server.tasks)
        self.assertEqual(recovered, 1)
        self.assertEqual(self.server.tasks[task_id]["status"], "interrupted")

        # Resume makes it pending and starts the worker.
        status, res = self._request("POST", "/api/tasks/%s/resume" % task_id, {}, token=self.token)
        self.assertEqual(status, 200, res)
        status, query = self._request("GET", "/api/tasks/%s" % task_id, token=self.token)
        self.assertEqual(status, 200, query)
        self.assertIn(query["task"]["status"], ("queued", "running", "done", "failed", "acknowledged", "pending"))


    def test_monitor_snapshot(self):
        status, body = self._request("GET", "/api/monitor", token=self.token)
        self.assertEqual(status, 200, body)
        self.assertTrue(body["ok"])
        self.assertTrue("hostname" in body["snapshot"])
        self.assertTrue("network" in body["missing_commands"])

    def test_skill_run_local_execution(self):
        status, body = self._request(
            "POST",
            "/api/skills/run",
            {"skill": {
                "id": "skill-test-1",
                "name": "远程主机信息",
                "steps": [
                    {"command": "hostname", "purpose": "获取主机名"},
                    {"command": "hostname", "purpose": "再次获取主机名"},
                ],
            }},
            token=self.token,
        )
        self.assertEqual(status, 200, body)
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["results"]), 2)
        self.assertTrue(all(r["ok"] for r in body["results"]))

        status, body = self._request(
            "POST",
            "/api/skills/run",
            {"skill": {
                "id": "skill-test-bad",
                "steps": [{"command": "network"}],
            }},
            token=self.token,
        )
        self.assertEqual(status, 403, body)
        self.assertFalse(body["ok"])


if __name__ == "__main__":
    unittest.main()
