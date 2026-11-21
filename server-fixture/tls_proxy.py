#!/usr/bin/env python3
"""TLS front for the local reference server, for the certificate cases of M2.

Terminates TLS with a certificate from server-fixture/.state/tls/ and forwards the bytes to the
plain HTTP instance, so the emulator reaches one Audiobookshelf server through a second, HTTPS
origin. Run it with a self-signed certificate to see the unrecognized-certificate path, then
restart it with another certificate to see the changed-certificate path.

    server-fixture/tls_proxy.py --listen 13443 --backend 13378 --cert NAME

NAME selects .state/tls/NAME.pem and NAME.key; missing files are generated as a self-signed
certificate for "pageworn-fixture.invalid", a host name the emulator never dials, so only a
confirmed fingerprint can make the client accept it. The proxy listens on 127.0.0.1 only;
the emulator reaches it as 10.0.2.2.

With --issued, NAME is instead a certificate for the emulator's host alias 10.0.2.2, issued by
the fixture authority .state/tls/authority.pem, which the check installs on the emulator as a
user-added authority (case A26c). Every key and certificate is generated locally and lives only
in the ignored .state directory.
"""

import argparse
import pathlib
import socket
import ssl
import subprocess
import threading

STATE = pathlib.Path(__file__).resolve().parent / ".state" / "tls"


def ensure_certificate(name: str) -> tuple[pathlib.Path, pathlib.Path]:
    STATE.mkdir(parents=True, exist_ok=True)
    cert, key = STATE / f"{name}.pem", STATE / f"{name}.key"
    if cert.exists() and key.exists():
        return cert, key
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-nodes", "-days", "30", "-subj", "/CN=pageworn-fixture.invalid",
            "-addext", "subjectAltName=DNS:pageworn-fixture.invalid",
            "-keyout", str(key), "-out", str(cert),
        ],
        check=True,
        capture_output=True,
    )
    return cert, key


def ensure_authority() -> tuple[pathlib.Path, pathlib.Path]:
    STATE.mkdir(parents=True, exist_ok=True)
    cert, key = STATE / "authority.pem", STATE / "authority.key"
    if cert.exists() and key.exists():
        return cert, key
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-nodes", "-days", "30", "-subj", "/CN=Pageworn fixture authority",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-keyout", str(key), "-out", str(cert),
        ],
        check=True,
        capture_output=True,
    )
    return cert, key


def ensure_issued_certificate(name: str) -> tuple[pathlib.Path, pathlib.Path]:
    authority, authority_key = ensure_authority()
    cert, key = STATE / f"{name}.pem", STATE / f"{name}.key"
    if cert.exists() and key.exists():
        return cert, key
    request = STATE / f"{name}.csr"
    extensions = STATE / f"{name}.ext"
    extensions.write_text(
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature\n"
        "extendedKeyUsage=serverAuth\n"
        "subjectAltName=IP:10.0.2.2\n"
    )
    subprocess.run(
        [
            "openssl", "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-nodes", "-subj", "/CN=10.0.2.2", "-keyout", str(key), "-out", str(request),
        ],
        check=True,
        capture_output=True,
    )
    subprocess.run(
        [
            "openssl", "x509", "-req", "-in", str(request), "-CA", str(authority), "-CAkey", str(authority_key),
            "-CAcreateserial", "-days", "30", "-extfile", str(extensions), "-out", str(cert),
        ],
        check=True,
        capture_output=True,
    )
    request.unlink()
    extensions.unlink()
    return cert, key


def fingerprint(cert: pathlib.Path) -> str:
    out = subprocess.run(
        ["openssl", "x509", "-noout", "-fingerprint", "-sha256", "-in", str(cert)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return out.split("=", 1)[1]


def pipe(source: socket.socket, sink: socket.socket) -> None:
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            sink.sendall(data)
    except OSError:
        pass
    finally:
        for s in (source, sink):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def serve(client: socket.socket, context: ssl.SSLContext, backend: int) -> None:
    try:
        tls = context.wrap_socket(client, server_side=True)
    except (ssl.SSLError, OSError):
        # The client refused the certificate: no request reached the server.
        client.close()
        return
    upstream = socket.create_connection(("127.0.0.1", backend))
    threading.Thread(target=pipe, args=(tls, upstream), daemon=True).start()
    pipe(upstream, tls)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", type=int, default=13443)
    parser.add_argument("--backend", type=int, default=13378)
    parser.add_argument("--cert", default="first")
    parser.add_argument("--issued", action="store_true", help="issue NAME for 10.0.2.2 from the fixture authority")
    parser.add_argument("--generate-only", action="store_true", help="create the certificate and print its fingerprint")
    args = parser.parse_args()

    cert, key = ensure_issued_certificate(args.cert) if args.issued else ensure_certificate(args.cert)
    if args.generate_only:
        print(fingerprint(cert))
        return
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    print(f"https://127.0.0.1:{args.listen}/ -> 127.0.0.1:{args.backend}, sha256 {fingerprint(cert)}", flush=True)

    listener = socket.create_server(("127.0.0.1", args.listen), reuse_port=True)
    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client, context, args.backend), daemon=True).start()


if __name__ == "__main__":
    main()
