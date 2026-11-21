#!/usr/bin/env python3
"""Capture sanitized Audiobookshelf v2.38.0 API exchanges for Pageworn tests.

Runs against the disposable instance seeded by seed.py and writes one JSON file
per exchange into app/src/test/resources/abs/v2.38.0/.

Sanitization: token values become <access-token> / <refresh-token> (including
inside JWT-looking strings anywhere in the payload), Set-Cookie values are
dropped, the host becomes server.example and no passwords are written.
IDs and timestamps are kept: they belong to a disposable instance.

Python 3 standard library only.
"""

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = os.environ.get("ABS_BASE_URL", "http://127.0.0.1:13378")
PUBLIC_BASE = "http://server.example"
USER_AGENT = "pageworn-contract-capture"

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
STATE_DIR = HERE / ".state"
ACCOUNTS_PATH = STATE_DIR / "accounts.json"
OUT_DIR = REPO / "app" / "src" / "test" / "resources" / "abs" / "v2.38.0"

JWT_RE = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
JWT_ANYWHERE_RE = re.compile(r"eyJ[A-Za-z0-9_-]{10,}")
SECRET_KEYS = {"password", "pash", "tokenSecret", "newRoot"}

written = []


def die(msg):
    print(f"capture: error: {msg}", file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(f"capture: {msg}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirect())

    def req(self, method, path, body=None, headers=None, token=None):
        hdrs = {"User-Agent": USER_AGENT}
        if body is not None:
            hdrs["Content-Type"] = "application/json"
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
        if headers:
            hdrs.update(headers)
        req = urllib.request.Request(
            BASE_URL + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=hdrs,
            method=method,
        )
        try:
            resp = self.opener.open(req, timeout=120)
            status, resp_headers = resp.status, resp.headers
            raw = resp.read()
        except urllib.error.HTTPError as e:
            status, resp_headers = e.code, e.headers
            raw = e.read()
        return status, resp_headers, raw, hdrs


def header_map(headers):
    """Response headers -> {lower-name: value}; set-cookie value dropped."""
    out = {}
    if not headers:
        return out
    for name in headers.keys():
        lname = name.lower()
        if lname == "set-cookie":
            cookies = headers.get_all("Set-Cookie") or []
            out[lname] = ", ".join("<dropped>" for _ in cookies)
        else:
            out[lname] = ", ".join(headers.get_all(name) or [])
    return out


def sanitize_body(key, value):
    if isinstance(value, dict):
        return {k: sanitize_body(k, v) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitize_body(key, v) for v in value]
    if isinstance(value, str):
        if key in SECRET_KEYS:
            return "<redacted>"
        if JWT_RE.search(value):
            placeholder = "<refresh-token>" if key == "refreshToken" else "<access-token>"
            return JWT_RE.sub(placeholder, value)
        if JWT_ANYWHERE_RE.search(value):
            return JWT_ANYWHERE_RE.sub("<access-token>", value)
    return value


def sanitize_json_text(text):
    return text.replace(BASE_URL, PUBLIC_BASE).replace("127.0.0.1:13378", "server.example")


def recorded_request_headers(sent):
    """The request headers that matter, sanitized."""
    out = {}
    for name, value in sent.items():
        lname = name.lower()
        if lname == "authorization":
            out[lname] = "Bearer <access-token>"
        elif lname == "x-refresh-token":
            out[lname] = "<refresh-token>"
        elif lname == "cookie":
            continue
        else:
            out[lname] = sanitize_body(lname, value) if isinstance(value, str) else value
    out["host"] = "server.example"
    return dict(sorted(out.items()))


def write_capture(name, request, response):
    doc = {"request": request, "response": response}
    text = sanitize_json_text(json.dumps(doc, indent=2, sort_keys=False))
    if JWT_ANYWHERE_RE.search(text):
        die(f"unsanitized token left in {name}")
    path = OUT_DIR / name
    path.write_text(text + "\n")
    written.append(name)
    log(f"wrote {name}")


def make_request_record(method, path, sent_headers, body):
    rec = {
        "method": method,
        "url": PUBLIC_BASE + path,
        "path": path,
        "headers": recorded_request_headers(sent_headers),
    }
    if body is not None:
        rec["body"] = sanitize_body("", body)
    return rec


def capture(api, name, method, path, body=None, headers=None, token=None, user=None):
    """Plain JSON exchange -> one sanitized file."""
    status, resp_headers, raw, sent = api.req(
        method, path, body=body, headers=headers, token=token
    )
    try:
        parsed = json.loads(raw.decode()) if raw else None
    except (ValueError, UnicodeDecodeError):
        parsed = raw.decode("utf-8", "replace")
    request_rec = make_request_record(method, path, sent, body)
    if user:
        request_rec["authenticatedAs"] = user
    response_rec = {
        "status": status,
        "headers": header_map(resp_headers),
        "body": sanitize_body("", parsed),
    }
    write_capture(name, request_rec, response_rec)
    return status, parsed


def sha256_b(data):
    return hashlib.sha256(data).hexdigest()


def sha256_f(path):
    return sha256_b(Path(path).read_bytes())


def main():
    api = Api()
    if not ACCOUNTS_PATH.is_file():
        die(f"{ACCOUNTS_PATH} missing; run 'abs.sh seed' first")
    accounts = json.loads(ACCOUNTS_PATH.read_text())
    libs = accounts["_libraries"]
    books = accounts["_books"]

    status, _, _, _ = api.req("GET", "/status")
    if status != 200:
        die("server not reachable; run 'abs.sh up' first")

    # --- unauthenticated probe ---
    capture(api, "status.json", "GET", "/status")

    # --- listener login (mobile token flow) ---
    _, login = capture(
        api, "login.json", "POST", "/login",
        body={"username": "listener", "password": accounts["listener"]["password"]},
        headers={"x-return-tokens": "true"},
    )
    at1 = login["user"]["accessToken"]
    rt1 = login["user"]["refreshToken"]

    # --- refresh rotation ---
    status_r, refresh = capture(
        api, "refresh.json", "POST", "/auth/refresh",
        headers={"x-refresh-token": rt1},
    )
    if status_r != 200:
        die(f"refresh failed: {status_r}")
    at2 = refresh["user"]["accessToken"]
    rt2 = refresh["user"]["refreshToken"]

    # --- old refresh token inside the grace period ---
    status_g, grace = capture(
        api, "refresh-reuse-old.json", "POST", "/auth/refresh",
        headers={"x-refresh-token": rt1},
    )
    grace_note = {
        "oldRefreshTokenAccepted": status_g == 200,
        "note": "POST /auth/refresh repeated immediately with the pre-rotation "
                "refresh token (REFRESH_TOKEN_GRACE_PERIOD, default 10 min). "
                "A 200 returns the already-rotated refresh token plus a fresh "
                "access token without rotating again.",
    }
    # Re-write the file with the observation attached.
    doc_path = OUT_DIR / "refresh-reuse-old.json"
    doc = json.loads(doc_path.read_text())
    doc["observation"] = grace_note
    doc_path.write_text(sanitize_json_text(json.dumps(doc, indent=2)) + "\n")
    log("annotated refresh-reuse-old.json")

    access = grace["user"]["accessToken"] if status_g == 200 else at2
    refresh_token = rt2

    # --- authenticated reads as listener ---
    capture(api, "me.json", "GET", "/api/me", token=access, user="listener")
    _, libraries = capture(api, "libraries.json", "GET", "/api/libraries", token=access, user="listener")

    ab_id = libs["Audiobooks"]
    second_id = libs["Second shelf"]

    for page in (0, 1):
        capture(
            api, f"items-page{page}.json", "GET",
            f"/api/libraries/{ab_id}/items?page={page}&limit=3"
            "&sort=media.metadata.title&minified=1",
            token=access, user="listener",
        )
    capture(
        api, "items-limit0.json", "GET",
        f"/api/libraries/{ab_id}/items?limit=0&sort=media.metadata.title&minified=1",
        token=access, user="listener",
    )
    capture(
        api, "items-second.json", "GET",
        f"/api/libraries/{second_id}/items?limit=0&sort=media.metadata.title&minified=1",
        token=access, user="listener",
    )

    # --- expanded items: every book in both book libraries ---
    all_books = [(b["title"], b["id"]) for name in books for b in books[name]]
    all_books.sort(key=lambda t: (t[0].lower(), t[1]))
    used_slugs = {}
    expanded = {}
    for title, item_id in all_books:
        slug = re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", title.lower())).strip("-") or "item"
        n = used_slugs.get(slug, 0) + 1
        used_slugs[slug] = n
        slug = slug if n == 1 else f"{slug}-{n}"
        _, body = capture(
            api, f"item-{slug}.json", "GET",
            f"/api/items/{item_id}?expanded=1",
            token=access, user="listener",
        )
        expanded[item_id] = body

    # --- boundary book download (full) ---
    boundary_id = accounts["_boundaryItemId"]
    boundary = expanded[boundary_id]
    tracks = boundary["media"]["tracks"]
    track = sorted(tracks, key=lambda t: t["index"])[0]
    ino = str(track["ino"])
    dl_path = f"/api/items/{boundary_id}/file/{ino}/download"

    status, hdrs, raw, sent = api.req(
        "GET", dl_path, headers={"Accept-Encoding": "identity"}, token=access
    )
    fixture_name = track["metadata"]["filename"]
    fixture_src = accounts["_bookFiles"]["Fixture Author/Boundary Book"].get(fixture_name)
    fixture_sha = sha256_f(fixture_src) if fixture_src and Path(fixture_src).is_file() else None
    write_capture(
        "download-full.json",
        make_request_record("GET", dl_path, sent, None) | {"authenticatedAs": "listener"},
        {
            "status": status,
            "headers": header_map(hdrs),
            "body": {
                "bodyBytes": len(raw),
                "sha256": sha256_b(raw),
                "fixtureFile": fixture_name,
                "fixtureSha256": fixture_sha,
                "matchesFixture": fixture_sha is not None and sha256_b(raw) == fixture_sha,
            },
        },
    )
    last_modified = hdrs.get("Last-Modified") if hdrs else None
    etag = hdrs.get("ETag") if hdrs else None

    # --- range requests on the same URL ---
    range_exchanges = []
    for label, extra_headers in (
        ("range-only", {"Range": "bytes=1000-"}),
        ("if-range-last-modified", {"Range": "bytes=1000-", "If-Range": last_modified or ""}),
        ("if-range-etag", {"Range": "bytes=1000-", "If-Range": etag or ""}),
    ):
        h = {"Accept-Encoding": "identity"}
        h.update(extra_headers)
        st, rh, raw, sent = api.req("GET", dl_path, headers=h, token=access)
        resp = {"status": st, "headers": header_map(rh), "bodyBytes": len(raw)}
        range_exchanges.append({
            "label": label,
            "request": make_request_record("GET", dl_path, sent, None) | {"authenticatedAs": "listener"},
            "response": resp,
        })
    doc = {"exchanges": range_exchanges}
    text = sanitize_json_text(json.dumps(doc, indent=2))
    (OUT_DIR / "download-range.json").write_text(text + "\n")
    written.append("download-range.json")
    log("wrote download-range.json")

    # --- browser: download denied, stream allowed ---
    _, b_login = capture(
        api, "login-browser.json", "POST", "/login",
        body={"username": "browser", "password": accounts["browser"]["password"]},
        headers={"x-return-tokens": "true"},
    )
    b_access = b_login["user"]["accessToken"]

    st, rh, raw, sent = api.req("GET", dl_path, headers={"Accept-Encoding": "identity"}, token=b_access)
    try:
        parsed = json.loads(raw.decode()) if raw else None
    except (ValueError, UnicodeDecodeError):
        parsed = raw.decode("utf-8", "replace")
    write_capture(
        "download-denied.json",
        make_request_record("GET", dl_path, sent, None) | {"authenticatedAs": "browser"},
        {"status": st, "headers": header_map(rh), "body": sanitize_body("", parsed)},
    )

    stream_path = f"/api/items/{boundary_id}/file/{ino}"
    st, rh, raw, sent = api.req("GET", stream_path, headers={"Accept-Encoding": "identity"}, token=b_access)
    write_capture(
        "stream-as-browser.json",
        make_request_record("GET", stream_path, sent, None) | {"authenticatedAs": "browser"},
        {"status": st, "headers": header_map(rh), "bodyBytes": len(raw)},
    )

    # --- cover without a token ---
    cover_item = None
    for item in expanded.values():
        if item.get("media", {}).get("coverPath"):
            cover_item = item
            break
    if cover_item is None:
        cover_item = boundary
        log("no item with a cover found; cover capture will record the error status")
    ts = cover_item.get("updatedAt", "")
    cover_path = f"/api/items/{cover_item['id']}/cover?width=200&format=webp&ts={ts}"
    st, rh, raw, sent = api.req("GET", cover_path)
    write_capture(
        "cover.json",
        make_request_record("GET", cover_path, sent, None),
        {
            "status": st,
            "headers": header_map(rh),
            "body": {"bodyBytes": len(raw), "sha256": sha256_b(raw)},
        },
    )

    # --- logout with the current refresh token ---
    st, rh, raw, sent = api.req("POST", "/logout", headers={"x-refresh-token": refresh_token})
    try:
        parsed = json.loads(raw.decode()) if raw else None
    except (ValueError, UnicodeDecodeError):
        parsed = raw.decode("utf-8", "replace")
    write_capture(
        "logout.json",
        make_request_record("POST", "/logout", sent, None) | {"authenticatedAs": "listener"},
        {"status": st, "headers": header_map(rh), "body": sanitize_body("", parsed)},
    )

    # --- nolibs sees no libraries ---
    _, n_login = capture(
        api, "login-nolibs.json", "POST", "/login",
        body={"username": "nolibs", "password": accounts["nolibs"]["password"]},
        headers={"x-return-tokens": "true"},
    )
    capture(
        api, "nolibs-libraries.json", "GET", "/api/libraries",
        token=n_login["user"]["accessToken"], user="nolibs",
    )

    log(f"done: {len(written)} files in {OUT_DIR}")


if __name__ == "__main__":
    main()
