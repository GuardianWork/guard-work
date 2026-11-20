import unittest
from datetime import date
import json
from http.server import HTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from ai.jd_adapter import analyze, make_handler
from ai.crawler_backend import LegalAnalyzer


class AdapterContractTest(unittest.TestCase):
    def test_stub_is_explicit_and_never_claims_a_clean_legal_review(self):
        request = {"analysisText": "description:\nGiữ bản chính CCCD.\n", "mode": "stub",
                   "profile": "jd-local-v1", "legalSnapshot": "stub-v1", "asOfDate": "2026-11-10"}
        report = analyze(request, mode="stub", profile="jd-local-v1", snapshot="stub-v1")
        self.assertEqual("NEEDS_REVIEW", report["outcome"])
        self.assertEqual("stub", report["mode"])
        self.assertEqual([], report["findings"])
        self.assertFalse(report["usage"]["available"])

    def test_malformed_detection_is_a_failure_not_a_clean_report(self):
        backend = LegalAnalyzer(generate=lambda *_: "not JSON", retrieve=lambda _: [])
        with self.assertRaises(ValueError):
            backend.analyze("Giữ bản chính CCCD.", date(2026, 11, 10))

    def test_missing_detection_array_and_fabricated_spans_fail(self):
        for response in ('{}', '{"risks":null}', '{"risks":[{"span":"not in JD"}]}'):
            with self.subTest(response=response), self.assertRaises(ValueError):
                LegalAnalyzer(generate=lambda *_: response, retrieve=lambda _: []).analyze(
                    "Một JD thông thường.", date(2026, 11, 10))

    def test_missing_verification_items_fail_instead_of_disappearing(self):
        responses = iter([json.dumps({"risks": [{"span": "Giữ giấy tờ.", "risk_type": "Giấy tờ",
            "severity_hint": "high", "possible_articles": []}]}), '{"results":[]}'])
        with self.assertRaises(ValueError):
            LegalAnalyzer(generate=lambda *_: next(responses), retrieve=lambda _: []).analyze(
                "Giữ giấy tờ.", date(2026, 11, 10))

    def test_fabricated_legal_quotes_are_not_presented_as_citations(self):
        responses = iter([json.dumps({"risks": [{"span": "Giữ giấy tờ.", "risk_type": "Giấy tờ",
            "severity_hint": "high", "possible_articles": []}]}), json.dumps({"results": [{
                "index": 0, "status": "risk", "entailment": "VIOLATES", "node_id": "doc:139264:art:17",
                "quote": "Invented quote", "conflict": "Unsupported claim"}]})])
        report = LegalAnalyzer(generate=lambda *_: next(responses), retrieve=lambda _: [{
            "node_id": "doc:139264:art:17", "text": "Actual legal source", "label": "Điều 17",
            "doc_num": "45/2019/QH14", "eff_status": "", "applicability": "UNVERIFIED"}]).analyze(
                "Giữ giấy tờ.", date(2026, 11, 10))
        self.assertEqual("NEEDS_REVIEW", report["outcome"])
        self.assertEqual([], report["findings"][0]["citations"])
        self.assertNotIn("Unsupported claim", report["findings"][0]["explanation"])

    def test_verification_receives_complete_evidence_and_uncertain_dates_require_review(self):
        responses = iter([
            json.dumps({"risks": [{"span": "Giữ bản chính CCCD.", "risk_type": "Giữ giấy tờ",
                "severity_hint": "high", "possible_articles": ["Điều 17"]}]}),
            json.dumps({"results": [{"index": 0, "status": "risk", "entailment": "VIOLATES",
                "node_id": "doc:139264:art:17", "quote": "Không được giữ bản chính giấy tờ.",
                "conflict": "JD yêu cầu giữ giấy tờ gốc."}]}),
        ])
        evidence = "Nội dung đầy đủ. " * 70 + "Không được giữ bản chính giấy tờ."
        prompts = []
        def generate(system, user):
            prompts.append(user)
            return next(responses)
        backend = LegalAnalyzer(generate=generate, retrieve=lambda _: [{
            "node_id": "doc:139264:art:17", "text": evidence, "label": "Điều 17",
            "doc_num": "45/2019/QH14", "eff_status": "Hết hiệu lực một phần",
            "applicability": "UNVERIFIED"}])
        report = backend.analyze("😀 Giữ bản chính CCCD.", date(2026, 11, 10))
        self.assertIn(evidence, prompts[1])
        self.assertEqual("NEEDS_REVIEW", report["outcome"])
        self.assertEqual(2, report["findings"][0]["start"])
        self.assertTrue(report["findings"][0]["citations"][0]["quoteGrounded"])

    def test_verifier_can_correct_a_wrong_hint_using_another_candidates_shared_source(self):
        source = {"node_id": "doc:139264:art:17", "text": "Giữ giấy tờ. Yêu cầu đặt cọc.",
            "label": "Điều 17", "doc_num": "45/2019/QH14", "eff_status": "",
            "applicability": "UNVERIFIED"}
        candidates = [{"span": span, "risk_type": span, "severity_hint": "high",
                       "possible_articles": [hint]} for span, hint in (
                           ("Giữ giấy tờ.", "Điều 16"), ("Yêu cầu đặt cọc.", "Điều 17"))]
        results = [{"index": index, "status": "risk", "entailment": "VIOLATES",
                    "node_id": source["node_id"], "quote": candidate["span"],
                    "conflict": "Cần đối chiếu với Điều 17."} for index, candidate in enumerate(candidates)]
        responses = iter([json.dumps({"risks": candidates}), json.dumps({"results": results})])
        report = LegalAnalyzer(generate=lambda *_: next(responses),
            retrieve=lambda candidate: [] if candidate["possible_articles"] == ["Điều 16"] else [source]).analyze(
                "Giữ giấy tờ. Yêu cầu đặt cọc.", date(2026, 11, 14))
        self.assertEqual([1, 1], [len(finding["citations"]) for finding in report["findings"]])

    def test_http_service_authenticates_and_rejects_mismatched_configuration(self):
        key = "test-key-not-a-secret"
        handler = make_handler(mode="stub", profile="jd-local-v1", snapshot="stub-v1", key=key)
        with HTTPServer(("127.0.0.1", 0), handler) as server:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{server.server_port}/analyze"
                payload = {"mode": "stub", "profile": "jd-local-v1", "legalSnapshot": "stub-v1",
                           "asOfDate": "2026-11-10", "analysisText": "Một JD thử nghiệm."}
                def call(body, supplied_key):
                    return urlopen(Request(url, data=json.dumps(body).encode(), method="POST",
                        headers={"Content-Type": "application/json", "X-GuardWork-AI-Key": supplied_key}), timeout=3)
                with self.assertRaises(HTTPError) as unauthorized:
                    call(payload, "wrong")
                self.assertEqual(401, unauthorized.exception.code)
                unauthorized.exception.close()
                with call(payload, key) as response:
                    self.assertEqual("NEEDS_REVIEW", json.load(response)["outcome"])
                with self.assertRaises(HTTPError) as mismatch:
                    call({**payload, "mode": "crawler"}, key)
                self.assertEqual(422, mismatch.exception.code)
                mismatch.exception.close()
                with self.assertRaises(HTTPError) as invalid:
                    call([], key)
                self.assertEqual(422, invalid.exception.code)
                invalid.exception.close()
            finally:
                server.shutdown()
                thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
