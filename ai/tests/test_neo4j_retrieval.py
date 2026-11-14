"""Opt-in regression over the existing imported corpus; never seeds or changes it."""
import os
from pathlib import Path
import unittest

from ai.crawler_backend import CrawlerBackend
from ai.legal_chat import retrieve_articles


@unittest.skipUnless(os.environ.get("GUARDWORK_TEST_NEO4J") == "true", "Requires the imported local Neo4j corpus")
class Neo4jRetrievalTest(unittest.TestCase):
    def test_custom_chat_without_an_article_number_finds_the_labor_source(self):
        backend = CrawlerBackend(Path(__file__).resolve().parents[3] / "crawler")
        try:
            sources = retrieve_articles(backend,
                "Công ty giữ bản chính CCCD của tôi trong thời gian làm việc. Tôi nên kiểm tra quy định nào?")
            self.assertIn("doc:139264:art:17", [source["node_id"] for source in sources])
        finally:
            backend.close()

    def test_wrong_detector_hint_does_not_hide_the_grounded_article(self):
        backend = CrawlerBackend(Path(__file__).resolve().parents[3] / "crawler")
        try:
            with backend.driver.session(database=backend.database) as session:
                sources = backend.retrieve(session,
                    "Ứng viên phải nộp bản chính CCCD và công ty giữ trong suốt thời gian làm việc.",
                    "Giữ giấy tờ", ["Điều 16"])
            correct = next((source for source in sources if source["node_id"] == "doc:139264:art:17"), None)
            self.assertIsNotNone(correct)
            self.assertIn("Giữ bản chính giấy tờ", correct["text"])
        finally:
            backend.close()
