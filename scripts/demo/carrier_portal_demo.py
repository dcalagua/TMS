#!/usr/bin/env python3
"""Builds demo trips for the carrier portal and the document delivery monitor (ADR-018).

DISPOSABLE ENVIRONMENTS ONLY - a local stack or a demo/QA project. NEVER production.

Everything goes through the public HTTP API, the way production gets there: the ERP sends orders
and their invoices/guides, a planner builds and confirms the trips, and the warehouse sends the
dispatch. No table is written directly. Run it after the seeds:

    supabase/seeds/local_dev_seed.sql
    supabase/seeds/carrier_portal_demo_seed.sql            (DEMO-LIMA)
    supabase/seeds/carrier_portal_demo_seed_arequipa.sql   (DEMO-AREQUIPA)
    supabase/seeds/demo_auth_users.sql   (-v demo_password=...)

and with the backend running with an evidence store, e.g.

    TMS_EVIDENCE_STORAGE_MODE=LOCAL TMS_EVIDENCE_STORAGE_ROOT=/tmp/tms-evidence scripts/dev-backend.sh

Then:

    DEMO_PASSWORD=... SUPABASE_ANON_KEY=... python3 scripts/demo/carrier_portal_demo.py
    DEMO_PASSWORD=... SUPABASE_ANON_KEY=... TMS_COMPANY_CODE=DEMO-AREQUIPA python3 scripts/demo/carrier_portal_demo.py
    DEMO_PASSWORD=... SUPABASE_ANON_KEY=... python3 scripts/demo/carrier_portal_demo.py --all

Authentication, one of:
    TMS_ADMIN_TOKEN       a bearer token for admin@demo.local (organization-wide: reaches both companies)
                          or for an administrator of the company being populated
    DEMO_PASSWORD + SUPABASE_ANON_KEY [+ SUPABASE_URL, default http://127.0.0.1:54321]
                          signs admin@demo.local in against Supabase Auth
Other settings: TMS_API_URL (default http://localhost:8080), TMS_COMPANY_CODE (default DEMO-LIMA;
DEMO-AREQUIPA, or ALL for both - the same as --all; --company CODE overrides the variable).

Each company has a profile in PROFILES: its depot, customers, vehicles, drivers and document series,
matching its seed file. What it leaves in each company, dated today (plus a GPS trail per dispatched
truck for the map):
    DEMO-LIMA      three DISPATCHED trips - two of carrier TRSA, one of TRSB - and one CONFIRMED,
                   not dispatched, TRSA trip for tomorrow;
    DEMO-AREQUIPA  three DISPATCHED trips - two of carrier TRAQ, one of TRSA (its Arequipa row) - and
                   one CONFIRMED, not dispatched, TRAQ trip for tomorrow.
The dispatched trips' documents (an invoice that covers two orders, separate invoices, GRE remitente,
guia) are PENDING for the carrier; the undispatched trip stays invisible in the portal until the
warehouse dispatches it - which shows the visibility rule.
Per company, three integration credentials ("Demo ERP ...", "Demo EWM ...", "Demo GPS ...") are
created - a credential belongs to one company; their secrets are kept in memory for this run only and
never printed. Revoke them from Integraciones when done.

Each run creates new orders and trips (keys carry a run suffix), so it can be repeated.
"""
import argparse
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from zoneinfo import ZoneInfo

API = os.environ.get("TMS_API_URL", "http://localhost:8080").rstrip("/")
COMPANY_CODE = os.environ.get("TMS_COMPANY_CODE", "DEMO-LIMA")
LIMA = ZoneInfo("America/Lima")
RUN = dt.datetime.now(LIMA).strftime("%m%d%H%M%S")

# One profile per company, matching its seed file. A trip is
#     (day offset, vehicle code, driver code, dispatched?, [order keys], [documents])
# and a document is (type, number template, [order keys]) - an invoice may cover several orders;
# "{run}" in a number is replaced by the run suffix. An order's service date is its trip's day.
PROFILES = {
    # supabase/seeds/carrier_portal_demo_seed.sql
    "DEMO-LIMA": {
        "origin": "CD-LIMA",
        "warehouse": "CD01",
        # Where the seed puts the depot and the customers, for the GPS trail (ADR-007: it informs only).
        "coordinates": {"CD-LIMA": (-12.0464, -77.0900), "CLI-PLAZA-NORTE": (-12.0067, -77.0590),
                        "CLI-SURCO": (-12.1350, -76.9900), "CLI-CALLAO": (-11.9950, -77.1200)},
        "destinations": {"A1": "CLI-PLAZA-NORTE", "A2": "CLI-SURCO", "A3": "CLI-CALLAO", "B1": "CLI-SURCO",
                         "A4": "CLI-PLAZA-NORTE"},
        "trips": [
            (0, "DEMO-VEH-A1", "DEMO-COND-A1", True, ["A1", "A2"], [
                ("INVOICE", "F001-{run}1", ["A1", "A2"]),
                ("GRE_REMITENTE", "T001-{run}1", ["A1"]),
                ("GRE_REMITENTE", "T001-{run}2", ["A2"]),
            ]),
            (0, "DEMO-VEH-A2", "DEMO-COND-A2", True, ["A3"], [
                ("INVOICE", "F001-{run}2", ["A3"]),
                ("DELIVERY_NOTE", "GR-{run}3", ["A3"]),
            ]),
            (0, "DEMO-VEH-B1", "DEMO-COND-B1", True, ["B1"], [
                ("INVOICE", "F002-{run}1", ["B1"]),
                ("GRE_REMITENTE", "T002-{run}1", ["B1"]),
            ]),
            (1, "DEMO-VEH-A1", "DEMO-COND-A1", False, ["A4"], [
                ("INVOICE", "F001-{run}4", ["A4"]),
            ]),
        ],
        "done": ["Sign in as transportista.trsa@demo.local or transportista.trsb@demo.local to see the portal,",
                 "and as admin@demo.local or planner.lima@demo.local for Entrega de documentos."],
    },
    # supabase/seeds/carrier_portal_demo_seed_arequipa.sql
    "DEMO-AREQUIPA": {
        "origin": "CD-AREQUIPA",
        "warehouse": "CD02",
        "coordinates": {"CD-AREQUIPA": (-16.4237, -71.5196), "CLI-CAYMA": (-16.3925, -71.5455),
                        "CLI-PAUCARPATA": (-16.4290, -71.5050), "CLI-CERRO-COLORADO": (-16.3600, -71.5650)},
        "destinations": {"Q1": "CLI-CAYMA", "Q2": "CLI-PAUCARPATA", "Q3": "CLI-CERRO-COLORADO",
                         "S1": "CLI-PAUCARPATA", "Q4": "CLI-CAYMA"},
        "trips": [
            (0, "DEMO-VEH-Q1", "DEMO-COND-Q1", True, ["Q1", "Q2"], [
                ("INVOICE", "F003-{run}1", ["Q1", "Q2"]),
                ("GRE_REMITENTE", "T003-{run}1", ["Q1"]),
                ("GRE_REMITENTE", "T003-{run}2", ["Q2"]),
            ]),
            (0, "DEMO-VEH-Q2", "DEMO-COND-Q2", True, ["Q3"], [
                ("INVOICE", "F003-{run}2", ["Q3"]),
                ("DELIVERY_NOTE", "GRA-{run}3", ["Q3"]),
            ]),
            # TRSA's Arequipa row: transportista.trsa@demo.local sees it after switching company.
            (0, "DEMO-VEH-S1", "DEMO-COND-S1", True, ["S1"], [
                ("INVOICE", "F004-{run}1", ["S1"]),
                ("GRE_REMITENTE", "T004-{run}1", ["S1"]),
            ]),
            (1, "DEMO-VEH-Q1", "DEMO-COND-Q1", False, ["Q4"], [
                ("INVOICE", "F003-{run}4", ["Q4"]),
            ]),
        ],
        "done": ["Sign in as transportista.traq@demo.local, or as transportista.trsa@demo.local and switch to",
                 "DEMO-AREQUIPA, to see the portal, and as admin@demo.local or planner.arequipa@demo.local",
                 "for Entrega de documentos."],
    },
}


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
            payload = response.read()
            status = response.status
    except urllib.error.HTTPError as failure:
        payload = failure.read()
        status = failure.code
    if status not in expect:
        raise ApiError(f"{method} {path} -> {status}: {payload.decode(errors='replace')[:600]}")
    return json.loads(payload) if payload else None


def admin_token():
    token = os.environ.get("TMS_ADMIN_TOKEN")
    if token:
        return token
    password = os.environ.get("DEMO_PASSWORD")
    anon = os.environ.get("SUPABASE_ANON_KEY")
    if not password or not anon:
        sys.exit("Set TMS_ADMIN_TOKEN, or DEMO_PASSWORD and SUPABASE_ANON_KEY (see the docstring).")
    supabase = os.environ.get("SUPABASE_URL", "http://127.0.0.1:54321").rstrip("/")
    request = urllib.request.Request(
        supabase + "/auth/v1/token?grant_type=password",
        data=json.dumps({"email": "admin@demo.local", "password": password}).encode(),
        method="POST")
    request.add_header("apikey", anon)
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())["access_token"]
    except urllib.error.HTTPError as failure:
        sys.exit(f"Sign-in as admin@demo.local failed: {failure.code}")


def by_code(items, code, field="code"):
    for item in items:
        if item.get(field) == code:
            return item
    raise ApiError(f"{code} not found - did carrier_portal_demo_seed.sql run?")


def page(token, company, path):
    result = call("GET", path + ("&" if "?" in path else "?") + "size=200", token, company=company)
    return result["content"] if isinstance(result, dict) and "content" in result else result


def populate(token, me, company_code):
    profile = PROFILES[company_code]
    company = by_code(me["companies"], company_code)["id"]
    print(f"Company {company_code}: {company}")

    locations = page(token, company, "/api/v1/masterdata/locations")
    origin = by_code(locations, profile["origin"])
    vehicles = page(token, company, "/api/v1/fleet/vehicles")
    drivers = page(token, company, "/api/v1/fleet/drivers")

    erp = call("POST", "/api/v1/integration-clients", token, company=company, body={
        "name": f"Demo ERP {RUN}", "description": "carrier_portal_demo.py - revoke when done",
        "scopes": ["integration.order:write", "integration.document:write"]})["bearerToken"]
    ewm = call("POST", "/api/v1/integration-clients", token, company=company, body={
        "name": f"Demo EWM {RUN}", "description": "carrier_portal_demo.py - revoke when done",
        "scopes": ["integration.dispatch:write"]})["bearerToken"]
    gps = call("POST", "/api/v1/integration-clients", token, company=company, body={
        "name": f"Demo GPS {RUN}", "description": "carrier_portal_demo.py - revoke when done",
        "scopes": ["integration.tracking:write"]})["bearerToken"]

    today = dt.datetime.now(LIMA).date()

    # trip: (date, vehicle code, driver code, dispatched?, [order keys], [documents])
    plan = [
        (today + dt.timedelta(days=day), vehicle_code, driver_code, dispatched, keys,
         [(document_type, number.format(run=RUN), document_keys) for document_type, number, document_keys in documents])
        for day, vehicle_code, driver_code, dispatched, keys, documents in profile["trips"]
    ]
    coordinates = profile["coordinates"]
    destinations = profile["destinations"]
    service_dates = {key: date for date, _, _, _, keys, _ in plan for key in keys}

    # 1. The ERP sends the orders.
    references = {}
    order_ids = {}
    for key, destination in destinations.items():
        service_date = service_dates[key]
        reference = f"PED-DEMO-{RUN}-{key}"
        intake = call("POST", "/integration/v1/orders", erp, body={
            "externalSource": "SAPB1_PE", "externalReference": reference, "originCode": profile["origin"],
            "destinationCode": destination, "customerName": f"Cliente {destination.split('-', 1)[1].title()}",
            "serviceDate": service_date.isoformat(), "priority": "NORMAL", "markReadyForPlanning": True,
            "lines": [{"materialCode": "SKU-100", "materialDescription": "Cemento Sol 42.5kg", "quantity": 100,
                       "uom": "UN", "unitWeightKg": 10, "unitVolumeM3": 0.01}]})
        references[key] = reference
        order_ids[key] = intake["id"]
        if intake.get("status") != "READY_FOR_PLANNING":
            raise ApiError(f"order {reference} is {intake.get('status')}, not released: "
                           f"{intake.get('releaseRefusedBy')}")
    print(f"ERP: {len(references)} orders sent")

    # 2. A planner builds and confirms the trips, one plan per day.
    runs = {}
    trips = []
    for date, vehicle_code, driver_code, dispatched, keys, documents in plan:
        if date not in runs:
            created = call("POST", "/api/v1/planning/runs", token, company=company,
                           body={"originId": origin["id"], "planningDate": date.isoformat()})
            runs[date] = created["run"]["id"]
        run_id = runs[date]
        departure = dt.datetime.combine(date, dt.time(8, 0), LIMA).isoformat()
        run_version = call("GET", f"/api/v1/planning/runs/{run_id}", token, company=company)["run"]["version"]
        trip = call("POST", f"/api/v1/planning/runs/{run_id}/trips", token, company=company, body={
            "vehicleId": by_code(vehicles, vehicle_code)["id"], "plannedDepartureAt": departure,
            "version": run_version})["trip"]
        trip_id = trip["id"]
        for key in keys:
            call("POST", f"/api/v1/planning/trips/{trip_id}/assignments", token, company=company,
                 body={"orderId": order_ids[key]})
        version = call("GET", f"/api/v1/planning/trips/{trip_id}", token, company=company)["trip"]["version"]
        call("PUT", f"/api/v1/planning/trips/{trip_id}/driver", token, company=company,
             body={"driverId": by_code(drivers, driver_code)["id"], "version": version})
        trips.append((trip_id, trip["shipmentNumber"], vehicle_code, driver_code, dispatched, keys, documents))
    for date, run_id in runs.items():
        version = call("GET", f"/api/v1/planning/runs/{run_id}", token, company=company)["run"]["version"]
        call("POST", f"/api/v1/planning/runs/{run_id}/confirm", token, company=company, body={"version": version})
    print(f"Planner: {len(trips)} trips confirmed")

    # 3. The ERP sends the documents - before the dispatch, as usual.
    for _, _, _, _, _, _, documents in trips:
        for document_type, number, keys in documents:
            call("POST", "/integration/v1/logistics-documents", erp, body={
                "sourceSystem": "SAPB1_PE", "documentType": document_type, "documentNumber": number,
                "issueDate": today.isoformat(), "amount": 1180.00, "currency": "PEN",
                "orders": [{"externalSource": "SAPB1_PE", "externalReference": references[key]} for key in keys]})

    # 4. The warehouse dispatches what left today. This is what makes a trip visible to its carrier.
    for trip_id, shipment, vehicle_code, driver_code, dispatched, keys, _ in trips:
        if not dispatched:
            print(f"  {shipment}: confirmed, NOT dispatched - not in the portal yet")
            continue
        vehicle = by_code(vehicles, vehicle_code)
        driver = by_code(drivers, driver_code)
        reference = f"SLS-DEMO-{RUN}-{shipment}"
        call("POST", "/integration/v1/dispatch-confirmations", ewm, body={
            "sourceSystem": "EWM_EBIM", "dispatchReference": reference, "revision": 1,
            "transportReference": shipment, "loadReference": "CRG-" + reference,
            "warehouseCode": profile["warehouse"],
            "actualDispatchAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "carrier": {"code": vehicle["carrierCode"], "name": vehicle["carrierBusinessName"]},
            "vehicle": {"licensePlate": vehicle["licensePlate"], "type": "FURGON"},
            "driver": {"name": driver["fullName"], "documentNumber": driver["documentNumber"]},
            "orders": [{"externalSource": "SAPB1_PE", "externalReference": references[key], "status": "SHIPPED",
                        "lines": [{"lineNumber": 1, "materialCode": "SKU-100", "quantity": 100, "uom": "UN"}]}
                       for key in keys],
            "document": {"format": "SLS", "version": "LOGFIRE-1", "contentType": "text/plain",
                         "content": "[H1]|" + profile["warehouse"] + "|" + shipment}}, expect=(200, 201))
        print(f"  {shipment}: dispatched by the warehouse ({vehicle['licensePlate']})")

        # 5. A GPS feed reports the truck on its way to the first stop: four points over 30 minutes.
        start = coordinates[profile["origin"]]
        end = coordinates[destinations[keys[0]]]
        now = dt.datetime.now(dt.timezone.utc)
        positions = []
        for step in range(1, 5):
            share = step / 5
            positions.append({
                "shipmentNumber": shipment,
                "occurredAt": (now - dt.timedelta(minutes=10 * (4 - step))).isoformat(),
                "latitude": round(start[0] + (end[0] - start[0]) * share, 6),
                "longitude": round(start[1] + (end[1] - start[1]) * share, 6),
                "speedKph": 32, "headingDegrees": 0, "externalVehicleReference": vehicle["licensePlate"]})
        call("POST", "/integration/v1/tracking/positions", gps, body={"provider": "demo-gps",
                                                                     "positions": positions},
             expect=(200, 201, 202))

    print("\nDone. " + "\n".join(profile["done"]))


def selected_companies():
    parser = argparse.ArgumentParser(description="Builds carrier portal demo trips (disposable environments only).")
    parser.add_argument("--company", default=COMPANY_CODE,
                        help="company code: " + ", ".join(PROFILES) + " or ALL (default: TMS_COMPANY_CODE or DEMO-LIMA)")
    parser.add_argument("--all", action="store_true", help="populate every company in PROFILES")
    arguments = parser.parse_args()
    code = "ALL" if arguments.all else arguments.company.strip().upper()
    if code == "ALL":
        return list(PROFILES)
    if code not in PROFILES:
        sys.exit(f"No demo profile for {code}. Known: {', '.join(PROFILES)} (or ALL).")
    return [code]


def main():
    companies = selected_companies()
    token = admin_token()
    me = call("GET", "/api/v1/me", token)
    for index, company_code in enumerate(companies):
        if index:
            print()
        populate(token, me, company_code)


if __name__ == "__main__":
    try:
        main()
    except ApiError as failure:
        sys.exit(str(failure))
