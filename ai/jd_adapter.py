"""Loopback-only JD adapter. Run `python3 -m ai.jd_adapter --help` from the repo root."""
from __future__ import annotations

import argparse
from datetime import date
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path

MAX_BODY = 524288


class InvalidAnalysis(ValueError):
    """The input/provider contract is incomplete or inconsistent."""


def analyze(request: dict, *, mode: str, profile: str, snapshot: str, backend=None) -> dict:
    if not isinstance(request, dict) or any(request.get(key) != value for key, value in (
        ("mode", mode), ("profile", profile), ("legalSnapshot", snapshot)
    )):
        raise InvalidAnalysis("Configuration mismatch")
    text = request.get("analysisText")
    if not isinstance(text, str) or not text.strip() or len(text.encode("utf-8")) > 262144:
        raise InvalidAnalysis("Invalid analysis text")
    try:
        as_of = date.fromisoformat(request["asOfDate"])
    except (ValueError, TypeError, KeyError) as failure:
        raise InvalidAnalysis("Invalid analysis date") from failure
    base = {"schemaVersion": "1", "mode": mode, "profile": profile,
            "legalSnapshot": snapshot, "asOfDate": as_of.isoformat(),
            "usage": {"available": False}}
    if mode == "stub":
        return {**base, "outcome": "NEEDS_REVIEW", "findings": [], "warnings": [
            "Chế độ thử nghiệm: chưa gọi AI hoặc kiểm tra căn cứ pháp luật."
        ]}
    if mode != "crawler" or backend is None:
        raise InvalidAnalysis("Real analysis backend is required")
    return {**base, **backend.analyze(text, as_of)}


def make_handler(*, mode: str, profile: str, snapshot: str, key: str, backend=None):
    class Handler(BaseHTTPRequestHandler):
        # Request paths and exception messages are not operational logs.
        def log_message(self, *args):
            pass

        def send_json(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            if len(body) > MAX_BODY:
                status, body = 422, b'{"error":"INVALID_ANALYSIS"}'
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path != "/analyze":
                self.send_json(404, {"error": "NOT_FOUND"})
                return
            supplied = self.headers.get("X-GuardWork-AI-Key", "")
            if not hmac.compare_digest(supplied.encode(), key.encode()):
                self.send_json(401, {"error": "UNAUTHORIZED"})
                return
            try:
                self.connection.settimeout(15)
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAX_BODY or self.headers.get("Transfer-Encoding"):
                    self.send_json(413, {"error": "INVALID_BODY_SIZE"})
                    return
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise InvalidAnalysis("Incomplete body")
                payload = json.loads(raw)
                report = analyze(payload, mode=mode, profile=profile, snapshot=snapshot, backend=backend)
                self.send_json(200, report)
            except (InvalidAnalysis, ValueError, TypeError, KeyError):
                self.send_json(422, {"error": "INVALID_ANALYSIS"})
            except Exception:
                self.send_json(503, {"error": "ANALYSIS_UNAVAILABLE"})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("stub", "crawler"), default="stub")
    parser.add_argument("--port", type=int, default=8011)
    parser.add_argument("--profile", default="jd-local-v1")
    parser.add_argument("--snapshot", default="stub-v1")
    parser.add_argument("--crawler", type=Path, default=Path(__file__).resolve().parents[2] / "crawler")
    args = parser.parse_args()
    key = os.environ.get("GUARDWORK_AI_SHARED_KEY", "local-development-only")
    if len(key) < 16:
        parser.error("GUARDWORK_AI_SHARED_KEY must contain at least 16 characters")
    backend = None
    if args.mode == "crawler":
        if args.snapshot == "stub-v1":
            parser.error("Crawler mode requires an explicit --snapshot identifier")
        from ai.crawler_backend import CrawlerBackend
        backend = CrawlerBackend(args.crawler)
    server = HTTPServer(("127.0.0.1", args.port), make_handler(
        mode=args.mode, profile=args.profile, snapshot=args.snapshot, key=key, backend=backend))
    print(f"JD adapter: http://127.0.0.1:{args.port}; mode={args.mode}; profile={args.profile}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if backend is not None:
            backend.close()


if __name__ == "__main__":
    main()
