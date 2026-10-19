#!/usr/bin/env python3
"""PLAPASA demo, transport side: the ERP's orders into the TMS company QAS-PLAPASA.

DISPOSABLE ENVIRONMENTS ONLY - TMS QAS or a local stack. NEVER production.

Run it AFTER supabase/seeds/plapasa_demo_seed.sql and AFTER the material profiles exist (sent by the
EWM with «Reenviar perfiles al TMS», or, only if that link is not live,
supabase/seeds/plapasa_demo_material_profiles_fallback.sql): an order line copies the profile the
moment it is written, and a profile that arrives later rewrites nothing (ADR-022 §2).

Everything goes through the public HTTP API, the way PLAPASA's ERP would: the orders of
scripts/demo/plapasa/orders.json, in one POST /integration/v1/orders/batch. No table is written.
The business key is (externalSource, externalReference), so running it twice answers UNCHANGED and
creates nothing - it can be re-run safely until an order is planned (after that the ERP may not
rewrite it, and the script says so per order instead of failing).

    TMS_API_URL=https://<api-qas>  SUPABASE_URL=https://<project>.supabase.co \\
    SUPABASE_ANON_KEY=...  DEMO_PASSWORD=...  python3 scripts/demo/plapasa_tms_demo.py check
    ... python3 scripts/demo/plapasa_tms_demo.py orders
    ... python3 scripts/demo/plapasa_tms_demo.py orders --service-date 2026-10-21   # the demo moved a day
    ... python3 scripts/demo/plapasa_tms_demo.py guide    # optional: the SRI remission guide (06) of SO-2026-10040

Authentication for the person (to read, and to create the ERP credential), one of:
    TMS_ADMIN_TOKEN            a bearer token for admin@plapasa.demo
    DEMO_PASSWORD + SUPABASE_ANON_KEY [+ SUPABASE_URL, default http://127.0.0.1:54321]
                               signs in as TMS_DEMO_EMAIL (default admin@plapasa.demo)
The ERP credential (scopes integration.order:write and integration.document:write), one of:
    TMS_ERP_TOKEN              an existing one (tmsc_<id>.tmss_<secret>)
    (absent)                   the script creates «ERP PLAPASA (demo) <stamp>» and keeps its secret in
                               memory for this run only - it is never printed. Revoke it afterwards in
                               Configuración › Integraciones, or reuse it by exporting TMS_ERP_TOKEN.
Other settings: TMS_COMPANY_CODE (default QAS-PLAPASA).
"""
import argparse
import datetime as dt
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("TMS_API_URL", "http://localhost:8080").rstrip("/")
COMPANY_CODE = os.environ.get("TMS_COMPANY_CODE", "QAS-PLAPASA")
ORDERS_FILE = pathlib.Path(__file__).resolve().parent / "plapasa" / "orders.json"
GUIDE_FILE = pathlib.Path(__file__).resolve().parent / "plapasa" / "remission_guide.json"

# The materials whose profile makes the demo's point. Without them the cups count 1.90 m3 per 1,000 again.
KEY_PROFILES = {"PLP-571": "NESTABLE", "PLP-111": "NESTABLE", "PLP-N105": "NESTABLE", "PLP-1601": "STACKABLE",
                "PLP-1608": "STACKABLE", "PLP-1623": "STACKABLE"}


class ApiError(RuntimeError):
    pass


def call(method, path, token, body=None, company=None, expect=(200, 201)):
    url = path if path.startswith("http") else API + path
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if company:
        request.add_header("X-Company-Id", company)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload, status = response.read(), response.status
    except urllib.error.HTTPError as failure:
        payload, status = failure.read(), failure.code
    if status not in expect:
        raise ApiError(f"{method} {path} -> {status}: {payload.decode(errors='replace')[:600]}")
    return json.loads(payload) if payload else None


def person_token():
    token = os.environ.get("TMS_ADMIN_TOKEN")
    if token:
        return token
    password, anon = os.environ.get("DEMO_PASSWORD"), os.environ.get("SUPABASE_ANON_KEY")
    if not password or not anon:
        sys.exit("Set TMS_ADMIN_TOKEN, or DEMO_PASSWORD and SUPABASE_ANON_KEY (see the docstring).")
    email = os.environ.get("TMS_DEMO_EMAIL", "admin@plapasa.demo")
    supabase = os.environ.get("SUPABASE_URL", "http://127.0.0.1:54321").rstrip("/")
    request = urllib.request.Request(supabase + "/auth/v1/token?grant_type=password",
                                     data=json.dumps({"email": email, "password": password}).encode(),
                                     method="POST")
    request.add_header("apikey", anon)
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())["access_token"]
    except urllib.error.HTTPError as failure:
        sys.exit(f"Sign-in as {email} failed: {failure.code} (does the account exist in this Supabase project?)")


def company_id(token):
    me = call("GET", "/api/v1/me", token)
    for company in me.get("companies", []):
        if company.get("code") == COMPANY_CODE:
            return company["id"]
    sys.exit(f"{COMPANY_CODE} is not among this user's companies - did plapasa_demo_seed.sql run, and was the "
             f"auth account created BEFORE it (the seed links people by email)?")


def load_orders(service_date):
    data = json.loads(ORDERS_FILE.read_text(encoding="utf-8"))
    base = dt.date.fromisoformat(data["baseServiceDate"])
    shift = (dt.date.fromisoformat(service_date) - base) if service_date else dt.timedelta(0)
    orders = []
    for order in data["orders"]:
        body = {k: v for k, v in order.items() if k != "rebase"}
        if order.get("rebase") and shift:
            body["serviceDate"] = (dt.date.fromisoformat(order["serviceDate"]) + shift).isoformat()
        body["externalSource"] = os.environ.get("TMS_SOURCE_SYSTEM", data["externalSource"])
        body["originCode"] = data["originCode"]
        orders.append(body)
    return orders


def profiles(token, company):
    found = {}
    page = 0
    while True:
        result = call("GET", f"/api/v1/masterdata/material-profiles?search=PLP&size=100&page={page}", token,
                      company=company)
        for item in result.get("content", []):
            found[item["materialCode"]] = item
        page += 1
        if not result.get("content") or page * result.get("size", 100) >= result.get("totalElements", 0):
            return found


def check(token, company):
    found = profiles(token, company)
    print(f"Perfiles de material en {COMPANY_CODE}: {len(found)}")
    missing = []
    for code, mode in KEY_PROFILES.items():
        item = found.get(code)
        if not item or item.get("stackMode") != mode:
            missing.append(code)
            print(f"  {code}: FALTA o sin modo {mode}")
        else:
            print(f"  {code}: {mode} · origen {item.get('sourceSystem')} · actualizado en origen "
                  f"{item.get('sourceUpdatedAt')}")
    return missing


def erp_token(token, company):
    erp = os.environ.get("TMS_ERP_TOKEN")
    if erp:
        return erp
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    created = call("POST", "/api/v1/integration-clients", token, company=company, body={
        "name": f"ERP PLAPASA (demo) {stamp}",
        "description": "scripts/demo/plapasa_tms_demo.py - revocar al terminar",
        "scopes": ["integration.order:write", "integration.document:write"]})
    print(f"\nCredencial del ERP creada: {created['clientId']} (secreto sólo en memoria; "
          f"exportar TMS_ERP_TOKEN para reutilizar una)")
    return created["bearerToken"]


def send_guide(token, company):
    guide = json.loads(GUIDE_FILE.read_text(encoding="utf-8"))
    guide.pop("$nota", None)
    source = os.environ.get("TMS_SOURCE_SYSTEM")
    if source:
        guide["sourceSystem"] = source
        for order in guide["orders"]:
            order["externalSource"] = source
    result = call("POST", "/integration/v1/logistics-documents", erp_token(token, company), body=guide)
    print(f"Guía de remisión {guide['documentNumber']} (SRI 06): {result['outcome']} · pedidos enlazados "
          f"{result['linkedOrders']} · desconocidos {result.get('unknownOrders') or []}")


def send_orders(token, company, service_date, allow_missing):
    missing = check(token, company)
    if missing and not allow_missing:
        sys.exit("\nFaltan perfiles: los pedidos entrarían sin geometría y no se pueden volver a copiar "
                 "después. Reenviarlos desde el EWM (o el respaldo SQL) y repetir; --allow-missing-profiles "
                 "para seguir igual.")
    erp = erp_token(token, company)
    orders = load_orders(service_date)
    result = call("POST", "/integration/v1/orders/batch", erp, body={"orders": orders}, expect=(200, 207))
    print(f"\nERP -> TMS: {result['submitted']} pedidos, {result['succeeded']} bien, {result['failed']} rechazados")
    for item in result["results"]:
        if item.get("error"):
            print(f"  {item['reference']}: RECHAZADO {item['error']}")
            continue
        outcome = item["result"]
        refused = outcome.get("releaseRefusedBy") or []
        print(f"  {item['reference']} -> {outcome['orderNumber']} {outcome['outcome']} {outcome['status']}"
              + (f" (no liberado: {', '.join(refused)})" if refused else ""))
        detail = call("GET", f"/api/v1/orders/{outcome['id']}", token, company=company)
        geometry = detail.get("geometry") or {}
        print(f"      declarado {detail.get('totalVolumeM3')} m³ · ocupa {geometry.get('planningVolumeM3')} m³ · "
              f"{geometry.get('stacks')} pilas · piso {geometry.get('floorAreaM2')} m² · alto "
              f"{geometry.get('maxStackHeightM')} m · líneas sin medir {geometry.get('unmeasuredLines')}")


def main():
    parser = argparse.ArgumentParser(description="PLAPASA demo orders into TMS (disposable environments only).")
    parser.add_argument("command", nargs="?", default="check", choices=["check", "orders", "guide"],
                        help="check (read only, default), orders (send the ERP's orders) or guide "
                             "(send the SRI remission guide of SO-2026-10040)")
    parser.add_argument("--service-date", help="re-base the dated orders: the date of SO-2026-10040 "
                                               "(default 2026-10-20; the cup campaign goes the day after)")
    parser.add_argument("--allow-missing-profiles", action="store_true")
    arguments = parser.parse_args()
    token = person_token()
    company = company_id(token)
    if arguments.command == "check":
        missing = check(token, company)
        sys.exit(1 if missing else 0)
    if arguments.command == "guide":
        send_guide(token, company)
        return
    send_orders(token, company, arguments.service_date, arguments.allow_missing_profiles)


if __name__ == "__main__":
    try:
        main()
    except ApiError as failure:
        sys.exit(str(failure))
