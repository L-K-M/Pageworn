#!/usr/bin/env python3
r"""Transfer-fault proxy between the emulator and the local reference server.

Forwards one HTTP/1.1 request per client connection from 127.0.0.1:LISTEN to the plain
Audiobookshelf instance on 127.0.0.1:BACKEND and injects the transfer faults that the
download-recovery tests need, so an instrumented client sees a dropped body, an ignored range,
a throttled body or an error status without a second server. A JSON control API on
127.0.0.1:CONTROL sets the rules and reads the request log:

    server-fixture/fault_proxy.py --listen 13380 --backend 13378 --control 13390

    curl -X POST --data '[{"mode":"drop","bytes":100000}]' http://127.0.0.1:13390/rules
    curl http://127.0.0.1:13390/log

Rules arrive as a JSON list and replace all previous rules. The first active rule whose
"match" regex fits the request target applies, and its "count" decreases by one; a count of 0
means unlimited. "mode" is one of "pass", "drop" (with "bytes"), "ignore_range", "throttle"
(with "rate" in bytes per second), "status" (with "code") or "refuse", which closes the
connection without an answer, as an address that stopped answering does. The default match is
r"/download$|/download\?". GET /log reports, in arrival order and from the moment a request
arrives, the method, target, Range and If-Range headers as received, the applied mode, the
status code and the response body bytes sent to the client so far; it never reports other
headers or request bodies.
"""

import argparse
import json
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
CHUNK_SIZE = 16384
SOCKET_TIMEOUT = 30.0
DEFAULT_MATCH = r"/download$|/download\?"
MODES = ("pass", "drop", "ignore_range", "throttle", "status", "refuse")


class Rule:
    """One fault rule, validated when it is installed."""

    def __init__(self, raw):
        if not isinstance(raw, dict):
            raise ValueError("a rule must be a JSON object")
        mode = raw.get("mode", "pass")
        if mode not in MODES:
            raise ValueError(f"unknown mode {mode!r}")
        self.mode = mode
        pattern = raw.get("match", DEFAULT_MATCH)
        if not isinstance(pattern, str):
            raise ValueError("match must be a string")
        try:
            self.match = re.compile(pattern)
        except re.error as error:
            raise ValueError(f"invalid match regex: {error}") from error
        count = raw.get("count", 1)
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            raise ValueError("count must be an integer of 0 or more")
        self.count = count
        self.bytes = self._positive_int(raw, "bytes", mode == "drop")
        self.rate = self._positive_number(raw, "rate", mode == "throttle")
        self.code = self._status_code(raw, mode == "status")

    @staticmethod
    def _positive_int(raw, name, required):
        value = raw.get(name)
        if value is None:
            if required:
                raise ValueError(f"{name} is required for this mode")
            return None
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise ValueError(f"{name} must be a positive integer")
        return value

    @staticmethod
    def _positive_number(raw, name, required):
        value = raw.get(name)
        if value is None:
            if required:
                raise ValueError(f"{name} is required for this mode")
            return None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
            raise ValueError(f"{name} must be a positive number")
        return float(value)

    @staticmethod
    def _status_code(raw, required):
        value = raw.get("code")
        if value is None:
            if required:
                raise ValueError("code is required for this mode")
            return None
        if not isinstance(value, int) or isinstance(value, bool) or value < 100 or value > 999:
            raise ValueError("code must be an integer status code")
        return value

    def applies_to(self, path):
        return self.match.search(path) is not None


class Proxy:
    """Rule state and the request log, shared by the accept and control threads."""

    def __init__(self, backend):
        self.backend = backend
        self.lock = threading.Lock()
        self.rules = []
        self.log = []
        self.sequence = 0

    def next_sequence(self):
        with self.lock:
            self.sequence += 1
            return self.sequence

    def set_rules(self, raw_rules):
        if not isinstance(raw_rules, list):
            raise ValueError("rules must be a JSON list")
        rules = [Rule(raw) for raw in raw_rules]
        with self.lock:
            self.rules = rules

    def reset(self):
        with self.lock:
            self.rules = []
            self.log = []

    def select_rule(self, path):
        with self.lock:
            for index, rule in enumerate(self.rules):
                if not rule.applies_to(path):
                    continue
                if rule.count > 0:
                    rule.count -= 1
                    if rule.count == 0:
                        del self.rules[index]
                return rule
            return None

    def record(self, sequence, entry):
        with self.lock:
            self.log.append((sequence, entry))

    def snapshot_log(self):
        with self.lock:
            return [dict(entry) for _, entry in sorted(self.log, key=lambda item: item[0])]


def read_head(sock):
    """Read to the end of a head. Return (head, rest), or (None, b"") on early EOF."""
    buffer = bytearray()
    while True:
        index = buffer.find(b"\r\n\r\n")
        if index != -1:
            end = index + 4
            return bytes(buffer[:end]), bytes(buffer[end:])
        chunk = sock.recv(65536)
        if not chunk:
            return None, b""
        buffer.extend(chunk)


def parse_request(head):
    lines = head.split(b"\r\n")
    parts = lines[0].decode("latin-1").split(" ")
    method = parts[0] if parts else ""
    target = parts[1] if len(parts) > 1 else ""
    headers = {}
    for line in lines[1:]:
        name, separator, value = line.partition(b":")
        if not separator:
            continue
        headers[name.strip().lower().decode("latin-1")] = value.strip().decode("latin-1")
    return method, target, headers


def rewrite_head(head, drop_range):
    """Force Connection: close and, for ignore_range, remove Range and If-Range."""
    lines = head.split(b"\r\n")
    rewritten = [lines[0]]
    for line in lines[1:]:
        if not line:
            continue
        lower = line.lower()
        if lower.startswith(b"connection:"):
            continue
        if drop_range and (lower.startswith(b"range:") or lower.startswith(b"if-range:")):
            continue
        rewritten.append(line)
    # The backend must close after one response, or the relay would wait forever.
    rewritten.append(b"Connection: close")
    return b"\r\n".join(rewritten) + b"\r\n\r\n"


def parse_status(head):
    parts = head.split(b"\r\n", 1)[0].decode("latin-1").split(" ", 2)
    if len(parts) >= 2 and parts[1].isdigit():
        return int(parts[1])
    return None


def send_status(client, code):
    phrase = BaseHTTPRequestHandler.responses.get(code, ("Unknown", ""))[0]
    response = f"HTTP/1.1 {code} {phrase}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    client.sendall(response.encode("latin-1"))


def pace(sent, rate, start):
    delay = sent / rate - (time.monotonic() - start)
    if delay > 0:
        time.sleep(delay)


def relay(backend, client, mode, rule, entry):
    """Stream the backend response to the client, applying the rule to body bytes.

    The entry's status and body bytes follow the relay, so a request that the client cuts
    short is logged with what it received.
    """
    head, pending = read_head(backend)
    if head is None:
        raise ConnectionError("backend closed before the response head")
    client.sendall(head)
    entry["status"] = parse_status(head)
    sent = 0
    start = time.monotonic()
    while True:
        if pending:
            chunk = pending
            pending = b""
        else:
            chunk = backend.recv(CHUNK_SIZE)
            if not chunk:
                break
        if mode == "drop":
            remaining = rule.bytes - sent
            if remaining <= 0:
                break
            chunk = chunk[:remaining]
            client.sendall(chunk)
            sent += len(chunk)
            entry["body_bytes"] = sent
            if sent >= rule.bytes:
                break
        else:
            client.sendall(chunk)
            sent += len(chunk)
            entry["body_bytes"] = sent
            if mode == "throttle":
                pace(sent, rule.rate, start)
    if mode == "drop":
        try:
            client.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def handle_client(proxy, client, sequence):
    entry = {
        "method": None,
        "path": None,
        "range": None,
        "if_range": None,
        "rule": None,
        "status": None,
        "body_bytes": 0,
    }
    backend = None
    try:
        client.settimeout(SOCKET_TIMEOUT)
        head, rest = read_head(client)
        if head is None:
            raise ConnectionError("client closed before the request head")
        method, target, headers = parse_request(head)
        entry["method"] = method
        entry["path"] = target
        entry["range"] = headers.get("range")
        entry["if_range"] = headers.get("if-range")
        rule = proxy.select_rule(target)
        mode = rule.mode if rule is not None else "pass"
        entry["rule"] = rule.mode if rule is not None else None
        # Logged on arrival and updated as the response flows, so a request in progress shows.
        proxy.record(sequence, entry)
        length = int(headers.get("content-length", "0") or 0)
        body = rest
        while len(body) < length:
            chunk = client.recv(min(65536, length - len(body)))
            if not chunk:
                raise ConnectionError("client closed during the request body")
            body += chunk
        if mode == "status":
            send_status(client, rule.code)
            entry["status"] = rule.code
            return
        if mode == "refuse":
            return
        backend = socket.create_connection((HOST, proxy.backend), timeout=SOCKET_TIMEOUT)
        backend.sendall(rewrite_head(head, mode == "ignore_range") + body)
        relay(backend, client, mode, rule, entry)
    except (OSError, ValueError) as error:
        print(f"fault_proxy: {error}", flush=True)
    finally:
        for sock in (backend, client):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


class ControlHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    proxy = None

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._reply({"ok": True})
        elif path == "/log":
            self._reply({"requests": self.proxy.snapshot_log()})
        else:
            self._reply({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._reply({"ok": False, "error": "bad Content-Length"}, 400)
            return
        raw = self.rfile.read(length) if length > 0 else b""
        if path == "/rules":
            try:
                self.proxy.set_rules(json.loads(raw.decode("utf-8")))
            except (ValueError, TypeError) as error:
                self._reply({"ok": False, "error": str(error)}, 400)
                return
            self._reply({"ok": True})
        elif path == "/reset":
            self.proxy.reset()
            self._reply({"ok": True})
        else:
            self._reply({"ok": False, "error": "not found"}, 404)

    def _reply(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description="HTTP forwarding proxy with transfer faults")
    parser.add_argument("--listen", type=int, default=13380)
    parser.add_argument("--backend", type=int, default=13378)
    parser.add_argument("--control", type=int, default=13390)
    args = parser.parse_args()

    proxy = Proxy(args.backend)
    ControlHandler.proxy = proxy
    control = ThreadingHTTPServer((HOST, args.control), ControlHandler)
    control.daemon_threads = True
    threading.Thread(target=control.serve_forever, daemon=True).start()

    listener = socket.create_server((HOST, args.listen), reuse_port=True)
    print(
        f"http://127.0.0.1:{args.listen}/ -> 127.0.0.1:{args.backend}, "
        f"control on 127.0.0.1:{args.control}",
        flush=True,
    )
    try:
        while True:
            try:
                client, _ = listener.accept()
            except OSError as error:
                print(f"fault_proxy: accept error: {error}", flush=True)
                continue
            threading.Thread(
                target=handle_client,
                args=(proxy, client, proxy.next_sequence()),
                daemon=True,
            ).start()
    except KeyboardInterrupt:
        pass
    finally:
        listener.close()
        control.shutdown()
        control.server_close()


if __name__ == "__main__":
    main()
