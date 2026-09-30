#!/usr/bin/env python3
"""Throwaway RS256 signer and JWKS publisher for the local TMS <-> EWM end-to-end run.

Stands in for Supabase Auth on a developer machine. No third-party packages: the key is made
and used by the `openssl` binary, and this file only does base64url and JSON.

    jwt_tool.py keygen <dir>                       # <dir>/signing.pem (+ jwks/.well-known/jwks.json)
    jwt_tool.py mint <dir> <issuer> <sub> [ttl_s]  # prints a signed JWT
    jwt_tool.py serve <dir> <port>                 # serves <dir>/jwks on 127.0.0.1:<port>

Nothing here is a real secret: the key is generated per run into a disposable directory.
"""
import base64
import http.server
import json
import os
import subprocess
import sys
import time
import uuid

KID = "tms-ewm-e2e"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def keygen(workdir: str) -> None:
    os.makedirs(os.path.join(workdir, "jwks", ".well-known"), exist_ok=True)
    pem = os.path.join(workdir, "signing.pem")
    if not os.path.exists(pem):
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", pem], check=True, capture_output=True)
        os.chmod(pem, 0o600)
    modulus_hex = subprocess.run(["openssl", "rsa", "-in", pem, "-noout", "-modulus"], check=True,
                                 capture_output=True, text=True).stdout.strip().split("=", 1)[1]
    n = bytes.fromhex(modulus_hex)
    jwk = {"kty": "RSA", "kid": KID, "use": "sig", "alg": "RS256",
           "n": b64url(n), "e": b64url((65537).to_bytes(3, "big"))}
    with open(os.path.join(workdir, "jwks", ".well-known", "jwks.json"), "w") as f:
        json.dump({"keys": [jwk]}, f)


def mint(workdir: str, issuer: str, sub: str, ttl: int = 7200) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT", "kid": KID}
    claims = {"iss": issuer, "sub": sub, "aud": "authenticated", "role": "authenticated",
              "iat": now - 5, "nbf": now - 5, "exp": now + ttl, "jti": str(uuid.uuid4()),
              "email": "e2e-admin@example.test"}
    signing_input = (b64url(json.dumps(header, separators=(",", ":")).encode()) + "." +
                     b64url(json.dumps(claims, separators=(",", ":")).encode()))
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", os.path.join(workdir, "signing.pem")],
                         input=signing_input.encode(), check=True, capture_output=True).stdout
    return signing_input + "." + b64url(sig)


def serve(workdir: str, port: int) -> None:
    root = os.path.join(workdir, "jwks")

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=root, **kw)

        def log_message(self, *args):
            pass

    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "keygen":
        keygen(sys.argv[2])
    elif cmd == "mint":
        print(mint(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]) if len(sys.argv) > 5 else 7200))
    elif cmd == "serve":
        serve(sys.argv[2], int(sys.argv[3]))
    else:
        sys.exit("unknown command " + cmd)
