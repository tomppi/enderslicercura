#!/usr/bin/env python3
"""Local image-to-3D service: the piece the app talks to.

One job at a time (a single GPU is the resource), shelling out to whichever
generator is installed, then normalising the result through mesh_cleanup.py so
every backend returns the same thing: a printable STL.

    python server.py --port 8765 --generator "instantmesh" --instantmesh-dir ~/InstantMesh

Protocol (JSON, no auth - bind it to your LAN or tailnet only):

    POST /generate            multipart-ish: raw image body  -> {"job": id}
    GET  /jobs/<id>           -> {"state": queued|running|done|failed, "detail": ...}
    GET  /jobs/<id>/model.stl -> the mesh (only when state is done)
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
JOBS: dict[str, dict] = {}
LOCK = threading.Lock()
ARGS: argparse.Namespace


def run_generator(image: str, outdir: str) -> str:
    """Runs the configured generator and returns the mesh path it produced."""
    work = os.path.join(outdir, "gen")
    os.makedirs(work, exist_ok=True)
    if ARGS.generator == "instantmesh":
        cmd = [
            "python", "run.py", ARGS.config,
            image, "--output_path", work,
        ]
    elif ARGS.generator == "triposr":
        cmd = ["python", "run.py", image, "--output-dir", work]
    else:
        raise RuntimeError(f"unknown generator {ARGS.generator!r}")
    subprocess.run(cmd, cwd=ARGS.generator_dir, check=True)
    produced = [
        os.path.join(work, name)
        for name in sorted(os.listdir(work))
        if name.lower().endswith((".obj", ".ply", ".glb"))
    ]
    if not produced:
        raise RuntimeError("the generator produced no mesh")
    return produced[0]


def worker(job_id: str, image_path: str, outdir: str) -> None:
    with LOCK:
        JOBS[job_id]["state"] = "running"
    try:
        mesh = run_generator(image_path, outdir)
        stl = os.path.join(outdir, "model.stl")
        subprocess.run(
            ["python", os.path.join(HERE, "mesh_cleanup.py"), mesh, stl,
             "--faces", str(ARGS.faces), "--target-height-mm", str(ARGS.height_mm)],
            check=True,
        )
        with LOCK:
            JOBS[job_id].update(state="done", stl=stl, source=mesh)
    except BaseException as error:  # surfaced to the app as the job detail
        with LOCK:
            JOBS[job_id].update(state="failed", detail=str(error)[:500])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # quiet by default
        print("gen3d:", fmt % args, flush=True)

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/generate":
            return self._json(404, {"error": "unknown path"})
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return self._json(400, {"error": "empty upload"})
        with LOCK:
            if any(j["state"] in ("queued", "running") for j in JOBS.values()):
                return self._json(429, {"error": "a job is already running"})
        job_id = uuid.uuid4().hex[:12]
        outdir = tempfile.mkdtemp(prefix=f"gen3d-{job_id}-")
        image_path = os.path.join(outdir, "input.png")
        with open(image_path, "wb") as handle:
            handle.write(self.rfile.read(length))
        with LOCK:
            JOBS[job_id] = {"state": "queued", "dir": outdir}
        threading.Thread(target=worker, args=(job_id, image_path, outdir), daemon=True).start()
        self._json(202, {"job": job_id})

    def do_GET(self):
        parts = [p for p in self.path.split("/") if p]
        if len(parts) == 2 and parts[0] == "jobs":
            job = JOBS.get(parts[1])
            if not job:
                return self._json(404, {"error": "unknown job"})
            return self._json(200, {k: v for k, v in job.items() if k != "dir"})
        if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "model.stl":
            job = JOBS.get(parts[1])
            if not job or job.get("state") != "done":
                return self._json(409, {"error": "job is not done"})
            with open(job["stl"], "rb") as handle:
                body = handle.read()
            self.send_response(200)
            self.send_header("Content-Type", "model/stl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._json(404, {"error": "unknown path"})


def main() -> int:
    global ARGS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--generator", choices=["instantmesh", "triposr"], default="instantmesh")
    parser.add_argument("--generator-dir", required=True, help="checkout of the generator")
    parser.add_argument("--config", default="configs/instant-mesh-base.yaml")
    parser.add_argument("--faces", type=int, default=250_000)
    parser.add_argument("--height-mm", type=float, default=60.0)
    ARGS = parser.parse_args()
    for tool in ("python", ARGS.generator_dir):
        if tool == "python":
            continue
        if not os.path.isdir(tool):
            print(f"warning: generator dir {tool} does not exist yet")
    server = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    print(f"gen3d listening on http://{ARGS.host}:{ARGS.port} (generator={ARGS.generator})", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
