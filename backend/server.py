#!/usr/bin/env python3
"""Loopback-only, privacy-conscious development server.

Run: DATA_GO_KR_SERVICE_KEY=<local environment value> python3 -m backend.server
Never put an actual key in source or a shell command saved in history.
This module does not write provider responses, images, or credentials to disk.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
from datetime import datetime, timedelta
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import mimetypes
import os
from pathlib import Path
import re
import secrets
import threading
import time
from typing import Any
import urllib.error
import urllib.parse
import urllib.request
import warnings
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

from .breed_catalog import BreedCatalog


BASE_URL = "https://apis.data.go.kr/1543061/lossInfoService/"
SOURCE_URL = "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000"
KST = ZoneInfo("Asia/Seoul")
CASE_TTL = 300
PHOTO_TTL = 1800
MAX_ROWS = 50
MAX_PAGE = 3
MAX_API_BYTES = 2 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_IMAGE_CACHE_BYTES = 32 * 1024 * 1024
MAX_PHOTO_ENTRIES = 200
ALLOWED_HOSTS = {"127.0.0.1", "localhost", "10.0.2.2"}
COMPLETION_NOTICE = "이 API에는 발견 완료 상태가 없어 현재도 찾는 중인지 확인되지 않습니다. 공고의 최신 상태는 공식 원문 또는 신고자에게 확인하세요."
REGION_ALIASES = {
    "서울": "서울특별시", "서울시": "서울특별시",
    "경기": "경기도", "부산": "부산광역시", "부산시": "부산광역시",
    "성남": "성남시",
}
# A city-name hint narrows this prototype's destination before paging. Actual
# province/district codes are always resolved from the provider, never invented.
REGION_PROVINCE_HINTS = {"성남시": "경기도"}


class APIError(Exception):
    def __init__(self, status: int, code: str, message: str):
        # Never put upstream URLs, bodies, exception strings, or keys here.
        super().__init__(code)
        self.status, self.code, self.message = status, code, message


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def clean_text(value: Any, limit: int = 300) -> str:
    if not isinstance(value, (str, int, float)):
        return ""
    return re.sub(r"[\x00-\x1f\x7f]", " ", str(value)).strip()[:limit]


def canonical_region(value: str) -> str:
    value = clean_text(value, 80)
    if value in ("", "전체", "전국", "all"):
        return ""
    words = value.split()
    if words:
        words[0] = REGION_ALIASES.get(words[0], words[0])
    return " ".join(words)


def region_matches(region: str, area: str) -> bool:
    if not region:
        return True
    normalized = area
    # Provider addresses sometimes use short province names.
    for short, full in REGION_ALIASES.items():
        normalized = re.sub(r"(?<!\S)" + re.escape(short) + r"(?=\s|$)", full, normalized)
    return region in normalized


def _xml_value(node):
    if not list(node):
        return (node.text or "").strip()
    result = {}
    for child in node:
        key = child.tag.rsplit("}", 1)[-1]
        value = _xml_value(child)
        if key in result:
            if not isinstance(result[key], list):
                result[key] = [result[key]]
            result[key].append(value)
        else:
            result[key] = value
    return result


def parse_provider_payload(raw: bytes, max_rows=MAX_ROWS) -> tuple[list[dict], int]:
    try:
        text = raw.decode("utf-8-sig")
        if text.lstrip().startswith("<"):
            root = ET.fromstring(text)
            parsed = {root.tag.rsplit("}", 1)[-1]: _xml_value(root)}
        else:
            parsed = json.loads(text)
        response = parsed.get("response", parsed)
        header, body = response.get("header", {}), response.get("body", {})
        code = str(header.get("resultCode", ""))
        if code not in ("00", "0", "000"):
            if "OpenAPI_ServiceResponse" in parsed:
                raise APIError(502, "PROVIDER_AUTH_REJECTED", "공공데이터 인증이 거절되었습니다. 활용신청 승인과 키 적용 상태를 확인하세요.")
            raise APIError(502, "PROVIDER_REJECTED", "공공데이터 서비스가 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.")
        items = body.get("items", {})
        if isinstance(items, dict):
            items = items.get("item", [])
        if isinstance(items, dict):
            items = [items]
        if items in (None, ""):
            items = []
        if not isinstance(items, list) or any(not isinstance(item, dict) for item in items):
            raise ValueError("shape")
        total_text = str(body.get("totalCount", ""))
        total = int(total_text) if re.fullmatch(r"\d{1,12}", total_text) else len(items)
        return items[:max_rows], total
    except APIError:
        raise
    except Exception:
        raise APIError(502, "PROVIDER_FORMAT_ERROR", "공공데이터 응답을 읽을 수 없습니다. 잠시 후 다시 시도하세요.") from None


class Provider:
    def __init__(self, key: str | None = None, opener=None):
        supplied = os.environ.get("DATA_GO_KR_SERVICE_KEY", "") if key is None else key
        supplied = supplied.strip()
        self._key = urllib.parse.unquote(supplied) if re.search(r"%[0-9a-fA-F]{2}", supplied) else supplied
        self.opener = opener or urllib.request.build_opener(NoRedirect)

    @property
    def configured(self):
        return bool(self._key)

    def query(self, operation: str, params: dict) -> tuple[list[dict], int]:
        if not self.configured:
            raise APIError(503, "API_KEY_MISSING", "실제 공고를 보려면 서버의 공공데이터 인증키를 설정하세요. 데모 모드는 별도로 사용할 수 있습니다.")
        if operation not in {"lossInfo", "lossInfoSido", "lossInfoSigungu", "lossInfoKind"}:
            raise APIError(400, "INVALID_OPERATION", "지원하지 않는 조회입니다.")
        query = dict(params, serviceKey=self._key, _type="json")
        request = urllib.request.Request(BASE_URL + operation + "?" + urllib.parse.urlencode(query))
        try:
            with self.opener.open(request, timeout=15) as response:
                if response.status != 200:
                    raise APIError(502, "PROVIDER_UNAVAILABLE", "공공데이터 서비스에 연결하지 못했습니다.")
                raw = response.read(MAX_API_BYTES + 1)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                raise APIError(502, "PROVIDER_AUTH_REJECTED", "공공데이터 인증이 거절되었습니다. 활용신청 승인과 키 적용 상태를 확인하세요.") from None
            raise APIError(502, "PROVIDER_UNAVAILABLE", "공공데이터 서비스에 연결하지 못했습니다. 잠시 후 다시 시도하세요.") from None
        except APIError:
            raise
        except Exception:
            raise APIError(502, "PROVIDER_UNAVAILABLE", "공공데이터 서비스에 연결하지 못했습니다. 잠시 후 다시 시도하세요.") from None
        if len(raw) > MAX_API_BYTES:
            raise APIError(502, "PROVIDER_RESPONSE_TOO_LARGE", "공공데이터 응답이 허용 크기를 초과했습니다.")
        return parse_provider_payload(raw, max_rows=1000 if operation == "lossInfoKind" else MAX_ROWS)


def photo_url_allowed(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        host = (parsed.hostname or "").lower()
        approved = any(host == domain or host.endswith("." + domain)
                       for domain in ("animal.go.kr", "epis.or.kr"))
        names = {name.lower() for name, _ in urllib.parse.parse_qsl(parsed.query)}
        if (not approved or parsed.scheme not in ("http", "https") or parsed.username
                or parsed.password or parsed.port not in (None, 80, 443)
                or names & {"servicekey", "key", "apikey", "api_key", "token", "access_token", "authorization"}):
            return ""
        return urllib.parse.urlunsplit(("https", host, parsed.path, parsed.query, ""))
    except Exception:
        return ""


def image_mime(data: bytes) -> str:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    return ""


def validate_image(data: bytes) -> str:
    mime = image_mime(data)
    if not mime:
        raise APIError(502, "INVALID_IMAGE", "표시할 수 없는 사진 형식입니다.")
    try:
        from PIL import Image
    except ImportError:
        # Never silently skip the pixel/decode limits on a runtime missing the decoder.
        raise APIError(503, "IMAGE_VALIDATION_UNAVAILABLE", "사진을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.") from None
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if not 0 < image.width * image.height <= MAX_IMAGE_PIXELS:
                    raise ValueError("pixel limit")
                image.verify()
            with Image.open(io.BytesIO(data)) as image:
                if not 0 < image.width * image.height <= MAX_IMAGE_PIXELS:
                    raise ValueError("pixel limit")
                image.load()
    except Exception:
        raise APIError(502, "INVALID_IMAGE", "사진 파일을 읽을 수 없습니다.") from None
    return mime


def fictional_cases():
    """Explicit fixtures: no real individuals, contact numbers, or source photos."""
    return [
        {"id": "demo-dog-bori", "species": "dog", "breed": "믹스견", "sex": "수컷", "age": "3살", "color": "밝은 갈색", "features": "가상 사례 · 귀 끝이 짙은 갈색이고 초록색 목줄을 착용했습니다.", "reportedDate": "2026-10-22", "area": "경기도 성남시 분당구", "demoName": "보리"},
        {"id": "demo-cat-nabi", "species": "cat", "breed": "코리안 쇼트헤어", "sex": "암컷", "age": "2살", "color": "삼색", "features": "가상 사례 · 코 옆에 검은 점이 있고 낯선 소리에 쉽게 숨습니다.", "reportedDate": "2026-10-21", "area": "경기도 성남시 수정구", "demoName": "나비"},
        {"id": "demo-dog-dubu", "species": "dog", "breed": "말티즈", "sex": "수컷", "age": "5살", "color": "흰색", "features": "가상 사례 · 작은 체구이며 파란색 하네스를 착용했습니다.", "reportedDate": "2026-10-20", "area": "경기도 성남시 중원구", "demoName": "두부"},
        {"id": "demo-cat-dal", "species": "cat", "breed": "코리안 쇼트헤어", "sex": "수컷", "age": "4살", "color": "검정과 흰색", "features": "가상 사례 · 앞발 끝이 흰색이고 꼬리가 길어요.", "reportedDate": "2026-10-22", "area": "서울특별시 성동구", "demoName": "달이"},
        {"id": "demo-dog-hae", "species": "dog", "breed": "시바견", "sex": "암컷", "age": "2살", "color": "갈색", "features": "가상 사례 · 꼬리가 말려 있고 빨간 인식표를 달고 있습니다.", "reportedDate": "2026-10-21", "area": "부산광역시 해운대구", "demoName": "해리"},
    ]


class AppState:
    def __init__(self, provider=None, web_root=None, photo_opener=None):
        self.provider = provider or Provider()
        self.breed_catalog = BreedCatalog(self.provider)
        self.web_root = (Path(web_root) if web_root else Path(__file__).resolve().parents[1] / "web").resolve()
        self.photo_opener = photo_opener or urllib.request.build_opener(NoRedirect)
        self.lock = threading.RLock()
        self.fetch_lock = threading.Lock()
        self.photo_lock = threading.Lock()
        self.secret = secrets.token_bytes(32)
        self.case_cache = OrderedDict()
        self.sido_cache = (0, {})
        self.sigungu_cache = {}
        self.photos = OrderedDict()

    def _prune(self):
        now = time.monotonic()
        for token, entry in list(self.photos.items()):
            if entry["expires"] < now:
                del self.photos[token]
        while len(self.photos) > MAX_PHOTO_ENTRIES:
            self.photos.popitem(last=False)
        while sum(len(entry.get("data", b"")) for entry in self.photos.values()) > MAX_IMAGE_CACHE_BYTES:
            # Retain the opaque reference while evicting downloaded image bytes.
            for entry in self.photos.values():
                if "data" in entry:
                    del entry["data"]
                    entry.pop("mime", None)
                    break

    def expire_memory_cache(self):
        """Drop expired provider data, including when the browser is idle."""
        if self.fetch_lock.acquire(blocking=False):
            try:
                now = time.monotonic()
                for cache_key, entry in list(self.case_cache.items()):
                    if entry[0] <= now:
                        del self.case_cache[cache_key]
            finally:
                self.fetch_lock.release()
        if self.lock.acquire(blocking=False):
            try:
                self._prune()
            finally:
                self.lock.release()

    def _map_case(self, row):
        # Session-scoped HMAC makes an opaque token, without exposing RFID/contacts.
        digest = hmac.new(self.secret, json.dumps(row, sort_keys=True, ensure_ascii=False).encode(), hashlib.sha256).hexdigest()[:32]
        token = "p" + digest
        photo_source = photo_url_allowed(clean_text(row.get("popfile"), 3000))
        if photo_source:
            with self.lock:
                if token not in self.photos:
                    self.photos[token] = {"url": photo_source, "expires": time.monotonic() + PHOTO_TTL}
                else:
                    self.photos[token]["expires"] = time.monotonic() + PHOTO_TTL
                self._prune()
        kind = clean_text(row.get("kindCd"), 100)
        species = self.breed_catalog.classify(kind)
        phone = clean_text(row.get("callTel"), 64)
        masked = bool(re.search(r"[*●•Xx]", phone))
        date_value = clean_text(row.get("happenDt"), 16)
        reported_date = "-".join((date_value[:4], date_value[4:6], date_value[6:8])) if re.fullmatch(r"\d{8}", date_value) else date_value
        area = clean_text(row.get("happenAddr"), 160) or clean_text(row.get("orgNm"), 160)
        return {
            "id": "live-" + digest, "species": species, "breed": kind,
            "sex": {"M": "수컷", "F": "암컷"}.get(clean_text(row.get("sexCd")), "미상"),
            "age": clean_text(row.get("age"), 50), "color": clean_text(row.get("colorCd"), 80),
            "features": clean_text(row.get("specialMark"), 700),
            "reportedDate": reported_date, "lostDate": reported_date,
            "dateLabel": "신고일", "area": area,
            "photoUrl": "/api/photo?id=" + token if photo_source else None,
            "contact": {"number": phone, "masked": masked, "available": bool(phone), "label": "신고자 연락처"},
            "status": "unknown", "sourceUrl": SOURCE_URL,
        }

    def _sido_code(self, region):
        if not region:
            return ""
        now = time.monotonic()
        expires, mapping = self.sido_cache
        if expires < now:
            rows, _ = self.provider.query("lossInfoSido", {"pageNo": 1, "numOfRows": 30})
            mapping = {clean_text(row.get("orgdownNm"), 80): clean_text(row.get("orgCd"), 12) for row in rows}
            mapping = {name: code for name, code in mapping.items() if re.fullmatch(r"\d{1,12}", code)}
            self.sido_cache = (now + 86400, mapping)
        return mapping.get(region, "")

    def _sigungu_code(self, sido, region):
        now = time.monotonic()
        expires, mapping = self.sigungu_cache.get(sido, (0, {}))
        if expires < now:
            rows, _ = self.provider.query("lossInfoSigungu", {"upr_cd": sido, "pageNo": 1, "numOfRows": MAX_ROWS})
            mapping = {clean_text(row.get("orgdownNm"), 80): clean_text(row.get("orgCd"), 12) for row in rows}
            mapping = {name: code for name, code in mapping.items() if re.fullmatch(r"\d{1,12}", code)}
            self.sigungu_cache[sido] = (now + 86400, mapping)
        # The lookup is scoped by province first. Accept an exact district name
        # or that same name preceded by its province, but not a partial name.
        matches = {code for name, code in mapping.items()
                   if name == region or name.endswith(" " + region)}
        return next(iter(matches)) if len(matches) == 1 else ""

    def _resolve_region_codes(self, region):
        direct_sido = self._sido_code(region)
        if direct_sido:
            return direct_sido, "", True
        words = region.split()
        if len(words) > 1:
            province = self._sido_code(words[0])
            if province:
                district = self._sigungu_code(province, " ".join(words[1:]))
                return province, district, bool(district)
        # Preserve the existing manual Seongnam selection without requiring its
        # province in the UI. Ambiguous bare districts receive no guessed code.
        hint = REGION_PROVINCE_HINTS.get(words[0], "") if words else ""
        if hint:
            province = self._sido_code(hint)
            if province:
                district = self._sigungu_code(province, region)
                return province, district, bool(district)
        return "", "", False

    def get_cases(self, mode="live", region="", sido="", sigungu="", page=1):
        if mode not in ("demo", "live"):
            raise APIError(400, "INVALID_MODE", "데이터 모드는 demo 또는 live여야 합니다.")
        if not 1 <= page <= MAX_PAGE:
            raise APIError(400, "INVALID_PAGE", "페이지는 1부터 3까지 조회할 수 있습니다.")
        for code in (sido, sigungu):
            if code and not re.fullmatch(r"\d{1,12}", code):
                raise APIError(400, "INVALID_REGION_CODE", "행정구역 코드 형식이 올바르지 않습니다.")
        if sigungu and not sido:
            raise APIError(400, "SIDO_REQUIRED", "시군구 코드 조회에는 시도 코드도 필요합니다.")
        region = canonical_region(region)
        now = datetime.now(KST)
        end = now.date()
        start = end - timedelta(days=29)
        filter_kind = "administrative_text" if region else "none"
        text_filter_required = bool(region)
        meta = {"fetchedAt": now.isoformat(timespec="seconds"), "windowStart": start.isoformat(), "windowEnd": end.isoformat(), "regionFilter": region, "completionNotice": COMPLETION_NOTICE, "page": page, "pageSize": MAX_ROWS}
        if mode == "demo":
            cases = fictional_cases()
            for item in cases:
                item.update({"lostDate": item["reportedDate"], "dateLabel": "신고일", "photoUrl": None,
                             "contact": {"number": "", "masked": False, "available": False, "label": "가상 사례 · 연락처 없음"},
                             "status": "unknown", "sourceUrl": SOURCE_URL})
            cases = [item for item in cases if region_matches(region, item["area"])]
            meta.update({"returnedCount": len(cases), "providerTotalCount": None, "filterKind": filter_kind,
                         "coverageNote": "모든 내용이 가상인 시연용 공고입니다. 실제 실종 신고나 연락처가 아닙니다.", "cached": False})
            return {"dataMode": "demo", "cases": cases, "meta": meta}
        if not self.provider.configured:
            raise APIError(503, "API_KEY_MISSING", "실제 공고를 보려면 서버의 공공데이터 인증키를 설정하세요. 데모 모드는 별도로 사용할 수 있습니다.")
        # One upstream request at a time and a short memory cache bound API usage.
        with self.fetch_lock:
            for old_key, entry in list(self.case_cache.items()):
                if entry[0] <= time.monotonic():
                    del self.case_cache[old_key]
            if not sido and region:
                sido, sigungu, exact_region_code = self._resolve_region_codes(region)
                # An exact official province/city code already applies the
                # requested administrative filter. A free-text address may be
                # abbreviated or omit that region, so do not filter it again.
                if exact_region_code:
                    text_filter_required = False
            cache_key = (sido, sigungu, page, start.isoformat(), end.isoformat())
            cached = self.case_cache.get(cache_key)
            if cached and cached[0] > time.monotonic():
                rows, total, fetched_at = cached[1:]
                was_cached = True
            else:
                params = {"bgnde": start.strftime("%Y%m%d"), "ended": end.strftime("%Y%m%d"), "pageNo": page, "numOfRows": MAX_ROWS}
                if sido:
                    params["upr_cd"] = sido
                if sigungu:
                    params["org_cd"] = sigungu
                rows, total = self.provider.query("lossInfo", params)
                rows = rows[:MAX_ROWS]
                fetched_at = datetime.now(KST).isoformat(timespec="seconds")
                self.case_cache[cache_key] = (time.monotonic() + CASE_TTL, rows, total, fetched_at)
                self.case_cache.move_to_end(cache_key)
                while len(self.case_cache) > 12:
                    self.case_cache.popitem(last=False)
                was_cached = False
            if rows:
                self.breed_catalog.refresh()
        if sido or sigungu:
            filter_kind = "provider_codes"
        # Only administrative address fields participate, never features/phone/name.
        filtered = [row for row in rows if not text_filter_required or region_matches(region, clean_text(row.get("happenAddr"), 200) + " " + clean_text(row.get("orgNm"), 160))]
        cases = [self._map_case(row) for row in filtered]
        meta.update({"fetchedAt": fetched_at, "returnedCount": len(cases), "providerTotalCount": total,
                     "filterKind": filter_kind, "cached": was_cached,
                     "textFilterApplied": text_filter_required,
                     "speciesLookupStatus": self.breed_catalog.status,
                     "coverageNote": f"최근 30일 공고 중 {page}페이지, 최대 {MAX_ROWS}건을 조회했습니다. 행정구역 기준이며 목적지 반경 검색이 아닙니다.",
                     "completionNotice": COMPLETION_NOTICE})
        return {"dataMode": "live", "cases": cases, "meta": meta}

    def get_photo(self, token):
        if not re.fullmatch(r"p[a-f0-9]{32}", token):
            raise APIError(404, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다. 공고를 새로고침하세요.")
        with self.photo_lock:
            with self.lock:
                self._prune()
                entry = self.photos.get(token)
                if not entry:
                    raise APIError(404, "PHOTO_EXPIRED", "사진 참조가 만료되었습니다. 공고를 새로고침하세요.")
                if "data" in entry:
                    self.photos.move_to_end(token)
                    return entry["data"], entry["mime"]
                url = entry["url"]
            try:
                request = urllib.request.Request(url, headers={"Accept-Encoding": "identity"})
                with self.photo_opener.open(request, timeout=15) as response:
                    if response.status != 200:
                        raise APIError(502, "PHOTO_UNAVAILABLE", "사진에 연결하지 못했습니다.")
                    data = response.read(MAX_IMAGE_BYTES + 1)
            except APIError:
                raise
            except Exception:
                raise APIError(502, "PHOTO_UNAVAILABLE", "사진에 연결하지 못했습니다.") from None
            if len(data) > MAX_IMAGE_BYTES:
                raise APIError(502, "PHOTO_TOO_LARGE", "사진이 허용 크기를 초과했습니다.")
            mime = validate_image(data)
            with self.lock:
                if token in self.photos:
                    self.photos[token].update({"data": data, "mime": mime})
                    self.photos.move_to_end(token)
                    self._prune()
            return data, mime


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, state):
        self.state = state
        super().__init__(address, handler)

    def handle_error(self, request, client_address):
        # Suppress tracebacks that could otherwise include request/provider values.
        pass

    def service_actions(self):
        self.state.expire_memory_cache()


class Handler(BaseHTTPRequestHandler):
    server_version = "ArrivalPrototype/0.1"
    sys_version = ""

    def log_message(self, format, *args):
        # No URL, key, personal data, or request-body access logs.
        pass

    def _reply(self, status, content, mime="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(content)

    def _json(self, status, payload):
        self._reply(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _check_local(self):
        host = self.headers.get("Host", "")
        allowed = {name + ":" + str(self.server.server_port) for name in ALLOWED_HOSTS}
        if host not in allowed:
            raise APIError(403, "LOCAL_HOST_REQUIRED", "로컬 개발 주소에서만 사용할 수 있습니다.")
        # A user opening the local app from another page is a legitimate initial
        # document navigation. Only public static documents get this exception;
        # API reads and cross-site subresources retain the same-origin checks.
        public_navigation = (
            self.command == "GET"
            and not urllib.parse.urlsplit(self.path).path.startswith("/api/")
            and self.headers.get("Sec-Fetch-Mode") == "navigate"
            and self.headers.get("Sec-Fetch-Dest") == "document"
        )
        origin = self.headers.get("Origin")
        if not public_navigation and origin and origin not in {"http://" + item for item in allowed}:
            raise APIError(403, "ORIGIN_REJECTED", "이 출처의 요청은 허용하지 않습니다.")
        if not public_navigation and self.headers.get("Sec-Fetch-Site") == "cross-site":
            raise APIError(403, "ORIGIN_REJECTED", "외부 사이트의 요청은 허용하지 않습니다.")

    def do_GET(self):
        mode = "live"
        try:
            self._check_local()
            if len(self.path) > 2048:
                raise APIError(414, "REQUEST_TOO_LONG", "요청이 너무 깁니다.")
            parsed = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(parsed.query, max_num_fields=12)
            value = lambda key, default="": query.get(key, [default])[0]
            if parsed.path == "/api/health":
                self._json(200, {"ok": True, "keyConfigured": self.server.state.provider.configured,
                                 "service": "arrival-pet-prototype", "liveDataUsesMemoryOnly": True})
            elif parsed.path == "/api/cases":
                mode = value("mode", "live")
                page = value("page", "1")
                if not re.fullmatch(r"[1-3]", page):
                    raise APIError(400, "INVALID_PAGE", "페이지는 1부터 3까지 조회할 수 있습니다.")
                result = self.server.state.get_cases(mode, value("region"), value("sido", value("upr_cd")),
                                                     value("sigungu", value("org_cd")), int(page))
                self._json(200, result)
            elif parsed.path == "/api/photo":
                data, mime = self.server.state.get_photo(value("id"))
                self._reply(200, data, mime)
            elif parsed.path.startswith("/api/"):
                raise APIError(404, "NOT_FOUND", "요청한 기능이 없습니다.")
            else:
                path = urllib.parse.unquote(parsed.path)
                relative = "index.html" if path == "/" else path.lstrip("/")
                root = self.server.state.web_root
                target = (root / relative).resolve()
                permitted_file = target.suffix.lower() in {".html", ".css", ".js", ".svg", ".png", ".jpg", ".jpeg", ".webp", ".ico", ".woff", ".woff2"}
                permitted_file = permitted_file or target == root / "offline-demo.json"
                if (root not in target.parents or any(part.startswith(".") for part in Path(relative).parts)
                        or not permitted_file
                        or not target.is_file()):
                    raise APIError(404, "NOT_FOUND", "파일을 찾을 수 없습니다.")
                self._reply(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        except APIError as error:
            self._json(error.status, {"dataMode": mode, "error": {"code": error.code, "message": error.message}})
        except (ValueError, TypeError):
            self._json(400, {"error": {"code": "INVALID_REQUEST", "message": "요청 형식을 확인하세요."}})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self._json(500, {"error": {"code": "INTERNAL_ERROR", "message": "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요."}})

    def do_POST(self):
        self._json(405, {"error": {"code": "METHOD_NOT_ALLOWED", "message": "읽기 전용 개발 서버입니다."}})


def make_server(port=8787, state=None):
    return LocalServer(("127.0.0.1", port), Handler, state or AppState())


def main():
    parser = argparse.ArgumentParser(description="Local arrival-area lost-pet prototype server")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8787")))
    args = parser.parse_args()
    server = make_server(args.port)
    print(f"Arrival prototype: http://127.0.0.1:{server.server_port} (credentials and data are not logged)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
