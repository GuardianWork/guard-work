import json
import unittest
from datetime import date
from http.server import HTTPServer
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from ai.legal_chat import LegalChatAnalyzer
from ai.jd_adapter import make_handler
from ai.article_search import ArticleSearch


class LegalChatTest(unittest.TestCase):
    def test_scoped_ranking_prefers_distinctive_words_over_common_question_wording(self):
        article = {"node_id": "article:17", "text": "Giữ bản chính giấy tờ tùy thân của người lao động."}
        common = [{"node_id": f"article:{index}", "text": "Công ty quy định thời gian làm việc cho người lao động. " * 10}
                  for index in range(20, 30)]
        search = ArticleSearch([article, *common])
        self.assertEqual("article:17", search.search(
            "Công ty giữ bản chính CCCD của tôi trong thời gian làm việc. Tôi nên kiểm tra quy định nào?")[0]["node_id"])
        self.assertEqual([], search.search("?!"))

    def analyze(self, result, provisions=None):
        return LegalChatAnalyzer(generate=lambda *_: json.dumps(result), retrieve=lambda _: (
            provisions if provisions is not None else [{
                "node_id": "doc:139264:art:25", "label": "Điều 25", "doc_num": "45/2019/QH14",
                "text": "Không quá 60 ngày. Chỉ được thử việc một lần.", "eff_status": "Hết hiệu lực một phần",
            }])).chat("Thử việc?", date(2026, 11, 14))

    def test_answer_citations_are_grounded_and_temporal_uncertainty_is_visible(self):
        report = self.analyze({"status": "ANSWERED", "answer": "Theo Điều 25: không quá 60 ngày.",
            "citations": [{"nodeId": "doc:139264:art:25", "quote": "Không quá 60 ngày."}]})
        self.assertEqual("ANSWERED", report["status"])
        self.assertTrue(report["citations"][0]["quoteGrounded"])
        self.assertEqual("UNVERIFIED", report["citations"][0]["applicability"])
        self.assertTrue(report["warnings"])

    def test_unknown_sources_fabricated_quotes_and_unattributed_answers_fail(self):
        for citations in ([], [{"nodeId": "unknown", "quote": "Không quá 60 ngày."}],
                          [{"nodeId": "doc:139264:art:25", "quote": "A fabricated legal quotation"}]):
            with self.subTest(citations=citations), self.assertRaises(ValueError):
                self.analyze({"status": "ANSWERED", "answer": "A model answer", "citations": citations})

    def test_missing_evidence_abstains_without_calling_provider(self):
        report = LegalChatAnalyzer(generate=lambda *_: self.fail("Provider must not be called"),
            retrieve=lambda _: []).chat("Ngoài phạm vi?", date(2026, 11, 14))
        self.assertEqual("INSUFFICIENT_CONTEXT", report["status"])
        self.assertEqual([], report["citations"])

    def test_model_abstention_cannot_carry_an_unvalidated_answer(self):
        report = self.analyze({"status": "INSUFFICIENT_CONTEXT", "answer": "Invented legal advice", "citations": []})
        self.assertNotIn("Invented", report["answer"])
        with self.assertRaises(ValueError):
            self.analyze({"status": "ANSWERED", "answer": "", "citations": []})

    def test_chat_http_requires_auth_real_mode_and_matching_identity(self):
        class Backend:
            def chat(self, question, as_of):
                return {"status": "INSUFFICIENT_CONTEXT", "answer": "Insufficient evidence",
                        "citations": [], "warnings": [], "model": "test-provider"}
        key = "test-key-not-a-secret"
        payload = {"mode": "crawler", "profile": "test-v1", "legalSnapshot": "labor-test",
                   "asOfDate": "2026-11-14", "question": "Thử việc?"}
        for mode in ("stub", "crawler"):
            with HTTPServer(("127.0.0.1", 0), make_handler(mode=mode, profile="test-v1",
                    snapshot="labor-test", key=key, backend=Backend())) as server:
                thread = Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    base = f"http://127.0.0.1:{server.server_port}"
                    with urlopen(base + "/health", timeout=3) as response:
                        self.assertEqual(mode, json.load(response)["mode"])
                    def call(body, supplied_key=key):
                        return urlopen(Request(base + "/chat", data=json.dumps(body).encode(),
                            headers={"Content-Type": "application/json", "X-GuardWork-AI-Key": supplied_key}), timeout=3)
                    with self.assertRaises(HTTPError) as failure:
                        call(payload, "wrong")
                    self.assertEqual(401, failure.exception.code)
                    failure.exception.close()
                    if mode == "stub":
                        with self.assertRaises(HTTPError) as failure:
                            call(payload)
                        self.assertEqual(503, failure.exception.code)
                        failure.exception.close()
                    else:
                        with call(payload) as response:
                            report = json.load(response)
                            self.assertEqual("crawler", report["mode"])
                            self.assertEqual("labor-test", report["legalSnapshot"])
                        for invalid in ({**payload, "legalSnapshot": "wrong"}, {**payload, "question": " "}):
                            with self.assertRaises(HTTPError) as failure:
                                call(invalid)
                            self.assertEqual(422, failure.exception.code)
                            failure.exception.close()
                finally:
                    server.shutdown()
                    thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
