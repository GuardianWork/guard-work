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


if __name__ == "__main__":
    main()
