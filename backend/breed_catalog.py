"""Resolve species from the official breed catalog, without guessing a dog default."""

import re
import time


def normalized_breed(value):
    if not isinstance(value, str):
        return ""
    value = re.sub(r"^\s*\[(?:개|고양이)\]\s*", "", value)
    return re.sub(r"\s+", "", value).casefold()[:100]


class BreedCatalog:
    def __init__(self, provider, ttl=86400):
        self.provider = provider
        self.ttl = ttl
        self.expires = 0
        self.mapping = {}
        self.status = "not_loaded"

    def refresh(self):
        now = time.monotonic()
        if self.expires > now:
            return
        candidates = {}
        complete = True
        try:
            for species, code in (("dog", "417000"), ("cat", "422400")):
                rows, total = self.provider.query(
                    "lossInfoKind", {"up_kind_cd": code, "pageNo": 1, "numOfRows": 1000}
                )
                complete = complete and total <= len(rows)
                for row in rows[:1000]:
                    for field in ("kindNm", "kindCd"):
                        key = normalized_breed(row.get(field))
                        if key:
                            candidates.setdefault(key, set()).add(species)
        except Exception:
            # A catalog outage must not hide otherwise valid lost-animal reports.
            # Discard expired evidence and retain unknown rather than guessing.
            self.mapping = {}
            self.status = "temporarily_unavailable"
            self.expires = now + 60
            return
        self.mapping = {key: next(iter(values)) if len(values) == 1 else "other"
                        for key, values in candidates.items()}
        self.status = "ready" if complete else "partial"
        self.expires = now + self.ttl

    def classify(self, value):
        if isinstance(value, str):
            if re.match(r"^\s*\[개\]", value):
                return "dog"
            if re.match(r"^\s*\[고양이\]", value):
                return "cat"
        return self.mapping.get(normalized_breed(value), "other")
