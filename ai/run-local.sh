#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  echo 'Usage: bash ai/run-local.sh [service|ngrok|adapter|backend|check]'
  echo 'Default: start the real AI adapter and ngrok together.'
  exit 0
fi
if [[ "${1:-service}" == "service" || "${1:-}" == "ngrok" ]]; then
  if [[ $# -gt 0 ]]; then shift; fi
  exec python3 -B - "$PWD" "$@" <<'GUARDWORK_AI_SERVICE'
"""Start the real GuardWork adapter and an ngrok HTTPS tunnel as one foreground service."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(sys.argv.pop(1))


def load_settings(root=ROOT, environ=None):
    """Read local dotenv values as data; never execute shell content or print secrets."""
    values = {}
    for relative in ("../crawler/.env", "ai/.env.local", "ai/.env.ngrok.local", "backend/.env"):
        path = root / relative
        if not path.is_file():
            continue
        for line in path.read_text().splitlines():
            match = re.fullmatch(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)", line)
            if not match:
                continue
            key, value = match.groups()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            else:
                value = re.split(r"\s+#", value, maxsplit=1)[0].strip()
            values[key] = value
    values.update(os.environ if environ is None else environ)
    return values


def health(url):
    request = Request(url, headers={"ngrok-skip-browser-warning": "guardwork"})
    with urlopen(request, timeout=3) as response:
        return json.load(response)


def available(port):
    with socket.socket() as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            raise RuntimeError(f"Port {port} is in use. Stop the existing service before running this script.") from None


def start_local_neo4j(settings):
    uri = urlparse(settings.get("NEO4J_URI", "bolt://localhost:7687"))
    if uri.hostname not in {"localhost", "127.0.0.1", "::1"}:
        return
    try:
        with socket.create_connection((uri.hostname, uri.port or 7687), timeout=2):
            return
    except OSError:
        pass
    if not shutil.which("systemctl"):
        raise RuntimeError("Local Neo4j is not running. Start the existing Neo4j service and rerun this script.")
    print("Starting the existing local Neo4j service (sudo may ask for your password).", flush=True)
    command = ["systemctl", "start", "neo4j"]
    if os.geteuid() != 0:
        if not shutil.which("sudo"):
            raise RuntimeError("Start Neo4j with systemctl start neo4j, then rerun this script.")
        command.insert(0, "sudo")
    try:
        result = subprocess.run(command, timeout=60)
    except subprocess.TimeoutExpired:
        raise RuntimeError("Timed out starting Neo4j. Check systemctl status neo4j.") from None
    if result.returncode:
        raise RuntimeError("Could not start Neo4j. Run sudo systemctl start neo4j, then rerun this script.")
    wait_until(lambda: neo4j_listening(uri.hostname, uri.port or 7687), [], 60, "local Neo4j")


def neo4j_listening(host, port):
    with socket.create_connection((host, port), timeout=2):
        return True


def wait_until(check, children, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(child.poll() is not None for child in children):
            raise RuntimeError(f"A service exited while waiting for {description}. Check the startup logs.")
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError, URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {description}. Check provider, Neo4j, corpus and ngrok configuration.")


def tunnel_url(upstream):
    tunnels = health("http://127.0.0.1:4040/api/tunnels").get("tunnels", [])
    for tunnel in tunnels:
        url = tunnel.get("public_url", "")
        if (url.startswith("https://")
                and tunnel.get("config", {}).get("addr", "").rstrip("/") == upstream):
            return url.rstrip("/")
    return None


def update_backend_settings(path, settings):
    text = path.read_text() if path.exists() else ""
    for key, value in settings.items():
        if "\n" in value or "\r" in value:
            raise RuntimeError(f"{key} must be a single-line environment value.")
        setting = key + "=" + value
        pattern = rf"(?m)^[ \t]*(?:export[ \t]+)?{key}[ \t]*=.*$"
        if re.search(pattern, text):
            text = re.sub(pattern, lambda _: setting, text)
        else:
            text = text.rstrip("\n") + "\n" + setting + "\n"
    # Replace atomically and keep local credentials readable only by their owner.
    descriptor, name = tempfile.mkstemp(prefix=".env.ai-", suffix=".local", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            output.write(text)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main():
    parser = argparse.ArgumentParser(prog="bash ai/run-local.sh", description=__doc__)
    parser.parse_args()
    settings = load_settings()
    python = ROOT.parent / "crawler/.venv/bin/python"
    snapshot = settings.get("GUARDWORK_AI_LEGAL_SNAPSHOT", "")
    profile = settings.get("GUARDWORK_AI_PROFILE", "jd-local-v1")
    if not python.is_file():
        parser.error("Missing ../crawler/.venv/bin/python. Set up the sibling crawler environment first.")
    if not snapshot or snapshot in {"stub-v1", "replace-with-imported-corpus-id"}:
        parser.error("Set the actual GUARDWORK_AI_LEGAL_SNAPSHOT in backend/.env or ai/.env.local.")
    if len(settings.get("GUARDWORK_AI_SHARED_KEY", "")) < 16:
        parser.error("Set the same GUARDWORK_AI_SHARED_KEY (at least 16 characters) for the backend and adapter.")
    if not shutil.which("ngrok"):
        parser.error("ngrok is not installed or is not on PATH. Install and authenticate it first.")
    settings.update(GUARDWORK_AI_ENABLED="true", GUARDWORK_AI_MODE="crawler")
    command = [str(python), "-B", "-m", "ai.jd_adapter", "--mode", "crawler",
               "--snapshot", snapshot, "--profile", profile]

    children = []
    logs = []
    def stop(_number, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        available(8011)
        available(4040)
        start_local_neo4j(settings)
        log_dir = Path(tempfile.mkdtemp(prefix="guardwork-ai-"))
        print(f"Starting real AI adapter. Startup logs: {log_dir}", flush=True)
        def launch(cmd, name):
            output = (log_dir / name).open("w")
            logs.append(output)
            child = subprocess.Popen(cmd, cwd=ROOT, env=settings, stdout=output, stderr=subprocess.STDOUT)
            children.append(child)
        expected = {"status": "ready", "mode": "crawler", "profile": profile, "legalSnapshot": snapshot}
        launch(command, "adapter.log")
        wait_until(lambda: health("http://127.0.0.1:8011/health") == expected,
                   children, 90, "real adapter readiness")
        upstream = "http://127.0.0.1:8011"
        ngrok = ["ngrok", "http", upstream, "--inspect=false", "--log=stdout", "--log-format=json"]
        public_url = settings.get("NGROK_URL", "")
        if not public_url:
            configured = settings.get("GUARDWORK_AI_ADAPTER_URL", "")
            host = urlparse(configured).hostname or ""
            if host.endswith((".ngrok-free.app", ".ngrok-free.dev", ".ngrok.app", ".ngrok.dev", ".ngrok.io")):
                public_url = configured
        if public_url:
            parsed = urlparse(public_url)
            if (parsed.scheme != "https" or not parsed.hostname or parsed.path not in {"", "/"}
                    or parsed.username or parsed.password or parsed.query or parsed.fragment):
                raise RuntimeError("NGROK_URL must be an HTTPS origin without credentials, path, query or fragment.")
            ngrok += ["--url", public_url.rstrip("/")]
        launch(ngrok, "ngrok.log")
        url = wait_until(lambda: tunnel_url(upstream), children, 45, "ngrok HTTPS endpoint")
        wait_until(lambda: health(url + "/health") == expected, children, 30, "public AI readiness")
        update_backend_settings(ROOT / "backend/.env", {
            "GUARDWORK_AI_ADAPTER_URL": url, "GUARDWORK_AI_ENABLED": "true", "GUARDWORK_AI_MODE": "crawler",
            "GUARDWORK_AI_PROFILE": profile, "GUARDWORK_AI_LEGAL_SNAPSHOT": snapshot,
            "GUARDWORK_AI_SHARED_KEY": settings["GUARDWORK_AI_SHARED_KEY"]})
        print(f"AI service READY: {url}\nHealth: {url}/health\n"
              "Updated backend/.env with the URL and matching AI settings.\n"
              "Restart a running backend to load the URL. For a hosted backend, copy the URL into its environment.\n"
              "Keep this terminal open. Press Ctrl+C to stop the adapter and tunnel.\n"
              "Readiness checks the adapter/corpus configuration; no paid model call was made.", flush=True)
        while all(child.poll() is None for child in children):
            time.sleep(0.5)
        raise RuntimeError("The adapter or ngrok stopped. Both services will be shut down; check the startup logs.")
    except KeyboardInterrupt:
        print("Stopping AI adapter and ngrok.", flush=True)
        return 0
    except (RuntimeError, OSError) as failure:
        print(str(failure), flush=True)
        return 1
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
        for child in children:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        for output in logs:
            output.close()


if __name__ == "__main__":
    raise SystemExit(main())

GUARDWORK_AI_SERVICE
fi
if [[ -f ai/.env.local ]]; then
  set -a
  source ai/.env.local
  set +a
fi
: "${GUARDWORK_AI_LEGAL_SNAPSHOT:?Set GUARDWORK_AI_LEGAL_SNAPSHOT to the imported legal corpus identifier; use the same value in the backend}"
if [[ "$GUARDWORK_AI_LEGAL_SNAPSHOT" == "stub-v1" || "$GUARDWORK_AI_LEGAL_SNAPSHOT" == "replace-with-imported-corpus-id" ]]; then
  echo 'Real analysis requires an actual corpus identifier, not stub-v1.' >&2
  exit 1
fi
export GUARDWORK_AI_ENABLED=true GUARDWORK_AI_MODE=crawler
if [[ "${1:-adapter}" == "backend" ]]; then
  cd backend
  exec ./mvnw spring-boot:run
fi
if [[ "${1:-adapter}" != "adapter" && "${1:-adapter}" != "check" ]]; then
  echo 'Usage: bash ai/run-local.sh [service|ngrok|adapter|backend|check]' >&2
  exit 1
fi
check_args=()
if [[ "${1:-adapter}" == "check" ]]; then check_args=(--check); fi
crawler_python="$(cd ../crawler && pwd)/.venv/bin/python"
exec "$crawler_python" -B -m ai.jd_adapter --mode crawler \
  --snapshot "$GUARDWORK_AI_LEGAL_SNAPSHOT" --profile "${GUARDWORK_AI_PROFILE:-jd-local-v1}" "${check_args[@]}"
