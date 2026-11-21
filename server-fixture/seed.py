#!/usr/bin/env python3
"""Seed the local Audiobookshelf v2.38.0 reference instance.

Creates the root user, three disposable accounts (listener, browser, nolibs),
three libraries, copies the generated fixture audio into the container's
library folders with `docker cp` (bind mounts of host paths on external volumes can be
stale inside the Colima VM, so the container uses named volumes), scans,
sets the Boundary Book server chapters and marks one audio file excluded
via the tracks endpoint.

Random passwords are generated on every run and stored (with the created IDs)
in server-fixture/.state/accounts.json. Passwords are never printed.

Python 3 standard library only.
"""

import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

BASE_URL = os.environ.get("ABS_BASE_URL", "http://127.0.0.1:13378")
CONTAINER = os.environ.get("ABS_CONTAINER", "pageworn-abs")

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
STATE_DIR = HERE / ".state"
ACCOUNTS_PATH = STATE_DIR / "accounts.json"

# Fixture audio produced by the repository's fixture generator. If it never
# materialized, abs.sh's caller generated sine-wave files under server-fixture/audio/
# instead (documented in server-fixture/README.md when used).
FIXTURE_ROOT_CANDIDATES = [
    REPO / "fixtures" / "out" / "assets" / "fixtures",
    HERE / "audio",
]

AUDIO_EXTS = {
    ".m4b", ".m4a", ".mp3", ".flac", ".ogg", ".opus", ".wma", ".aac",
    ".wav", ".aif", ".aiff", ".webm", ".mka", ".caf", ".mpg", ".mpeg", ".mp4",
}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}

SCAN_TIMEOUT_S = 240


def die(msg):
    print(f"seed: error: {msg}", file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(f"seed: {msg}")


def docker_run(args, label):
    """Run a docker CLI command, die on failure."""
    res = subprocess.run(["docker", *args], capture_output=True, text=True)
    if res.returncode != 0:
        die(f"{label} failed: {res.stderr.strip() or res.stdout.strip()}")
    return res.stdout


def docker_cp_into(src_dir, dest):
    """Copy the *contents* of src_dir into the container at dest."""
    docker_run(["cp", f"{src_dir}/.", f"{CONTAINER}:{dest}/"],
               f"docker cp {src_dir} -> {CONTAINER}:{dest}")
    log(f"copied {src_dir.name}/ into {CONTAINER}:{dest}")


def fix_permissions():
    """Make the copied library tree readable by the server process."""
    docker_run(
        ["exec", CONTAINER, "sh", "-c",
         "chmod -R a+rX /audiobooks /second /podcasts"],
        "docker exec chmod",
    )


class Api:
    """Minimal urllib JSON client. Returns (status, headers, body)."""

    def __init__(self):
        self.token = None
        # Never follow redirects: a credential-carrying request must stay on
        # this origin, and captures need the raw status code.
        no_redirect = urllib.request.build_opener(NoRedirect())
        self.opener = no_redirect

    def req(self, method, path, body=None, headers=None, token=None):
        hdrs = {}
        if body is not None:
            hdrs["Content-Type"] = "application/json"
        tok = self.token if token is None else token
        if tok:
            hdrs["Authorization"] = f"Bearer {tok}"
        if headers:
            hdrs.update(headers)
        req = urllib.request.Request(
            BASE_URL + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=hdrs,
            method=method,
        )
        try:
            resp = self.opener.open(req, timeout=60)
            status, resp_headers = resp.status, dict(resp.headers)
            raw = resp.read()
        except urllib.error.HTTPError as e:
            status, resp_headers = e.code, dict(e.headers or {})
            raw = e.read()
        try:
            parsed = json.loads(raw.decode()) if raw else None
        except (ValueError, UnicodeDecodeError):
            parsed = raw.decode("utf-8", "replace")
        return status, resp_headers, parsed

    def expect(self, method, path, body=None, headers=None, token=None, want=(200,)):
        status, hdrs, parsed = self.req(method, path, body=body, headers=headers, token=token)
        if status not in want:
            die(f"{method} {path} -> {status}: {str(parsed)[:300]}")
        return status, hdrs, parsed


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def random_password():
    return secrets.token_urlsafe(18)


def fixture_root():
    for cand in FIXTURE_ROOT_CANDIDATES:
        if cand.is_dir() and any(cand.iterdir()):
            return cand
    die(
        "no fixture audio found: expected fixtures/out/assets/fixtures/<id>/ "
        "or server-fixture/audio/<id>/"
    )


def fixture_files(root, fixture_id):
    """All media files of a fixture, sorted by name."""
    d = root / fixture_id
    if not d.is_dir():
        die(f"fixture '{fixture_id}' not found under {root}")
    files = sorted(
        p for p in d.iterdir()
        if p.is_file() and p.suffix.lower() in AUDIO_EXTS | IMAGE_EXTS
    )
    if not any(p.suffix.lower() in AUDIO_EXTS for p in files):
        die(f"fixture '{fixture_id}' has no audio files")
    return files


def find_audio(root, suffix):
    """First audio file with the given extension across all fixtures."""
    for d in sorted(root.iterdir()):
        if not d.is_dir():
            continue
        for p in sorted(d.iterdir()):
            if p.is_file() and p.suffix.lower() == suffix:
                return p
    return None


def copy_tree_files(files, dest_dir, renames=None):
    """Copy files into dest_dir; renames maps source name -> new name."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    copied = {}
    for i, src in enumerate(files):
        name = renames.get(src.name, src.name) if renames else src.name
        shutil.copy2(src, dest_dir / name)
        copied[name] = str(src)
    return copied


def make_epub(path):
    """A tiny but structurally valid EPUB (zip) for the mixed-media book."""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
    opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">pageworn-fixture-epub</dc:identifier>
    <dc:title>Mixed Media Book</dc:title>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>
"""
    xhtml = """<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head>
<body><p>Pageworn fixture ebook.</p></body></html>
"""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", container)
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/chapter1.xhtml", xhtml)


def wait_for_items(api, library_id, expected, label):
    deadline = time.time() + SCAN_TIMEOUT_S
    while time.time() < deadline:
        status, _, body = api.req(
            "GET", f"/api/libraries/{library_id}/items?limit=0&minified=1"
        )
        if status == 200 and isinstance(body, dict):
            results = body.get("results") or []
            if len(results) >= expected:
                log(f"library '{label}' scan done: {len(results)} items")
                return results
        time.sleep(2)
    die(f"library '{label}' still has fewer than {expected} items after {SCAN_TIMEOUT_S}s")


def main():
    api = Api()

    status, _, body = api.req("GET", "/status")
    if status != 200:
        die(f"GET /status -> {status}; is the container running? (abs.sh up)")
    if body.get("isInit"):
        die("server already initialized; run 'abs.sh reset' then 'abs.sh up' for a clean seed")

    # --- root user ---
    passwords = {u: random_password() for u in ("root", "listener", "browser", "nolibs")}
    status, _, _ = api.req(
        "POST", "/init", body={"newRoot": {"username": "root", "password": passwords["root"]}}
    )
    if status != 200:
        die(f"POST /init -> {status}")
    log("root user created")

    _, _, login = api.expect(
        "POST", "/login",
        body={"username": "root", "password": passwords["root"]},
        headers={"x-return-tokens": "true"},
        token="",
    )
    api.token = login["user"]["accessToken"]
    accounts = {
        "root": {"id": login["user"]["id"], "username": "root", "password": passwords["root"]}
    }

    root = fixture_root()
    log(f"using fixture audio from {root}")

    # --- lay out library folders ---
    # Bind mounts of this checkout are stale inside the Colima VM, so the
    # tree is staged in a temp dir and pushed into the running container
    # (named volumes) with `docker cp`.
    book_files = {}  # "Author/Title" -> {copied name: fixture source}
    with tempfile.TemporaryDirectory(prefix="abs-seed-") as staging:
        stage = Path(staging)
        books_dir = stage / "audiobooks"
        second_dir = stage / "second"
        for d in (books_dir, second_dir):
            d.mkdir(parents=True, exist_ok=True)

        layout = [
            ("Elin Ward/The Night Orchard", "single-m4b", None),
            ("Fixture Author/Boundary Book", "boundary-book", None),
            ("Ada Bell/Field Notes on Rain", "no-chapters-multi", None),
            ("Kit Mercer/Small Hours", "no-chapters-single", None),
            ("Format Author/WMA Book", "fmt-wma-wmav2", None),
        ]
        for rel, fixture_id, renames in layout:
            files = fixture_files(root, fixture_id)
            book_files[rel] = copy_tree_files(files, books_dir / rel, renames)

        # Two items with the same title: identical fixture file in both
        # folders so the derived title matches either way.
        same_title_src = fixture_files(root, "no-chapters-single")[0]
        for rel in ("Mara Ellis/Same Title", "Kit Mercer/Same Title"):
            book_files[rel] = copy_tree_files([same_title_src], books_dir / rel)

        # Mixed-media book: one m4a plus a generated EPUB.
        m4a = find_audio(root, ".m4a")
        if m4a is None:
            die("no .m4a fixture found for the mixed-media book")
        rel = "Mixed Author/Mixed Media Book"
        book_files[rel] = copy_tree_files([m4a], books_dir / rel)
        make_epub(books_dir / rel / "bonus.epub")
        book_files[rel]["bonus.epub"] = "(generated epub)"

        # Three-track book whose middle file will be excluded through the API.
        three = [fixture_files(root, "boundary-book")[0],
                 fixture_files(root, "no-chapters-single")[0],
                 fixture_files(root, "boundary-book")[-1]]
        rel = "Exclude Author/Exclude Track Book"
        book_files[rel] = copy_tree_files(
            three, books_dir / rel,
            renames={p.name: f"{i + 1:02d}{p.suffix.lower()}" for i, p in enumerate(three)},
        )

        # Second shelf: two books reusing short fixtures.
        second_layout = [
            ("Second Author/Second Book A", "no-chapters-single"),
            ("Second Author/Second Book B", "boundary-book"),
        ]
        for rel, fixture_id in second_layout:
            files = fixture_files(root, fixture_id)
            book_files[rel] = copy_tree_files(files, second_dir / rel)

        docker_cp_into(books_dir, "/audiobooks")
        docker_cp_into(second_dir, "/second")
    fix_permissions()

    # --- libraries ---
    libraries = {}
    for name, media_type, folder in (
        ("Audiobooks", "book", "/audiobooks"),
        ("Second shelf", "book", "/second"),
        ("Podcasts", "podcast", "/podcasts"),
    ):
        _, _, lib = api.expect(
            "POST", "/api/libraries",
            body={"name": name, "mediaType": media_type,
                  "folders": [{"fullPath": folder}]},
        )
        libraries[name] = lib["id"]
        log(f"library '{name}' created ({lib['id']})")

    # --- users ---
    user_specs = {
        "listener": {"download": True, "accessAllLibraries": True},
        "browser": {"download": False, "accessAllLibraries": True},
        "nolibs": {"download": True, "accessAllLibraries": False,
                   "librariesAccessible": []},
    }
    for username, perms in user_specs.items():
        _, _, resp = api.expect(
            "POST", "/api/users",
            body={
                "username": username,
                "password": passwords[username],
                "type": "user",
                "isActive": True,
                "permissions": perms,
            },
        )
        user = resp["user"]
        accounts[username] = {
            "id": user["id"], "username": username, "password": passwords[username]
        }
        log(f"user '{username}' created ({user['id']})")

    # --- scan and wait ---
    api.expect("POST", f"/api/libraries/{libraries['Audiobooks']}/scan")
    api.expect("POST", f"/api/libraries/{libraries['Second shelf']}/scan")
    expected = {"Audiobooks": 9, "Second shelf": 2}
    items = {}
    for name in ("Audiobooks", "Second shelf"):
        items[name] = wait_for_items(api, libraries[name], expected[name], name)

    # --- Boundary Book server chapters ---
    boundary = next(i for i in items["Audiobooks"] if i["media"]["metadata"]["title"] == "Boundary Book")
    chapters = [
        {"id": 0, "title": "Chapter One", "start": 0, "end": 75},
        {"id": 1, "title": "Chapter Two", "start": 75, "end": 140},
        {"id": 2, "title": "Chapter Three", "start": 140, "end": 210},
    ]
    api.expect("POST", f"/api/items/{boundary['id']}/chapters", body={"chapters": chapters})
    log("Boundary Book chapters set")

    # --- exclude the middle file of the three-track book ---
    excl = next(i for i in items["Audiobooks"] if i["media"]["metadata"]["title"] == "Exclude Track Book")
    _, _, expanded = api.expect("GET", f"/api/items/{excl['id']}?expanded=1")
    audio_files = sorted(expanded["media"]["audioFiles"], key=lambda f: f["index"])
    if len(audio_files) != 3:
        die(f"Exclude Track Book has {len(audio_files)} audio files, expected 3")
    ordered = [
        {"ino": af["ino"], "exclude": af is audio_files[1]}
        for af in audio_files
    ]
    api.expect("PATCH", f"/api/items/{excl['id']}/tracks", body={"orderedFileData": ordered})
    log(f"excluded middle file (ino {audio_files[1]['ino']}) of Exclude Track Book")

    # --- persist accounts + IDs (passwords only into .state, never printed) ---
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    accounts["_libraries"] = libraries
    accounts["_books"] = {
        name: [
            {"id": i["id"], "title": i["media"]["metadata"]["title"],
             "relPath": i.get("relPath", "")}
            for i in items[name]
        ]
        for name in items
    }
    accounts["_bookFiles"] = book_files
    accounts["_boundaryItemId"] = boundary["id"]
    accounts["_excludeItemId"] = excl["id"]
    ACCOUNTS_PATH.write_text(json.dumps(accounts, indent=2) + "\n")
    try:
        ACCOUNTS_PATH.chmod(0o600)
    except OSError:
        pass
    log(f"accounts written to {ACCOUNTS_PATH} (passwords stored, not shown)")
    log("seed complete")


if __name__ == "__main__":
    main()
