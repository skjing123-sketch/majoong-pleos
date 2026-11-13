"""Behavioral checks using fictional data only; no real API/key access."""

import contextlib
import http.client
import io
import json
import sys
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

from backend.server import (
    APIError, AppState, CASE_TTL, MAX_IMAGE_BYTES, MAX_ROWS, Provider,
    make_server, parse_provider_payload, photo_url_allowed, validate_image,
)


PRIVATE_SENTINEL = "SYNTHETIC_PRIVATE_SENTINEL_DO_NOT_LOG"


def row(area="경기도 성남시 분당구", kind="[개] 믹스견"):
    return {"kindCd": kind, "happenAddr": area, "orgNm": area,
            "callTel": "000-0000-0000", "callName": PRIVATE_SENTINEL,
            "rfidCd": PRIVATE_SENTINEL, "happenDt": "20261022",
            "popfile": "https://www.animal.go.kr/files/fictional.png",
            "specialMark": "가상의 파란색 목줄", "sexCd": "M"}


class FakeProvider:
    configured = True

    def __init__(self, rows=None):
        self.rows = rows if rows is not None else [row()]
        self.calls = []

    def query(self, operation, params):
        self.calls.append((operation, dict(params)))
        if operation == "lossInfoSido":
            return [{"orgdownNm": "경기도", "orgCd": "6410000"},
                    {"orgdownNm": "서울특별시", "orgCd": "6110000"},
                    {"orgdownNm": "부산광역시", "orgCd": "6260000"}], 3
        if operation == "lossInfoSigungu":
            # Synthetic district codes intentionally distinguish identically named districts.
            names = {"6410000": ("성남시", "9991000"), "6110000": ("강서구", "9992000"),
                     "6260000": ("부산광역시 강서구", "9993000")}
            name, code = names[params["upr_cd"]]
            return [{"orgdownNm": name, "orgCd": code, "uprCd": params["upr_cd"]}], 1
        if operation == "lossInfoKind":
            names = ["푸들", "치와와", "풍산견"] if params["up_kind_cd"] == "417000" else ["한국 고양이"]
            return [{"kindNm": name, "kindCd": f"synthetic-{index}-{params['up_kind_cd']}"} for index, name in enumerate(names)], len(names)
        return self.rows, len(self.rows)


class FakeResponse:
    status = 200

    def __init__(self, data):
        self.data = data
        self.read_limit = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, limit):
        self.read_limit = limit
        return self.data[:limit]


class FakeOpener:
    def __init__(self, response=None, error=None):
        self.response, self.error = response, error
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        if self.error:
            raise self.error
        return self.response


class ProviderTests(unittest.TestCase):
    def test_encoded_key_encoded_exactly_once_and_not_returned(self):
        payload = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": [row()]}, "totalCount": 1}}}
        opener = FakeOpener(FakeResponse(json.dumps(payload).encode()))
        provider = Provider("synthetic%2Bkey%2F%3D", opener)
        result, total = provider.query("lossInfo", {"pageNo": 1})
        request = opener.requests[0]
        self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query)["serviceKey"], ["synthetic+key/="])
        self.assertEqual(total, 1)
        self.assertEqual(len(result), 1)

    def test_upstream_error_is_sanitized(self):
        opener = FakeOpener(error=RuntimeError("https://example.invalid/?key=" + PRIVATE_SENTINEL))
        with self.assertRaises(APIError) as caught:
            Provider("synthetic", opener).query("lossInfo", {})
        self.assertNotIn(PRIVATE_SENTINEL, str(caught.exception))
        self.assertNotIn(PRIVATE_SENTINEL, caught.exception.message)
        self.assertEqual(caught.exception.code, "PROVIDER_UNAVAILABLE")

    def test_xml_and_empty_results(self):
        xml = b"<response><header><resultCode>00</resultCode></header><body><items><item><kindCd>fictional</kindCd></item></items><totalCount>1</totalCount></body></response>"
        self.assertEqual(parse_provider_payload(xml), ([{"kindCd": "fictional"}], 1))
        empty = b'{"response":{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}}'
        self.assertEqual(parse_provider_payload(empty), ([], 0))

    def test_breed_catalog_response_is_not_truncated_to_case_page_limit(self):
        rows = [{"kindCd": str(index), "kindNm": "가상 품종 " + str(index)} for index in range(80)]
        payload = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": rows}, "totalCount": 80}}}
        opener = FakeOpener(FakeResponse(json.dumps(payload).encode()))
        result, total = Provider("synthetic", opener).query("lossInfoKind", {"up_kind_cd": "417000", "numOfRows": 1000})
        self.assertEqual(len(result), 80)
        self.assertEqual(total, 80)


class AppTests(unittest.TestCase):
    def test_photo_decoder_missing_never_bypasses_pixel_validation(self):
        with patch.dict(sys.modules, {"PIL": None}):
            with self.assertRaises(APIError) as caught:
                validate_image(b"\x89PNG\r\n\x1a\n" + b"invalid")
        self.assertEqual(caught.exception.status, 503)
        self.assertEqual(caught.exception.code, "IMAGE_VALIDATION_UNAVAILABLE")

    def test_photo_decode_rejects_corruption_and_pixel_limit(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow is required for photo decoding")
        with self.assertRaises(APIError):
            validate_image(b"\x89PNG\r\n\x1a\n" + b"invalid")
        image = io.BytesIO()
        Image.new("RGB", (2, 2), "blue").save(image, format="PNG")
        with patch("backend.server.MAX_IMAGE_PIXELS", 1):
            with self.assertRaises(APIError) as caught:
                validate_image(image.getvalue())
        self.assertEqual(caught.exception.code, "INVALID_IMAGE")

    def test_demo_is_explicit_and_has_no_real_contact(self):
        app = AppState(Provider(""))
        demo = app.get_cases(mode="demo", region="경기")
        self.assertEqual(demo["dataMode"], "demo")
        self.assertEqual(len(demo["cases"]), 3)
        self.assertTrue(all(not case["contact"]["number"] for case in demo["cases"]))
        self.assertTrue(all("가상" in case["features"] for case in demo["cases"]))
        with self.assertRaises(APIError) as caught:
            app.get_cases()
        self.assertEqual(caught.exception.status, 503)

    def test_code_lookup_filter_cache_and_private_fields(self):
        # The provider applied its region code; abbreviated addresses must remain.
        provider = FakeProvider([row(), row("가상 공원 입구")])
        app = AppState(provider)
        result = app.get_cases(region="경기도")
        case_query = next(params for operation, params in provider.calls if operation == "lossInfo")
        self.assertEqual(case_query["upr_cd"], "6410000")
        self.assertEqual(result["meta"]["filterKind"], "provider_codes")
        self.assertFalse(result["meta"]["textFilterApplied"])
        self.assertEqual(len(result["cases"]), 2)
        case = result["cases"][0]
        self.assertEqual(case["status"], "unknown")
        self.assertTrue(case["photoUrl"].startswith("/api/photo?id=p"))
        self.assertNotIn(PRIVATE_SENTINEL, json.dumps(result))
        self.assertNotIn("/files/fictional.png", json.dumps(result))
        self.assertEqual(case["reportedDate"], "2026-10-22")
        again = app.get_cases(region="경기도")
        self.assertTrue(again["meta"]["cached"])
        self.assertEqual(len(provider.calls), 4)

    def test_region_matches_address_not_arbitrary_features(self):
        unrelated = row("부산광역시 해운대구")
        unrelated["specialMark"] = "분당구에서 입양한 가상 강아지"
        app = AppState(FakeProvider([row(), unrelated]))
        result = app.get_cases(region="분당구")
        self.assertEqual(len(result["cases"]), 1)
        self.assertEqual(result["meta"]["filterKind"], "administrative_text")
        self.assertIn("반경 검색이 아닙니다", result["meta"]["coverageNote"])

    def test_seongnam_resolves_province_and_city_codes_before_paging(self):
        provider = FakeProvider([row()])
        app = AppState(provider)
        result = app.get_cases(region="성남시")
        self.assertEqual([call[0] for call in provider.calls], ["lossInfoSido", "lossInfoSigungu", "lossInfo", "lossInfoKind", "lossInfoKind"])
        case_query = next(params for operation, params in provider.calls if operation == "lossInfo")
        self.assertEqual(case_query["upr_cd"], "6410000")
        self.assertEqual(case_query["org_cd"], "9991000")
        self.assertEqual(result["meta"]["filterKind"], "provider_codes")
        self.assertEqual(len(result["cases"]), 1)
        app.get_cases(region="성남시")
        self.assertEqual(len(provider.calls), 5)

    def test_exact_city_code_keeps_abbreviated_addresses_and_maps_bare_breeds(self):
        provider = FakeProvider([row("가상 공원", "푸들"), row("가상 아파트", "치와와"),
                                 row("가상 거리", "한국 고양이"), row("가상 산책로", "풍산견")])
        result = AppState(provider).get_cases(region="성남시")
        self.assertEqual(len(result["cases"]), 4)
        self.assertEqual([case["species"] for case in result["cases"]], ["dog", "dog", "cat", "dog"])
        self.assertEqual(result["meta"]["speciesLookupStatus"], "ready")
        self.assertFalse(result["meta"]["textFilterApplied"])

    def test_city_hint_without_exact_district_code_retains_address_filter(self):
        provider = FakeProvider([row(), row("경기도 수원시")])
        result = AppState(provider).get_cases(region="성남시 분당구")
        self.assertTrue(result["meta"]["textFilterApplied"])
        self.assertEqual(len(result["cases"]), 1)
        case_query = next(params for operation, params in provider.calls if operation == "lossInfo")
        self.assertEqual(case_query["upr_cd"], "6410000")
        self.assertNotIn("org_cd", case_query)

    def test_province_prefix_distinguishes_same_named_seoul_and_busan_districts(self):
        provider = FakeProvider([row("가상 공원 입구", "푸들")])
        app = AppState(provider)
        seoul = app.get_cases(region="서울특별시 강서구")
        busan = app.get_cases(region="부산광역시 강서구")
        queries = [params for operation, params in provider.calls if operation == "lossInfo"]
        self.assertEqual([(q["upr_cd"], q["org_cd"]) for q in queries],
                         [("6110000", "9992000"), ("6260000", "9993000")])
        for result in (seoul, busan):
            self.assertFalse(result["meta"]["textFilterApplied"])
            self.assertEqual(len(result["cases"]), 1)

    def test_prefixed_seongnam_and_manual_seongnam_share_official_scope(self):
        provider = FakeProvider([row("가상 장소", "푸들")])
        app = AppState(provider)
        prefixed = app.get_cases(region="경기도 성남시")
        manual = app.get_cases(region="성남시")
        self.assertEqual(prefixed["cases"], manual["cases"])
        self.assertFalse(prefixed["meta"]["textFilterApplied"])
        self.assertTrue(manual["meta"]["cached"])
        self.assertEqual(sum(operation == "lossInfo" for operation, _ in provider.calls), 1)

    def test_unresolved_prefixed_district_retains_full_province_address_match(self):
        provider = FakeProvider([row("서울특별시 양천구", "푸들"), row("부산광역시 양천구", "푸들")])
        result = AppState(provider).get_cases(region="서울특별시 양천구")
        self.assertTrue(result["meta"]["textFilterApplied"])
        self.assertEqual(len(result["cases"]), 1)
        query = next(params for operation, params in provider.calls if operation == "lossInfo")
        self.assertEqual(query["upr_cd"], "6110000")
        self.assertNotIn("org_cd", query)

    def test_missing_upstream_case_disappears_after_cache_refresh(self):
        provider = FakeProvider([row()])
        app = AppState(provider)
        self.assertEqual(len(app.get_cases()["cases"]), 1)
        provider.rows = []
        with patch("backend.server.time.monotonic", return_value=time.monotonic() + CASE_TTL + 2):
            result = app.get_cases()
        self.assertEqual(result["cases"], [])
        self.assertIn("확인되지", result["meta"]["completionNotice"])

    def test_missing_optional_fields_do_not_invent_a_photo_contact_or_resolution(self):
        case = AppState(FakeProvider([{}])).get_cases()["cases"][0]
        self.assertEqual(case["species"], "other")
        self.assertEqual(case["status"], "unknown")
        self.assertEqual(case["area"], "")
        self.assertEqual(case["reportedDate"], "")
        self.assertIsNone(case["photoUrl"])
        self.assertFalse(case["contact"]["available"])
        self.assertEqual(case["contact"]["number"], "")

    def test_outage_after_cache_expiry_does_not_return_stale_or_demo_cases(self):
        provider = FakeProvider([row()])
        app = AppState(provider)
        self.assertEqual(len(app.get_cases()["cases"]), 1)
        with patch("backend.server.time.monotonic", return_value=time.monotonic() + CASE_TTL + 2):
            with patch.object(provider, "query", side_effect=APIError(502, "PROVIDER_UNAVAILABLE", "연결 실패")):
                with self.assertRaises(APIError) as caught:
                    app.get_cases()
        self.assertEqual(caught.exception.code, "PROVIDER_UNAVAILABLE")
        self.assertFalse(app.case_cache)

    def test_expired_photo_reference_does_not_request_or_return_the_old_image(self):
        opener = FakeOpener()
        app = AppState(FakeProvider(), photo_opener=opener)
        case = app.get_cases()["cases"][0]
        token = urllib.parse.parse_qs(urllib.parse.urlsplit(case["photoUrl"]).query)["id"][0]
        with patch("backend.server.time.monotonic", return_value=time.monotonic() + 1802):
            with self.assertRaises(APIError) as caught:
                app.get_photo(token)
        self.assertEqual(caught.exception.code, "PHOTO_EXPIRED")
        self.assertEqual(opener.requests, [])
        self.assertNotIn(token, app.photos)

    def test_idle_expiry_removes_raw_case_and_photo_memory(self):
        app = AppState(FakeProvider())
        app.get_cases()
        self.assertTrue(app.case_cache)
        self.assertTrue(app.photos)
        with patch("backend.server.time.monotonic", return_value=time.monotonic() + 1900):
            app.expire_memory_cache()
        self.assertFalse(app.case_cache)
        self.assertFalse(app.photos)

    def test_row_and_page_bounds(self):
        app = AppState(FakeProvider([row()] * (MAX_ROWS + 5)))
        self.assertEqual(len(app.get_cases()["cases"]), MAX_ROWS)
        with self.assertRaises(APIError):
            app.get_cases(page=4)

    def test_photo_url_policy(self):
        self.assertTrue(photo_url_allowed("http://www.animal.go.kr/a.png").startswith("https://"))
        for url in ("https://evil.invalid/x", "https://animal.go.kr.evil.invalid/x", "file:///tmp/a", "https://www.animal.go.kr/a?serviceKey=secret", "https://user:secret@www.animal.go.kr/a", "https://127.0.0.1/a"):
            self.assertEqual(photo_url_allowed(url), "")

    def test_photo_proxy_correct_mime_memory_cache_and_limits(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Optional Pillow is needed to build the synthetic PNG fixture")
        image = io.BytesIO()
        Image.new("RGB", (2, 2), "blue").save(image, format="PNG")
        opener = FakeOpener(FakeResponse(image.getvalue()))
        app = AppState(FakeProvider(), photo_opener=opener)
        case = app.get_cases()["cases"][0]
        token = urllib.parse.parse_qs(urllib.parse.urlsplit(case["photoUrl"]).query)["id"][0]
        data, mime = app.get_photo(token)
        self.assertEqual(mime, "image/png")
        self.assertEqual(data, image.getvalue())
        app.get_photo(token)
        self.assertEqual(len(opener.requests), 1)
        self.assertIsNone(opener.requests[0].get_header("Authorization"))
        self.assertNotIn("serviceKey", opener.requests[0].full_url)
        oversize = FakeOpener(FakeResponse(b"x" * (MAX_IMAGE_BYTES + 1)))
        app2 = AppState(FakeProvider(), photo_opener=oversize)
        token2 = urllib.parse.parse_qs(urllib.parse.urlsplit(app2.get_cases()["cases"][0]["photoUrl"]).query)["id"][0]
        with self.assertRaises(APIError) as caught:
            app2.get_photo(token2)
        self.assertEqual(caught.exception.code, "PHOTO_TOO_LARGE")


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        (root / "index.html").write_text("<!doctype html><p>prototype</p>")
        (root / "offline-demo.json").write_text('{"dataMode":"demo","cases":[]}')
        (root / "other.json").write_text('{}')
        self.app = AppState(Provider(""), web_root=root)
        self.server = make_server(0, self.app)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, path, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        conn.request("GET", path, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), data
        conn.close()
        return result

    def test_static_health_and_no_automatic_demo_fallback(self):
        self.assertEqual(self.request("/")[0], 200)
        status, headers, data = self.request("/api/health")
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(data)["keyConfigured"])
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        status, _, data = self.request("/api/cases")
        self.assertEqual(status, 503)
        self.assertEqual(json.loads(data)["error"]["code"], "API_KEY_MISSING")
        self.assertEqual(self.request("/api/cases?mode=demo")[0], 200)

    def test_health_revision_freezes_startup_identity_without_disclosing_location(self):
        before = json.loads(self.request("/api/health")[2])["uiRevision"]
        self.assertRegex(before, r"^[a-f0-9]{64}$")
        (Path(self.directory.name) / "style.css").write_text("body{color:blue}")
        after = json.loads(self.request("/api/health")[2])["uiRevision"]
        self.assertEqual(before, after)
        self.assertNotIn(self.directory.name, after)

    def test_external_origin_host_and_traversal_rejected(self):
        for headers in ({"Host": "evil.invalid"}, {"Origin": "https://evil.invalid"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(self.request("/api/health", headers)[0], 403)
        emulator_host = f"10.0.2.2:{self.server.server_port}"
        self.assertEqual(self.request("/api/health", {"Host": emulator_host, "Origin": "http://" + emulator_host})[0], 200)
        self.assertEqual(self.request("/%2e%2e/backend/server.py")[0], 404)

    def test_cross_site_document_navigation_allowed_but_api_reads_stay_blocked(self):
        headers = {"Sec-Fetch-Site": "cross-site", "Sec-Fetch-Mode": "navigate", "Sec-Fetch-Dest": "document"}
        self.assertEqual(self.request("/", headers)[0], 200)
        self.assertEqual(self.request("/api/health", headers)[0], 403)
        self.assertEqual(self.request("/api/cases?mode=demo", headers)[0], 403)
        self.assertEqual(self.request("/", dict(headers, Host="evil.invalid"))[0], 403)
        self.assertEqual(self.request("/", dict(headers, **{"Sec-Fetch-Dest": "iframe"}))[0], 403)
        self.assertEqual(self.request("/offline-demo.json")[0], 200)
        self.assertEqual(self.request("/other.json")[0], 404)

    def test_query_values_are_not_logged_or_echoed(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            status, _, data = self.request("/api/cases?page=" + PRIVATE_SENTINEL)
        self.assertEqual(status, 400)
        self.assertNotIn(PRIVATE_SENTINEL, data.decode())
        self.assertNotIn(PRIVATE_SENTINEL, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
