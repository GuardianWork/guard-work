import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from ai import opentunnel_service as service


class OpenTunnelServiceTest(unittest.TestCase):
    def test_configuration_precedence_and_values_are_not_executed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "guard-work"
            (root / "ai").mkdir(parents=True)
            (root / "backend").mkdir()
            (root.parent / "crawler").mkdir()
            (root.parent / "crawler/.env").write_text("PROFILE=crawler\nCRAWLER_ONLY=yes\n")
            (root / "ai/.env.local").write_text("PROFILE=shared\n")
            (root / "ai/.env.opentunnel.local").write_text("PROFILE=tunnel\nTUNNEL_ONLY=yes\n")
            (root / "ai/.env.ngrok.local").write_text("NGROK_ONLY=no\n")
            (root / "backend/.env").write_text('PROFILE=backend\nLITERAL="$(false)"\n')
            settings = service.load_settings(root, {})
            self.assertEqual("backend", settings["PROFILE"])
            self.assertEqual("yes", settings["CRAWLER_ONLY"])
            self.assertEqual("yes", settings["TUNNEL_ONLY"])
            self.assertEqual("$(false)", settings["LITERAL"])
            self.assertNotIn("NGROK_ONLY", settings)
            self.assertEqual("process", service.load_settings(root, {"PROFILE": "process"})["PROFILE"])

    def test_profile_check_refuses_active_unknown_and_unrelated_routes(self):
        outputs = (
            "Service: connected, 0 active connection(s) (pid 42, profile guardwork-ai)\n",
            "A new CLI status format\n",
            "db.host.opentunnel.xyz  →  127.0.0.1:5432\nService: stopped (connect with: opentunnel up)\n",
        )
        for output in outputs:
            with self.subTest(output=output), patch.object(service.subprocess, "run", return_value=Mock(stdout=output)):
                with self.assertRaises(RuntimeError):
                    service.check_profile({})
        with patch.object(service.subprocess, "run", return_value=Mock(stdout=(
                "route.host.opentunnel.xyz  →  127.0.0.1:8011\n"
                "Service: stopped (connect with: opentunnel up)\n"))):
            service.check_profile({})

    def test_route_url_rejects_unexpected_destinations_and_ambiguous_output(self):
        url = "https://random-route.tunnel-id.opentunnel.xyz"
        self.assertEqual(url, service.tunnel_url("Tunnel is ready.\n" + url + "\n"))
        for output in ("https://opentunnel.xyz\n", "https://example.com\n",
                       "http://route.id.opentunnel.xyz\n", url + "/health\n",
                       url + "\n" + url + "\n"):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                service.tunnel_url(output)

    def test_health_checks_refuse_redirects(self):
        self.assertIsNone(service.NoRedirects().redirect_request(None, None, 302, "", {}, "https://example.com"))

    def run_launcher(self, public_failure=False, provisioning_failure=False):
        settings = {"GUARDWORK_AI_LEGAL_SNAPSHOT": "labor-test",
                    "GUARDWORK_AI_PROFILE": "test-v1", "GUARDWORK_AI_SHARED_KEY": "private-test-key-123"}
        adapter = Mock()
        adapter.poll.return_value = None
        provisioning = Mock(returncode=1 if provisioning_failure else 0)
        provisioning.poll.return_value = provisioning.returncode

        def launch(command, **kwargs):
            if command[0] == "opentunnel":
                kwargs["stdout"].write("https://route.tunnel.opentunnel.xyz\n")
                kwargs["stdout"].flush()
                return provisioning
            return adapter

        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(service, "load_settings", return_value=settings))
            stack.enter_context(patch.object(Path, "is_file", return_value=True))
            stack.enter_context(patch.object(service.shutil, "which", return_value="/fake/opentunnel"))
            stack.enter_context(patch.object(service.socket, "socket"))
            stack.enter_context(patch.object(service, "check_profile"))
            stack.enter_context(patch.object(service, "start_local_neo4j"))
            stack.enter_context(patch.object(service.tempfile, "mkdtemp", return_value=directory))
            stack.enter_context(patch.object(service.subprocess, "Popen", side_effect=launch))
            run = stack.enter_context(patch.object(service.subprocess, "run", return_value=Mock(returncode=0)))
            ready = stack.enter_context(patch.object(service, "wait_until", side_effect=(
                [True, RuntimeError("Public health unavailable")] if public_failure else [True, True])))
            stack.enter_context(patch.object(service.time, "sleep", side_effect=KeyboardInterrupt))
            stack.enter_context(contextlib.redirect_stdout(output))
            result = service.main([])
            self.assertNotIn(settings["GUARDWORK_AI_SHARED_KEY"], output.getvalue())
            self.assertEqual(["opentunnel", "--profile", "guardwork-ai", "down"], run.call_args.args[0])
            adapter.terminate.assert_called_once()
            adapter.wait.assert_called_once()
            if not provisioning_failure:
                self.assertEqual(2, ready.call_count)
        return result, output.getvalue()

    def test_success_and_interrupt_stop_owned_service(self):
        result, output = self.run_launcher()
        self.assertEqual(0, result)
        self.assertIn("AI service READY: https://route.tunnel.opentunnel.xyz", output)

    def test_failed_public_readiness_cleans_up_without_advertising_ready(self):
        result, output = self.run_launcher(public_failure=True)
        self.assertEqual(1, result)
        self.assertNotIn("AI service READY", output)

    def test_provisioning_failure_still_disconnects_profile(self):
        result, output = self.run_launcher(provisioning_failure=True)
        self.assertEqual(1, result)
        self.assertNotIn("AI service READY", output)

    def test_active_profile_is_never_stopped(self):
        with patch.object(service, "load_settings", return_value={
                "GUARDWORK_AI_LEGAL_SNAPSHOT": "labor-test", "GUARDWORK_AI_SHARED_KEY": "private-test-key-123"}), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(service.shutil, "which", return_value="/fake/opentunnel"), \
                patch.object(service.socket, "socket"), \
                patch.object(service, "check_profile", side_effect=RuntimeError("Already active")), \
                patch.object(service.subprocess, "run") as run, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, service.main([]))
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
