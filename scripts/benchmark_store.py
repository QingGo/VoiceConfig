"""Persistent benchmark storage for VoiceConfig ASR benchmarks.

Stores each run both as a human-readable JSON file and as rows in a SQLite
database so results can be queried, compared and tracked over time.
"""

import json
import os
import sqlite3
import time


class BenchmarkStore:
    """Persist benchmark runs as JSON + SQLite."""

    def __init__(self, root="benchmark_results"):
        self.root = root
        self.runs_dir = os.path.join(root, "runs")
        os.makedirs(self.runs_dir, exist_ok=True)
        self.db_path = os.path.join(root, "benchmark.sqlite3")
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS benchmark_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    serial TEXT,
                    device_id TEXT,
                    soc TEXT,
                    abi TEXT,
                    profile TEXT,
                    git_sha TEXT,
                    build_id TEXT,
                    model TEXT NOT NULL,
                    threads INTEGER,
                    provider TEXT,
                    warm INTEGER,
                    lang TEXT,
                    case_count INTEGER,
                    iter_count INTEGER,
                    avg_total_ms REAL,
                    avg_rtf REAL,
                    avg_cer REAL,
                    avg_wer REAL,
                    keyword_pass INTEGER,
                    notes TEXT,
                    run_json_path TEXT
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS benchmark_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id INTEGER NOT NULL,
                    case_id TEXT,
                    model TEXT NOT NULL,
                    threads INTEGER,
                    provider TEXT,
                    warm INTEGER,
                    wav TEXT,
                    expected_text TEXT,
                    recognized_text TEXT,
                    error TEXT,
                    total_ms INTEGER,
                    warmup_ms REAL,
                    rtf REAL,
                    cer REAL,
                    wer REAL,
                    keyword_ok INTEGER,
                    mem_pss_kb INTEGER,
                    raw_json TEXT,
                    FOREIGN KEY(run_id) REFERENCES benchmark_runs(id)
                )
                """
            )
            conn.commit()
        finally:
            conn.close()

    @staticmethod
    def _num(values):
        nums = [v for v in values if v is not None]
        return sum(nums) / len(nums) if nums else None

    def save_run(self, meta, results, json_path=None):
        if json_path is None:
            stamp = time.strftime("%Y%m%d-%H%M%S")
            json_path = os.path.join(self.runs_dir, f"{stamp}-{meta.get('model', 'model')}.json")
            n = 1
            while os.path.exists(json_path):
                json_path = os.path.join(self.runs_dir, f"{stamp}-{n}-{meta.get('model', 'model')}.json")
                n += 1

        with open(json_path, "w", encoding="utf-8") as f:
            json.dump({"meta": meta, "results": results}, f, ensure_ascii=False, indent=2)

        keyword_ok = [int(bool(r.get("keyword_ok"))) for r in results if r.get("keyword_ok") is not None]
        conn = sqlite3.connect(self.db_path)
        try:
            cur = conn.execute(
                """
                INSERT INTO benchmark_runs (
                    created_at, serial, device_id, soc, abi, profile, git_sha, build_id,
                    model, threads, provider, warm, lang, case_count, iter_count,
                    avg_total_ms, avg_rtf, avg_cer, avg_wer, keyword_pass, notes, run_json_path
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    meta.get("created_at"),
                    meta.get("serial"),
                    meta.get("device_id"),
                    meta.get("soc"),
                    meta.get("abi"),
                    meta.get("profile"),
                    meta.get("git_sha"),
                    meta.get("build_id"),
                    meta.get("model"),
                    meta.get("threads"),
                    meta.get("provider"),
                    1 if meta.get("warm") else 0,
                    meta.get("lang"),
                    len(results),
                    meta.get("iterations", 1),
                    self._num([r.get("total_ms") for r in results]),
                    self._num([r.get("rtf") for r in results]),
                    self._num([r.get("cer") for r in results]),
                    self._num([r.get("wer") for r in results]),
                    sum(keyword_ok) if keyword_ok else None,
                    meta.get("notes"),
                    json_path,
                ),
            )
            run_id = cur.lastrowid
            for r in results:
                conn.execute(
                    """
                    INSERT INTO benchmark_results (
                        run_id, case_id, model, threads, provider, warm, wav,
                        expected_text, recognized_text, error, total_ms, warmup_ms,
                        rtf, cer, wer, keyword_ok, mem_pss_kb, raw_json
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        run_id,
                        r.get("case_id"),
                        meta.get("model"),
                        meta.get("threads"),
                        meta.get("provider"),
                        1 if meta.get("warm") else 0,
                        r.get("wav"),
                        r.get("expected_text"),
                        r.get("text"),
                        r.get("error"),
                        r.get("total_ms"),
                        r.get("warmup_ms"),
                        r.get("rtf"),
                        r.get("cer"),
                        r.get("wer"),
                        r.get("keyword_ok"),
                        r.get("mem_pss_kb"),
                        json.dumps(r, ensure_ascii=False),
                    ),
                )
            conn.commit()
        finally:
            conn.close()

        print(f"benchmark saved: run_id={run_id} json={json_path} db={self.db_path}")
        return run_id, json_path

    def list_runs(self, limit=20):
        conn = sqlite3.connect(self.db_path)
        try:
            rows = conn.execute(
                """
                SELECT id, created_at, device_id, profile, abi, model, threads, provider, warm,
                       case_count, avg_cer, avg_rtf, avg_total_ms, keyword_pass, git_sha
                FROM benchmark_runs ORDER BY id DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        finally:
            conn.close()

        if not rows:
            print("(no benchmark runs yet)")
            return
        headers = ["id", "created_at", "device", "profile", "abi", "model", "thr", "prov", "warm", "cases", "cer", "rtf", "avg_ms", "kw", "sha"]
        print("\t".join(headers))
        for row in rows:
            print("\t".join("" if v is None else str(v) for v in row))
