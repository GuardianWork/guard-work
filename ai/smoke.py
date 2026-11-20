"""Create a synthetic JD, request analysis, poll, and verify cached reuse locally."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="demo_owner")
    parser.add_argument("--jd", type=Path, default=Path(__file__).parent / "samples" / "jd-risk.txt")
    parser.add_argument("--wait-seconds", type=int, default=60)
    parser.add_argument("--require-real", action="store_true", help="Fail if JD scanning uses stub mode")
    parser.add_argument("--chat", action="store_true", help="Also require a grounded real legal-chat response")
    args = parser.parse_args()
    password = os.environ.get("GUARDWORK_SMOKE_PASSWORD", "GuardWork123!")
    token = None

    def call(method, path, payload=None):
        headers = {"Content-Type": "application/json", "X-Guardian-Portal": "public"}
        if token:
            headers["Authorization"] = "Bearer " + token
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            with urlopen(Request(args.base_url.rstrip("/") + path, data=body, headers=headers,
                                 method=method), timeout=20) as response:
                return json.load(response)["data"]
        except HTTPError as failure:
            raise SystemExit(f"Local API returned HTTP {failure.code} for {method} {path}; "
                             "check the backend setup and account access.") from None

    identity = call("POST", "/api/auth/login", {"usernameOrEmail": args.username, "password": password})
    token = identity["accessToken"]
    job = call("POST", "/api/companies/me/jobs", {"content": {
        "title": "Local AI scan example", "description": args.jd.read_text(encoding="utf-8")}})
    revision = job["workingRevision"]
    path = f'/api/companies/me/jobs/{job["id"]}/revisions/{revision["id"]}/scans'
    scan = call("POST", path, {"expectedRevisionVersion": revision["version"]})
    print(f'Created draft job={job["id"]}, revision={revision["id"]}, scan={scan["scanId"]}, mode={scan["mode"]}')
    if args.require_real and scan["mode"] != "crawler":
        raise SystemExit("FAIL: stub mode cannot verify real AI analysis; restart backend and adapter in crawler mode.")
    end = time.monotonic() + args.wait_seconds
    while scan["status"] in {"QUEUED", "RUNNING"} and time.monotonic() < end:
        time.sleep(1)
        scan = call("GET", path + f'/{scan["scanId"]}')
    if scan["status"] != "COMPLETED":
        raise SystemExit(f'Scan status={scan["status"]}; failure={scan.get("failureCode")}; '
                         "the saved draft remains available. Increase --wait-seconds for real analysis.")
    reused = call("POST", path, {"expectedRevisionVersion": revision["version"]})
    if reused["scanId"] != scan["scanId"]:
        raise SystemExit("Result reuse check failed")
    print(json.dumps(scan["report"], ensure_ascii=False, indent=2))
    print("PASS: completed report persisted; repeated request reused the same scan.")
    if args.chat:
        chat = call("POST", "/api/candidates/me/legal-chat", {
            "question": "Theo Điều 25 Bộ luật Lao động 2019, thời gian thử việc đối với công việc yêu cầu trình độ đại học là bao lâu?"})
        if (chat.get("mode") != "crawler" or chat.get("status") != "ANSWERED"
            or not chat.get("citations") or not all(c.get("quoteGrounded") for c in chat["citations"])):
            raise SystemExit("FAIL: chat did not return a grounded real answer.")
        print(f'PASS: real chat answered with {len(chat["citations"])} grounded citations; model={chat.get("model")}.')


if __name__ == "__main__":
    main()
