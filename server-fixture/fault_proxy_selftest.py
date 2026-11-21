#!/usr/bin/env python3
"""Self-test for server-fixture/fault_proxy.py.

Starts a local backend that serves a deterministic 1 MiB file and a login endpoint, starts the
proxy as a subprocess on free ports, and checks pass-through, drop, ignore_range, throttle and
status faults plus the control log. Run it from the repository root:

    python3 server-fixture/fault_proxy_selftest.py

Prints PASS or FAIL per check and exits non-zero when any check fails.
"""

import http.client
import json
import pathlib
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HERE = pathlib.Path(__file__).resolve().parent
PROXY = HERE / "fault_proxy.py"
FILE_BYTES = bytes(range(256)) * 4096
DOWNLOAD_PATH = "/api/items/x/file/y/download"


class BackendHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if urlsplit(self.path).path != DOWNLOAD_PATH:
            self._json(404, {"error": "not found"})
            return
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d+)-", range_header.strip())
            if match:
                start = int(match.group(1))
                if start >= len(FILE_BYTES):
                    self._json(416, {"error": "range not satisfiable"})
                    return
                chunk = FILE_BYTES[start:]
                self.send_response(206)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header(
                    "Content-Range", f"bytes {start}-{len(FILE_BYTES) - 1}/{len(FILE_BYTES)}"
                )
                self.send_header("Content-Length", str(len(chunk)))
                self.end_headers()
                self._write(chunk)
                return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(FILE_BYTES)))
        self.end_headers()
        self._write(FILE_BYTES)

    def do_POST(self):
        if urlsplit(self.path).path != "/login":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        json.loads(self.rfile.read(length).decode("utf-8"))
        self._json(200, {"ok": True})

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self._write(body)

    def _write(self, data):
        try:
            self.wfile.write(data)
        except ConnectionError:
            # The proxy drops the connection mid-body on purpose.
            self.close_connection = True

    def log_message(self, format, *args):
        pass


def start_backend():
    server = ThreadingHTTPServer(("127.0.0.1", 0), BackendHandler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, server.server_address[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def start_proxy(listen_port, backend_port, control_port):
    output = tempfile.TemporaryFile()
    process = subprocess.Popen(
        [
            sys.executable,
            str(PROXY),
            "--listen",
            str(listen_port),
            "--backend",
            str(backend_port),
            "--control",
            str(control_port),
        ],
        stdout=output,
        stderr=subprocess.STDOUT,
    )
    return process, output


def wait_for_proxy(process, control_port, timeout=15.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"proxy exited with status {process.returncode}")
        try:
            status, _ = control(control_port, "GET", "/health")
            if status == 200:
                return
        except OSError:
            pass
        time.sleep(0.1)
    raise RuntimeError("proxy did not become healthy")


def control(control_port, method, path, payload=None):
    connection = http.client.HTTPConnection("127.0.0.1", control_port, timeout=10.0)
    body = None
    headers = {}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    connection.request(method, path, body=body, headers=headers)
    response = connection.getresponse()
    data = response.read()
    status = response.status
    connection.close()
    return status, json.loads(data.decode("utf-8")) if data else None


def wait_for_requests(control_port, count, timeout=5.0):
    """Poll the control log until it holds COUNT entries, then return them."""
    deadline = time.monotonic() + timeout
    requests = []
    while time.monotonic() < deadline:
        _, log = control(control_port, "GET", "/log")
        requests = log["requests"]
        if len(requests) >= count:
            return requests
        time.sleep(0.02)
    return requests


def fetch(port, method, path, headers=None, body=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30.0)
    connection.request(method, path, body=body, headers=headers or {})
    response = connection.getresponse()
    data = response.read()
    status = response.status
    connection.close()
    return status, data


def fetch_truncating(port, path, headers=None):
    """Fetch and return (status, body bytes, error) even when the body is cut short."""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30.0)
    status = None
    data = b""
    error = None
    try:
        connection.request("GET", path, headers=headers or {})
        response = connection.getresponse()
        status = response.status
        try:
            data = response.read()
        except http.client.IncompleteRead as incomplete:
            data = incomplete.partial
            error = incomplete
        except (ConnectionResetError, OSError) as reset:
            error = reset
    except (ConnectionResetError, OSError) as reset:
        error = reset
    finally:
        connection.close()
    return status, data, error


def check_pass_through(listen_port, control_port):
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    assert status == 200, f"full file status {status}"
    assert data == FILE_BYTES, f"full file body {len(data)} bytes"
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH, headers={"Range": "bytes=1000-"})
    assert status == 206, f"range status {status}"
    assert data == FILE_BYTES[1000:], f"range body {len(data)} bytes"
    requests = wait_for_requests(control_port, 2)
    assert len(requests) == 2, f"log has {len(requests)} entries"
    assert requests[0]["method"] == "GET" and requests[0]["path"] == DOWNLOAD_PATH, requests[0]
    assert requests[0]["status"] == 200 and requests[0]["body_bytes"] == len(FILE_BYTES), requests[0]
    assert requests[1]["range"] == "bytes=1000-", requests[1]
    assert requests[1]["if_range"] is None, requests[1]
    assert requests[1]["status"] == 206, requests[1]
    assert requests[1]["body_bytes"] == len(FILE_BYTES) - 1000, requests[1]


def check_drop(listen_port, control_port):
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "drop", "count": 1, "bytes": 100000}])
    status, data, error = fetch_truncating(listen_port, DOWNLOAD_PATH)
    assert status == 200, f"drop status {status}"
    assert 0 < len(data) <= 100000, f"received {len(data)} bytes after the drop"
    assert error is not None, "expected EOF or an error after the drop"
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    assert status == 200 and data == FILE_BYTES, f"follow-up status {status}, {len(data)} bytes"
    requests = wait_for_requests(control_port, 2)
    assert requests[0]["rule"] == "drop", requests[0]
    assert requests[0]["body_bytes"] <= 100000, requests[0]
    assert requests[1]["rule"] is None, requests[1]
    assert requests[1]["body_bytes"] == len(FILE_BYTES), requests[1]


def check_ignore_range(listen_port, control_port):
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "ignore_range", "count": 1}])
    status, data = fetch(
        listen_port,
        "GET",
        DOWNLOAD_PATH,
        headers={"Range": "bytes=1000-", "If-Range": "fixture-etag"},
    )
    assert status == 200, f"ignore_range status {status}"
    assert data == FILE_BYTES, f"ignore_range body {len(data)} bytes"
    requests = wait_for_requests(control_port, 1)
    entry = requests[0]
    assert entry["rule"] == "ignore_range", entry
    assert entry["range"] == "bytes=1000-", entry
    assert entry["if_range"] == "fixture-etag", entry
    assert entry["status"] == 200, entry
    assert entry["body_bytes"] == len(FILE_BYTES), entry


def check_throttle(listen_port, control_port):
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "throttle", "count": 1, "rate": 200000}])
    start = time.monotonic()
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    elapsed = time.monotonic() - start
    assert status == 200, f"throttle status {status}"
    assert data == FILE_BYTES, f"throttle body {len(data)} bytes"
    assert elapsed >= 4.0, f"throttled transfer took {elapsed:.2f}s"
    wait_for_requests(control_port, 1)


def check_status(listen_port, control_port):
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "status", "count": 1, "code": 503}])
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    assert status == 503, f"status mode returned {status}"
    assert data == b"", f"status mode body {len(data)} bytes"
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    assert status == 200 and data == FILE_BYTES, f"follow-up status {status}, {len(data)} bytes"
    requests = wait_for_requests(control_port, 2)
    assert requests[0]["rule"] == "status", requests[0]
    assert requests[0]["status"] == 503 and requests[0]["body_bytes"] == 0, requests[0]
    assert requests[1]["rule"] is None, requests[1]


def check_login_passthrough(listen_port, control_port):
    control(control_port, "POST", "/reset")
    password = "fixture-password-123"
    token = "fixture-access-token"
    body = json.dumps({"username": "listener", "password": password}).encode("utf-8")
    status, data = fetch(
        listen_port,
        "POST",
        "/login",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        body=body,
    )
    assert status == 200, f"login status {status}"
    assert json.loads(data.decode("utf-8")) == {"ok": True}, data
    requests = wait_for_requests(control_port, 1)
    assert len(requests) == 1, requests
    entry = requests[0]
    assert entry["method"] == "POST" and entry["path"] == "/login", entry
    assert entry["body_bytes"] == len(data), entry
    serialized = json.dumps({"requests": requests})
    assert "Authorization" not in serialized, "log leaked the Authorization header"
    assert password not in serialized, "log leaked the request body"
    assert token not in serialized, "log leaked the access token"


def check_refuse(listen_port, control_port):
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "refuse", "match": ".*", "count": 1}])
    status, data, error = fetch_truncating(listen_port, "/status")
    assert status is None and error is not None, f"refuse answered {status}"
    status, data = fetch(listen_port, "GET", DOWNLOAD_PATH)
    assert status == 200 and data == FILE_BYTES, f"follow-up status {status}, {len(data)} bytes"
    requests = wait_for_requests(control_port, 2)
    assert requests[0]["rule"] == "refuse" and requests[0]["status"] is None, requests[0]
    assert requests[1]["rule"] is None, requests[1]


def check_client_hang_up(listen_port, control_port):
    """A client that stops reading mid-body is logged with the status and bytes it received."""
    control(control_port, "POST", "/reset")
    control(control_port, "POST", "/rules", [{"mode": "throttle", "count": 1, "rate": 200000}])
    connection = http.client.HTTPConnection("127.0.0.1", listen_port, timeout=30.0)
    connection.request("GET", DOWNLOAD_PATH)
    response = connection.getresponse()
    received = response.read(100000)
    connection.sock.shutdown(socket.SHUT_RDWR)
    connection.close()
    requests = wait_for_requests(control_port, 1, timeout=10.0)
    assert len(requests) == 1, requests
    entry = requests[0]
    assert entry["status"] == 200, entry
    assert len(received) <= entry["body_bytes"] < len(FILE_BYTES), entry


CHECKS = (
    ("pass-through full file and range", check_pass_through),
    ("drop after 100000 bytes then pass", check_drop),
    ("ignore_range answers the whole file", check_ignore_range),
    ("throttle at 200000 B/s", check_throttle),
    ("status 503 then pass", check_status),
    ("login body passes through unlogged", check_login_passthrough),
    ("refuse closes without an answer", check_refuse),
    ("a client hang-up keeps its status and bytes", check_client_hang_up),
)


def main():
    backend, backend_thread, backend_port = start_backend()
    listen_port = free_port()
    control_port = free_port()
    process, proxy_output = start_proxy(listen_port, backend_port, control_port)
    failures = []
    try:
        wait_for_proxy(process, control_port)
        for name, check in CHECKS:
            try:
                check(listen_port, control_port)
            except Exception as error:
                failures.append(name)
                print(f"FAIL {name}: {error}")
            else:
                print(f"PASS {name}")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        proxy_output.seek(0)
        proxy_text = proxy_output.read().decode("utf-8", "replace")
        proxy_output.close()
        backend.shutdown()
        backend.server_close()
        backend_thread.join(timeout=5)
    if failures:
        print(f"{len(failures)} check(s) failed")
        print("proxy output:")
        print(proxy_text)
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
