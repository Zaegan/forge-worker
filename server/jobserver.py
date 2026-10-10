#!/usr/bin/env python3
"""forge-worker job server: hands out batches of Forge games to remote workers and accepts their results.

Several machines can work at once. Each machine has its own token (TOKENS file: {"token": "worker name"}). Games are
leased in batches; a lease that is not answered before it expires goes back into the pool, so a machine that vanishes
costs nothing but time. A game counts once: the first accepted upload for it wins, later ones are kept aside as
duplicates.

Campaigns: CAMPAIGNS/<name>/ holds games.jsonl (one game per line: {"gid", "a", "b", "seed", "index", "timeout"}, where
a and b are deck names), decks/<name>.dck, and meta.json ({"tweaks": "...", "harness": "<git commit>", "priority": N}).
Results land in CAMPAIGNS/<name>/results/<worker>/<job>/ (results.jsonl plus the game logs) and state in state.sqlite.

API (Authorization: Bearer <token>):
  GET  /api/job?slots=N     -> 200 {"job", "campaign", "tweaks", "harness", "lease_s", "games": [...], "decks": {...}}
                               or 204 when there is no work
  POST /api/result?job=ID   body: a zip of results.jsonl + logs/ -> 200 {"accepted": n, "duplicates": m}
  GET  /api/status          -> per-campaign counts

Usage: jobserver.py --campaigns DIR --tokens FILE [--port 8650] [--bind 127.0.0.1] [--lease 1800] [--per-slot 4]
"""
import argparse
import io
import json
import os
import sqlite3
import threading
import time
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_UPLOAD = 512 * 1024 * 1024
ap = argparse.ArgumentParser()
ap.add_argument("--campaigns", required=True)
ap.add_argument("--tokens", required=True)
ap.add_argument("--port", type=int, default=8650)
ap.add_argument("--bind", default="127.0.0.1")
ap.add_argument("--lease", type=int, default=1800)
ap.add_argument("--per-slot", type=int, default=4)
a = ap.parse_args()
C = os.path.abspath(a.campaigns)
lock = threading.Lock()
db = sqlite3.connect(os.path.join(C, "state.sqlite"), check_same_thread=False)
db.executescript("""
create table if not exists games (campaign text, gid text, status text default 'open', job text, worker text,
  lease_until real, done_at real, primary key (campaign, gid));
create table if not exists jobs (job text primary key, campaign text, worker text, created real, n integer,
  done real);
""")


def load_campaigns():
    """register new games from every campaign's games.jsonl (idempotent)"""
    out = {}
    for name in sorted(os.listdir(C)):
        d = os.path.join(C, name)
        if not os.path.isfile(os.path.join(d, "games.jsonl")):
            continue
        meta = json.load(open(os.path.join(d, "meta.json")))
        out[name] = meta
        with lock:
            for l in open(os.path.join(d, "games.jsonl")):
                if l.strip():
                    g = json.loads(l)
                    db.execute("insert or ignore into games (campaign, gid) values (?, ?)", (name, g["gid"]))
            db.commit()
    return out


def tokens():
    return json.load(open(a.tokens))


def lease(worker, slots):
    camps = load_campaigns()
    now = time.time()
    with lock:
        db.execute("update games set status='open', job=null, worker=null where status='leased' and lease_until < ?", (now,))
        for name in sorted(camps, key=lambda n: -camps[n].get("priority", 0)):
            rows = db.execute("select gid from games where campaign=? and status='open' limit ?",
                              (name, slots * a.per_slot)).fetchall()
            if not rows:
                continue
            job = uuid.uuid4().hex[:16]
            gids = [r[0] for r in rows]
            db.executemany("update games set status='leased', job=?, worker=?, lease_until=? where campaign=? and gid=?",
                           [(job, worker, now + a.lease, name, g) for g in gids])
            db.execute("insert into jobs values (?, ?, ?, ?, ?, null)", (job, name, worker, now, len(gids)))
            db.commit()
            d = os.path.join(C, name)
            want = set(gids)
            games = [g for g in map(json.loads, filter(str.strip, open(os.path.join(d, "games.jsonl")))) if g["gid"] in want]
            decks = {}
            for g in games:
                for k in ("a", "b"):
                    if g[k] not in decks:
                        decks[g[k]] = open(os.path.join(d, "decks", g[k] + ".dck")).read()
            return {"job": job, "campaign": name, "tweaks": camps[name].get("tweaks", ""), "harness": camps[name]["harness"],
                    "lease_s": a.lease, "games": games, "decks": decks}
    return None


def accept(worker, job, body):
    with lock:
        row = db.execute("select campaign, worker from jobs where job=?", (job,)).fetchone()
    if not row:
        return 404, {"error": "unknown job"}
    camp, owner = row
    if owner != worker:
        return 403, {"error": "job belongs to another worker"}
    try:
        z = zipfile.ZipFile(io.BytesIO(body))
        names = z.namelist()
        if "results.jsonl" not in names or any(n.startswith("/") or ".." in n for n in names):
            return 400, {"error": "bad archive"}
        results = [json.loads(l) for l in z.read("results.jsonl").decode().splitlines() if l.strip()]
    except Exception as e:  # noqa: BLE001
        return 400, {"error": f"bad archive: {e}"}
    accepted = dup = 0
    now = time.time()
    with lock:
        for r in results:
            gid = r.get("gid")
            st = db.execute("select status from games where campaign=? and gid=?", (camp, gid)).fetchone()
            if not st:
                continue
            if st[0] == "done":
                dup += 1
                continue
            db.execute("update games set status='done', job=?, worker=?, done_at=? where campaign=? and gid=?",
                       (job, worker, now, camp, gid))
            accepted += 1
        db.execute("update jobs set done=? where job=?", (now, job))
        db.commit()
    dest = os.path.join(C, camp, "results", worker, job + ("" if accepted else ".duplicate"))
    os.makedirs(dest, exist_ok=True)
    z.extractall(dest)
    return 200, {"accepted": accepted, "duplicates": dup}


def status():
    with lock:
        rows = db.execute("select campaign, status, count(*) from games group by campaign, status").fetchall()
        workers = db.execute("select worker, count(*), max(done_at) from games where status='done' group by worker").fetchall()
    out = {}
    for c, s, n in rows:
        out.setdefault(c, {})[s] = n
    return {"campaigns": out, "workers": {w: {"games": n, "last": t} for w, n, t in workers}}


class H(BaseHTTPRequestHandler):
    def who(self):
        auth = self.headers.get("Authorization", "")
        tok = auth[7:] if auth.startswith("Bearer ") else ""
        return tokens().get(tok) if tok else None

    def send(self, code, obj=None):
        body = b"" if obj is None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        w = self.who()
        if not w:
            return self.send(401, {"error": "bad token"})
        u = urlparse(self.path)
        if u.path == "/api/job":
            slots = max(1, min(64, int(parse_qs(u.query).get("slots", ["1"])[0])))
            j = lease(w, slots)
            return self.send(200, j) if j else self.send(204)
        if u.path == "/api/status":
            return self.send(200, status())
        self.send(404, {"error": "not found"})

    def do_POST(self):
        w = self.who()
        if not w:
            return self.send(401, {"error": "bad token"})
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", "0"))
        if u.path != "/api/result" or n <= 0 or n > MAX_UPLOAD:
            return self.send(400, {"error": "bad request"})
        body = self.rfile.read(n)
        code, obj = accept(w, parse_qs(u.query).get("job", [""])[0], body)
        self.send(code, obj)

    def log_message(self, fmt, *args):
        print(f"{time.strftime('%F %T')} {self.client_address[0]} {fmt % args}", flush=True)


load_campaigns()
print(f"jobserver on {a.bind}:{a.port}, campaigns in {C}", flush=True)
ThreadingHTTPServer((a.bind, a.port), H).serve_forever()
