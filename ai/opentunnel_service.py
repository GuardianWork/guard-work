"""Run the real AI adapter with an isolated OpenTunnel CLI profile."""
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
import tempfile
import time
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
TUNNEL_PROFILE = "guardwork-ai"
TARGET = "127.0.0.1:8011"


def load_settings(root=ROOT, environ=None):
    values = {}
    for relative in ("../crawler/.env", "ai/.env.local", "ai/.env.opentunnel.local", "backend/.env"):
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


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def health(url):
    with build_opener(NoRedirects).open(url, timeout=3) as response:
        return json.load(response)


def wait_until(check, children, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(child.poll() is not None for child in children):
            raise RuntimeError(f"A service exited while waiting for {description}. Check the startup logs.")
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {description}. Check the private startup logs.")


def start_local_neo4j(settings):
    uri = urlparse(settings.get("NEO4J_URI", "bolt://localhost:7687"))
    if uri.hostname not in {"localhost", "127.0.0.1", "::1"}:
        return

    def listening():
        with socket.create_connection((uri.hostname, uri.port or 7687), timeout=2):
            return True

    try:
        if listening():
            return
    except OSError:
        pass
    if not shutil.which("systemctl"):
        raise RuntimeError("Start the existing Neo4j service before running this launcher.")
    command = ["systemctl", "start", "neo4j"]
    if os.geteuid() != 0:
        command.insert(0, "sudo")
    print("Starting the existing Neo4j service (sudo may ask for your password).", flush=True)
    subprocess.run(command, check=True, timeout=60)
    wait_until(listening, [], 60, "local Neo4j")


def tunnel_command(*args):
    return ["opentunnel", "--profile", TUNNEL_PROFILE, *args]


def check_profile(settings):
    result = subprocess.run(tunnel_command("status"), env=settings, capture_output=True,
                            text=True, check=True, timeout=10)
    # Fail closed if a future CLI changes its status format. Never disconnect a
    # service we cannot establish was stopped before this invocation.
    if not re.search(r"(?m)^Service: stopped(?:\s|$)", result.stdout):
        raise RuntimeError("OpenTunnel profile guardwork-ai is already running or its status is unknown. "
                           "Use another profile for other tunnels; stop guardwork-ai explicitly before retrying.")
    # A dedicated profile may retain our route between launches, but must never
    # bring unrelated routes online when route add restarts its service.
    routes = [line for line in result.stdout.splitlines() if "→" in line]
    if any(not re.fullmatch(r"\S+\s+→\s+127\.0\.0\.1:8011", line.strip()) for line in routes):
        raise RuntimeError("OpenTunnel profile guardwork-ai contains unrelated routes. "
                           "Reserve this profile exclusively for this launcher.")


def tunnel_url(output):
    # The route-add command prints the selected URL on its own line. Do not
    # accidentally select the API hostname from progress messages or errors.
    urls = re.findall(r"(?m)^https://[a-z0-9-]+\.[a-z0-9-]+\.opentunnel\.xyz/?\s*$", output)
    if len(urls) != 1:
        raise RuntimeError("OpenTunnel did not return one hosted route URL. Check opentunnel.log.")
    return urls[0].strip().rstrip("/")


def stop_child(child):
    if child.poll() is None:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check the real adapter without opening a tunnel")
    args = parser.parse_args(argv)
    settings = load_settings()
    python = ROOT.parent / "crawler/.venv/bin/python"
    snapshot = settings.get("GUARDWORK_AI_LEGAL_SNAPSHOT", "")
    profile = settings.get("GUARDWORK_AI_PROFILE", "jd-local-v1")
    if not python.is_file():
        parser.error("Missing ../crawler/.venv/bin/python. Set up the sibling crawler environment first.")
    if not snapshot or snapshot in {"stub-v1", "replace-with-imported-corpus-id"}:
        parser.error("Set the actual GUARDWORK_AI_LEGAL_SNAPSHOT in backend/.env or ai/.env.local.")
    if len(settings.get("GUARDWORK_AI_SHARED_KEY", "")) < 16:
        parser.error("Set matching GUARDWORK_AI_SHARED_KEY (at least 16 characters) for backend and adapter.")
    settings.update(GUARDWORK_AI_ENABLED="true", GUARDWORK_AI_MODE="crawler")
    command = [str(python), "-B", "-m", "ai.jd_adapter", "--mode", "crawler",
               "--snapshot", snapshot, "--profile", profile]
    if args.check:
        return subprocess.run(command + ["--check"], cwd=ROOT, env=settings).returncode
    if not shutil.which("opentunnel"):
        parser.error("OpenTunnel CLI (opentunnel.xyz) is not installed or is not on PATH. See docs/backend/opentunnel.md.")

    children = []
    logs = []
    tunnel_started = False

    def stop(_number, _frame):
        raise KeyboardInterrupt

    previous = {sig: signal.signal(sig, stop) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", 8011))
            except OSError:
                raise RuntimeError("Port 8011 is in use. Stop the existing adapter before retrying.") from None
        check_profile(settings)
        start_local_neo4j(settings)
        log_dir = Path(tempfile.mkdtemp(prefix="guardwork-opentunnel-"))
        print(f"Starting real AI adapter. Private startup logs: {log_dir}", flush=True)
        adapter_log = (log_dir / "adapter.log").open("w")
        logs.append(adapter_log)
        children.append(subprocess.Popen(command, cwd=ROOT, env=settings,
                                         stdout=adapter_log, stderr=subprocess.STDOUT))
        expected = {"status": "ready", "mode": "crawler", "profile": profile, "legalSnapshot": snapshot}
        wait_until(lambda: health("http://127.0.0.1:8011/health") == expected,
                   children, 90, "real adapter readiness")
        tunnel_log = (log_dir / "opentunnel.log").open("w+")
        logs.append(tunnel_log)
        # Mark ownership before starting: certificate provisioning can be
        # interrupted after the CLI has already created its background service.
        tunnel_started = True
        provisioning = subprocess.Popen(tunnel_command("route", "add", TARGET), env=settings,
                                        stdout=tunnel_log, stderr=subprocess.STDOUT)
        children.append(provisioning)
        print("Provisioning OpenTunnel; first-time certificate verification can take several minutes.", flush=True)
        deadline = time.monotonic() + 300
        while provisioning.poll() is None:
            if children[0].poll() is not None:
                raise RuntimeError("The adapter exited while provisioning OpenTunnel. Check adapter.log.")
            if time.monotonic() > deadline:
                raise RuntimeError("OpenTunnel provisioning timed out. Check opentunnel.log and retry later.")
            time.sleep(0.5)
        if provisioning.returncode:
            raise RuntimeError("OpenTunnel provisioning failed. Check opentunnel.log.")
        children.remove(provisioning)
        tunnel_log.seek(0)
        url = tunnel_url(tunnel_log.read())
        wait_until(lambda: health(url + "/health") == expected, children, 60, "public AI readiness")
        print(f"AI service READY: {url}\nHealth: {url}/health\n"
              f"Set GUARDWORK_AI_ADAPTER_URL={url} in your backend process or hosting environment.\n"
              "Use the same shared key, profile and legal snapshot as this adapter.\n"
              "Restart/redeploy the backend to load the URL. Existing files were not updated.\n"
              "Keep this terminal open. Ctrl+C stops this adapter and the guardwork-ai tunnel service.\n"
              "Readiness makes no paid model request.", flush=True)
        while children[0].poll() is None:
            time.sleep(5)
            # OpenTunnel reconnects transient failures itself. A stopped daemon
            # is terminal; public health remains available for remote monitoring.
            result = subprocess.run(tunnel_command("status"), env=settings, capture_output=True,
                                    text=True, check=True, timeout=10)
            if (not re.search(r"(?m)^Service: (?!stopped\b).+\(pid \d+,", result.stdout)
                    or "tunnel stopped after an error" in result.stdout):
                raise RuntimeError("The OpenTunnel service stopped. Check its profile status and private logs.")
        raise RuntimeError("The adapter stopped. Check adapter.log.")
    except KeyboardInterrupt:
        print("Stopping AI adapter and the guardwork-ai OpenTunnel service.", flush=True)
        return 0
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as failure:
        # Subprocess errors can include command/env details; keep the public
        # diagnostic generic and never echo provider output or credentials.
        print(str(failure) if isinstance(failure, RuntimeError) else
              f"Startup/service check failed ({type(failure).__name__}). Check prerequisites and private logs.", flush=True)
        return 1
    finally:
        for sig in previous:
            signal.signal(sig, signal.SIG_IGN)
        for child in reversed(children):
            stop_child(child)
        if tunnel_started:
            try:
                result = subprocess.run(tunnel_command("down"), env=settings, capture_output=True, timeout=15)
                if result.returncode:
                    print("Cleanup failed: run opentunnel --profile guardwork-ai down.", flush=True)
            except (OSError, subprocess.SubprocessError):
                print("Cleanup failed: run opentunnel --profile guardwork-ai down.", flush=True)
        for output in logs:
            output.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == "__main__":
    raise SystemExit(main())
