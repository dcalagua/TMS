"""Shared helpers for the local TMS <-> EWM end-to-end harness.

Standard library only. Everything talks to localhost and to the two disposable database
containers this harness created; nothing here can reach a shared database.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("E2E_WORK", "/tmp/tms-ewm-e2e")
TMS_URL = os.environ.get("TMS_URL", "http://localhost:8080")
EWM_URL = os.environ.get("EWM_URL", "http://localhost:8081")
TMS_DB_CONTAINER = os.environ.get("TMS_DB_CONTAINER", "tmsewm-e2e-tms-db")
EWM_DB_CONTAINER = os.environ.get("EWM_DB_CONTAINER", "tmsewm-e2e-ewm-db")
JWT_ISSUER = os.environ.get("TMS_JWT_ISSUER", "http://127.0.0.1:55499/auth/v1")

EVIDENCE = []


def load_env(path):
    values = {}
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k] = v
    return values


def save_env(path, **kv):
    values = load_env(path)
    values.update({k: str(v) for k, v in kv.items()})
    with open(path, "w") as f:
        for k, v in values.items():
            f.write(f"{k}={v}\n")
    os.chmod(path, 0o600)


STATE_FILE = os.path.join(WORK, "state.env")


def state():
    return load_env(STATE_FILE)


def log(msg):
    print(msg, flush=True)


class Response:
    def __init__(self, status, body, headers):
        self.status = status
        self.raw = body
        self.headers = headers
        try:
            self.json = json.loads(body) if body else None
        except ValueError:
            self.json = None

    def __repr__(self):
        return f"<{self.status} {self.raw[:300]!r}>"


EWM_SUB = "0e2e0000-0000-4000-8000-0000000000b1"   # public.app_users.auth_user_id of the EWM admin
_EWM_TOKEN = {"v": None, "at": 0}


def ewm_token():
    if _EWM_TOKEN["v"] is None or time.time() - _EWM_TOKEN["at"] > 1800:
        _EWM_TOKEN["v"] = subprocess.run([sys.executable, os.path.join(HERE, "jwt_tool.py"), "mint", WORK,
                                          JWT_ISSUER, EWM_SUB], check=True, capture_output=True,
                                         text=True).stdout.strip()
        _EWM_TOKEN["at"] = time.time()
    return _EWM_TOKEN["v"]


def http(method, url, body=None, headers=None, raw=None, timeout=30):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Accept": "application/json"}
    if url.startswith(EWM_URL) and "/integration/tms/webhooks/" not in url:
        h["Authorization"] = "Bearer " + ewm_token()   # EWM runs in jwt mode against the local JWKS
    if data is not None:
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Response(r.status, r.read().decode(), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Response(e.code, e.read().decode(), dict(e.headers))


def expect(resp, *codes, what=""):
    if resp.status not in codes:
        raise AssertionError(f"{what}: expected {codes}, got {resp.status}: {resp.raw[:1500]}")
    return resp


def tms_token():
    st = state()
    out = subprocess.run([sys.executable, os.path.join(HERE, "jwt_tool.py"), "mint", WORK, JWT_ISSUER,
                          st["TMS_SUB"]], check=True, capture_output=True, text=True).stdout.strip()
    return out


_TOKEN = {"v": None, "at": 0}


def tms(method, path, body=None, headers=None, raw=None):
    if _TOKEN["v"] is None or time.time() - _TOKEN["at"] > 1800:
        _TOKEN["v"], _TOKEN["at"] = tms_token(), time.time()
    st = state()
    h = {"Authorization": "Bearer " + _TOKEN["v"], "X-Company-Id": st["TMS_CO"]}
    h.update(headers or {})
    return http(method, TMS_URL + path, body, h, raw)


def tms_integration(method, path, body=None, headers=None, raw=None):
    h = {"Authorization": "Bearer " + state()["TMS_INTEGRATION_BEARER"]}
    h.update(headers or {})
    return http(method, TMS_URL + path, body, h, raw)


def psql(container, db, user, sql):
    p = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", user, "-d", db, "-v",
                        "ON_ERROR_STOP=1", "-At", "-F", "|", "-c", sql], capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"psql failed on {container}: {p.stderr.strip()}\nSQL: {sql}")
    out = p.stdout
    return [line.split("|") for line in out.strip().splitlines() if line]


def tms_sql(sql):
    return psql(TMS_DB_CONTAINER, "tms_e2e", "postgres", sql)


def ewm_sql(sql):
    return psql(EWM_DB_CONTAINER, "wms", "wms_app", sql)


def wait_until(fn, timeout=120, interval=2, what="condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError(f"timed out waiting for {what} (last={last!r})")


def record(scenario, key, value):
    EVIDENCE.append({"scenario": scenario, "key": key, "value": value})
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    log(f"  [{scenario}] {key}: {text[:2000]}")


def dump_evidence(name):
    path = os.path.join(WORK, f"evidence-{name}.json")
    with open(path, "w") as f:
        json.dump(EVIDENCE, f, indent=2, ensure_ascii=False, default=str)
    log(f"evidence written to {path}")
