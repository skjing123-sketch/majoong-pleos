import unittest
from unittest.mock import patch

from backend.breed_catalog import BreedCatalog


class CatalogProvider:
    def __init__(self):
        self.calls = []
        self.fail = False

    def query(self, operation, params):
        self.calls.append((operation, params))
        if self.fail:
            raise RuntimeError("synthetic error")
        if params["up_kind_cd"] == "417000":
            rows = [{"kindCd": "d1", "kindNm": "푸들"}, {"kindCd": "d2", "kindNm": "치와와"},
                    {"kindCd": "d3", "kindNm": "풍산견"}, {"kindCd": "d4", "kindNm": "믹스"}]
        else:
            rows = [{"kindCd": "c1", "kindNm": "한국 고양이"}, {"kindCd": "c2", "kindNm": "믹스"}]
        return rows, len(rows)


class BreedCatalogTests(unittest.TestCase):
    def test_official_name_and_code_mapping_with_ambiguity(self):
        provider = CatalogProvider()
        catalog = BreedCatalog(provider)
        catalog.refresh()
        self.assertEqual(catalog.status, "ready")
        for value in ("푸들", "치와와", "풍산견", "d1"):
            self.assertEqual(catalog.classify(value), "dog")
        self.assertEqual(catalog.classify("한국 고양이"), "cat")
        self.assertEqual(catalog.classify("믹스"), "other")
        self.assertEqual(catalog.classify("알 수 없는 품종"), "other")
        self.assertEqual(catalog.classify("[개] 믹스"), "dog")
        self.assertEqual(catalog.classify("[고양이] 믹스"), "cat")
        self.assertEqual(provider.calls[0][0], "lossInfoKind")
        self.assertEqual(provider.calls[0][1]["numOfRows"], 1000)

    def test_24_hour_cache_then_failure_does_not_reuse_expired_mapping(self):
        provider = CatalogProvider()
        catalog = BreedCatalog(provider)
        with patch("backend.breed_catalog.time.monotonic", return_value=100):
            catalog.refresh()
            catalog.refresh()
        self.assertEqual(len(provider.calls), 2)
        provider.fail = True
        with patch("backend.breed_catalog.time.monotonic", return_value=86501):
            catalog.refresh()
            catalog.refresh()
        self.assertEqual(len(provider.calls), 3)
        self.assertEqual(catalog.classify("푸들"), "other")
        self.assertEqual(catalog.status, "temporarily_unavailable")


if __name__ == "__main__":
    unittest.main()
