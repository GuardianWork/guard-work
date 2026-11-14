"""Small, snapshot-scoped BM25 index over full Labor Code articles; no embedding calls."""
from collections import Counter
import math
import re
import unicodedata


def tokens(value):
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFC", value).casefold())


def term_counts(value):
    words = tokens(value)
    # Vietnamese words often span multiple space-separated syllables. Preserve
    # adjacent pairs such as "bản chính" so broad question wording cannot drown
    # out the distinctive legal phrase. No external tokenizer/model is required.
    return Counter([*words, *(f"{left} {right}" for left, right in zip(words, words[1:]))])


class ArticleSearch:
    def __init__(self, articles):
        self.articles = tuple(dict(article) for article in articles)
        self.terms = tuple(term_counts(article["text"]) for article in self.articles)
        self.lengths = tuple(sum(terms.values()) for terms in self.terms)
        self.average = sum(self.lengths) / max(1, len(self.lengths))
        self.frequency = Counter(term for terms in self.terms for term in terms)

    def search(self, question, limit=4):
        query = term_counts(question)
        ranked = []
        for article, terms, length in zip(self.articles, self.terms, self.lengths):
            score = 0.0
            normalization = 1.2 * (0.25 + 0.75 * length / max(self.average, 1))
            for term, query_count in query.items():
                count = terms.get(term, 0)
                if not count:
                    continue
                df = self.frequency[term]
                weight = math.log(1 + (len(self.articles) - df + 0.5) / (df + 0.5))
                score += weight * count * 2.2 / (count + normalization) * (1 + math.log(query_count))
            if score > 0:
                ranked.append((score, article))
        ranked.sort(key=lambda item: (-item[0], item[1]["node_id"]))
        return [dict(article) for _, article in ranked[:limit]]
