"""Persistent, authenticated native-feature build service.
Runs behind TLS reverse proxy. All code builds happen in GitHub-hosted runners.
"""
import base64
import hashlib
import hmac
import json
import os
import queue
import re
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import PurePosixPath
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen, build_opener, HTTPRedirectHandler

DATABASE = os.environ.get("ATLAS_JOBS_DB", "/data/jobs.sqlite3")
REPO = os.environ.get("GITHUB_REPOSITORY", "Azi599/LifeAtlas")
BASE = os.environ.get("GITHUB_BASE_BRANCH", "main")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
ACCESS = os.environ.get("ATLAS_SERVICE_TOKEN", "")
AI_ENDPOINT = os.environ.get("AI_ENDPOINT", "https://api.deepseek.com/chat/completions")
AI_MODEL = os.environ.get("AI_MODEL", "deepseek-chat")
AI_KEY = os.environ.get("AI_API_KEY", "")
JOBS = queue.Queue()

def database():
    connection = sqlite3.connect(DATABASE, timeout=30)
    connection.row_factory = sqlite3.Row
    return connection

def initialize():
    with database() as db:
        db.execute("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,spec TEXT,status TEXT,branch TEXT,commit_sha TEXT,run_id INTEGER,detail TEXT,artifact TEXT,created_at INTEGER)")
        # An interrupted request is retained and exposed, rather than replayed implicitly.
        db.execute("UPDATE jobs SET status='INTERRUPTED',detail='Service restarted; submit a new request to retry' WHERE status IN ('QUEUED','GENERATING','COMMITTING')")

def update(job_id, **values):
    allowed = {"status", "branch", "commit_sha", "run_id", "detail", "artifact"}
    assert values and set(values) <= allowed
    with database() as db:
        db.execute("UPDATE jobs SET " + ",".join(f"{key}=?" for key in values) + " WHERE id=?", [*values.values(), job_id])

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args):
        return None

def request_json(url, token, method="GET", body=None):
    if urlsplit(url).scheme != "https":
        raise ValueError("HTTPS endpoint required")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "LifeAtlas-build-service"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    with build_opener(NoRedirect()).open(Request(url, data=data, headers=headers, method=method), timeout=300) as response:
        payload = response.read(2_000_001)
        if len(payload) > 2_000_000:
            raise ValueError("Remote response too large")
        return json.loads(payload) if payload else {}

def github(path, method="GET", body=None):
    if not TOKEN:
        raise ValueError("GITHUB_TOKEN not configured")
    return request_json(f"https://api.github.com/repos/{REPO}/{path}", TOKEN, method, body)

def validate_changes(changes):
    if not isinstance(changes, list) or not 1 <= len(changes) <= 20:
        raise ValueError("Expected 1–20 source changes")
    seen = set()
    for change in changes:
        path, content = change.get("path", ""), change.get("content", "")
        parts = PurePosixPath(path).parts
        if not path or path.startswith("/") or ".." in parts or path in seen or "\\" in path:
            raise ValueError("Invalid source path")
        if path in {"app/src/test/java/com/lifeatlas/app/MathEngineTest.kt", "app/src/androidTest/java/com/lifeatlas/app/StorageTest.kt", "app/src/androidTest/java/com/lifeatlas/app/UiTest.kt"}:
            raise ValueError("Core regression tests are protected; add a new test file")
        permitted = path.startswith(("app/src/main/java/", "app/src/main/res/", "app/src/test/java/", "app/src/androidTest/java/")) or path == "app/src/main/AndroidManifest.xml"
        if not permitted or not isinstance(content, str) or len(content.encode()) > 300_000:
            raise ValueError("Source change exceeds allowed scope")
        seen.add(path)
    return changes

def generate(spec, tree):
    if not AI_KEY:
        raise ValueError("AI_API_KEY not configured")
    sources = []
    total = 0
    for item in tree.get("tree", []):
        path = item["path"]
        if path.startswith("app/src/main/java/") and path.endswith(".kt"):
            blob = github("git/blobs/" + item["sha"])
            content = base64.b64decode(blob["content"]).decode()
            total += len(content)
            if total > 350_000:
                break
            sources.append({"path": path, "content": content})
    instructions = "Implement the requested native Android feature in this Kotlin/Compose app. Use existing Actions and data contracts. Return JSON {summary,changes:[{path,content}]} containing full file contents. Include meaningful tests. Only app/src/main/java, app/src/main/res, app/src/test/java, app/src/androidTest/java and AndroidManifest.xml may change. Preserve data and migrations. No generated progress or fake results. Sources: " + json.dumps(sources)
    result = request_json(AI_ENDPOINT, AI_KEY, "POST", {"model": AI_MODEL, "messages": [{"role": "system", "content": instructions}, {"role": "user", "content": json.dumps(spec)}], "temperature": 0.1})
    text = result["choices"][0]["message"]["content"].strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0]
    result = json.loads(text)
    validate_changes(result.get("changes"))
    return result

def process(job_id):
    with database() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    try:
        update(job_id, status="GENERATING", detail="Generating source and tests")
        base = github("git/ref/heads/" + quote(BASE, safe=""))["object"]["sha"]
        commit = github("git/commits/" + base)
        tree_sha = commit["tree"]["sha"]
        tree = github("git/trees/" + tree_sha + "?recursive=1")
        generated = generate(json.loads(row["spec"]), tree)
        branch = "native/" + job_id
        update(job_id, status="COMMITTING", detail=generated.get("summary", "Source generated"), branch=branch)
        entries = [{"path": c["path"], "mode": "100644", "type": "blob", "content": c["content"]} for c in generated["changes"]]
        next_tree = github("git/trees", "POST", {"base_tree": tree_sha, "tree": entries})["sha"]
        next_commit = github("git/commits", "POST", {"message": "Native feature: " + json.loads(row["spec"]).get("title", job_id), "tree": next_tree, "parents": [base]})["sha"]
        github("git/refs", "POST", {"ref": "refs/heads/" + branch, "sha": next_commit})
        github("actions/workflows/native-build.yml/dispatches", "POST", {"ref": branch, "inputs": {"request_id": job_id}})
        update(job_id, status="SUBMITTED", commit_sha=next_commit, detail="Source committed; GitHub Actions dispatched")
    except Exception as error:
        update(job_id, status="FAILED", detail=f"{type(error).__name__}: {str(error)[:500]}")

def refresh(job_id):
    with database() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    if row is None:
        raise KeyError(job_id)
    if row["status"] in {"SUBMITTED", "BUILDING"}:
        try:
            runs = github("actions/runs?branch=" + quote(row["branch"], safe="") + "&event=workflow_dispatch&per_page=10")["workflow_runs"]
            run = next((r for r in runs if r["head_sha"] == row["commit_sha"]), None)
            if run:
                status = "BUILDING" if run["status"] != "completed" else "READY_FOR_REVIEW" if run["conclusion"] == "success" else "FAILED"
                update(job_id, status=status, run_id=run["id"], artifact=run["html_url"], detail="GitHub Actions: " + str(run["conclusion"] or run["status"]))
        except Exception as error:
            update(job_id, detail="Status lookup failed: " + type(error).__name__)
    with database() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    return {"id": row["id"], "status": row["status"], "detail": row["detail"], "artifactUrl": row["artifact"] or "", "branch": row["branch"], "commit": row["commit_sha"], "runId": row["run_id"]}

def worker():
    while True:
        job = JOBS.get()
        try:
            process(job)
        finally:
            JOBS.task_done()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def respond(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def authorized(self):
        return bool(ACCESS) and hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + ACCESS)
    def do_POST(self):
        if not self.authorized():
            return self.respond(401, {"error": "Unauthorized"})
        if self.path != "/builds":
            return self.respond(404, {"error": "Not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 128_000:
                raise ValueError("Request size invalid")
            spec = json.loads(self.rfile.read(length))
            if not isinstance(spec, dict) or not spec.get("requirements"):
                raise ValueError("requirements required")
            if not (AI_KEY and TOKEN):
                return self.respond(503, {"error": "AI and GitHub credentials must be configured"})
            key = self.headers.get("Idempotency-Key", "")
            if key and not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", key):
                raise ValueError("Invalid idempotency key")
            job_id = __import__('hashlib').sha256(key.encode()).hexdigest()[:32] if key else uuid.uuid4().hex
            with database() as db:
                previous = db.execute("SELECT spec FROM jobs WHERE id=?", (job_id,)).fetchone()
                if previous:
                    if json.loads(previous["spec"]) != spec:
                        return self.respond(409, {"error":"Idempotency key already used for different requirements"})
                    return self.respond(200, refresh(job_id))
                db.execute("INSERT INTO jobs(id,spec,status,detail,created_at) VALUES (?,?,'QUEUED','Queued',?)", (job_id, json.dumps(spec), int(time.time())))
            JOBS.put(job_id)
            self.respond(202, {"id": job_id, "status": "QUEUED", "detail": "Queued"})
        except (ValueError, json.JSONDecodeError) as error:
            self.respond(400, {"error": str(error)})
    def do_GET(self):
        if not self.authorized():
            return self.respond(401, {"error": "Unauthorized"})
        match = re.fullmatch(r"/builds/([a-f0-9]{32})", self.path)
        if not match:
            return self.respond(404, {"error": "Not found"})
        try:
            self.respond(200, refresh(match[1]))
        except KeyError:
            self.respond(404, {"error": "Unknown build"})

if __name__ == "__main__":
    if len(ACCESS) < 24:
        raise SystemExit("ATLAS_SERVICE_TOKEN must be at least 24 characters")
    initialize()
    threading.Thread(target=worker, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
