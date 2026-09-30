"""Business building blocks for the scenarios: one function per real API step on either side."""
import datetime as dt
import json
import time
import uuid

from common import (EWM_URL, expect, http, log, state, tms, tms_integration, tms_sql, ewm_sql, wait_until)

LIMA = dt.timezone(dt.timedelta(hours=-5))
WH = "33333333-3333-4333-8333-333333333301"


def today():
    return dt.datetime.now(LIMA).date().isoformat()


def now_lima():
    return dt.datetime.now(LIMA).replace(microsecond=0)


def run_id():
    return time.strftime("%H%M%S")


# ------------------------------------------------------------------------------------------ TMS

def tms_order(ref, qty=100, mark_ready=True):
    body = {"externalSource": "SAPB1_PE", "externalReference": ref, "originCode": "CD-LIMA",
            "destinationCode": "SODIMAC-ATOCONGO", "customerName": "Sodimac Peru", "customerReference": "OC-" + ref,
            "serviceDate": today(), "priority": "NORMAL",
            "lines": [{"materialCode": "SKU-100", "materialDescription": "Cemento 42.5kg", "quantity": qty,
                       "uom": "UN", "unitWeightKg": 10, "unitVolumeM3": 0.01, "palletQuantity": qty // 10}],
            "markReadyForPlanning": mark_ready}
    r = expect(tms_integration("POST", "/integration/v1/orders", body,
                               {"Idempotency-Key": "e2e-order-" + ref}), 201, 200, what="TMS order " + ref)
    return r.json


def tms_order_view(order_id):
    return expect(tms("GET", f"/api/v1/orders/{order_id}"), 200, what="order view").json


def free_vehicle_idx():
    st = state()
    busy = {r[0] for r in tms_sql("""SELECT vehicle_id FROM tms.trip
                                      WHERE status IN ('DRAFT','CONFIRMED','READY_FOR_DISPATCH','IN_TRANSIT')""")}
    for i in range(64):
        vid = st.get(f"TMS_VEHICLE_{i}")
        if vid is None:
            break
        if vid not in busy:
            return i
    raise AssertionError("no free vehicle left: reset the environment (run.sh recreates it)")


def tms_plan(assignments, vehicle_idx=None):
    """assignments: list of (orderId, partial-dict-or-None). Returns (tripId, shipmentNumber, runId)."""
    st = state()
    if vehicle_idx is None:
        vehicle_idx = free_vehicle_idx()
    for rid, ver in tms_sql(f"""SELECT id, version FROM tms.planning_run
                                  WHERE origin_id='{st['TMS_ORIGIN_ID']}' AND status='DRAFT'"""):
        tms("POST", f"/api/v1/planning/runs/{rid}/cancel", {"version": int(ver), "reason": "e2e cleanup"})
    run = expect(tms("POST", "/api/v1/planning/runs", {"originId": st["TMS_ORIGIN_ID"], "planningDate": today(),
                                                       "notes": "e2e"}), 201, what="planning run").json["run"]
    n = now_lima()
    departure = min(n + dt.timedelta(minutes=30), n.replace(hour=23, minute=59, second=0)).isoformat()
    trip = expect(tms("POST", f"/api/v1/planning/runs/{run['id']}/trips",
                      {"vehicleId": st[f"TMS_VEHICLE_{vehicle_idx}"], "plannedDepartureAt": departure,
                       "version": run["version"]}), 201, what="trip").json["trip"]
    for order_id, partial in assignments:
        body = {"orderId": order_id}
        if partial:
            body.update(partial)
        expect(tms("POST", f"/api/v1/planning/trips/{trip['id']}/assignments", body), 200, what="assignment")
    run_now = expect(tms("GET", f"/api/v1/planning/runs/{run['id']}"), 200, what="run").json["run"]
    expect(tms("POST", f"/api/v1/planning/runs/{run['id']}/confirm", {"version": run_now["version"]}), 200,
           what="confirm run")
    t = tms_trip(trip["id"])
    return trip["id"], t["shipmentNumber"], run["id"]


def tms_trip(trip_id):
    return expect(tms("GET", f"/api/v1/planning/trips/{trip_id}"), 200, what="trip").json["trip"]


def tms_trip_action(trip_id, action, **extra):
    t = tms_trip(trip_id)
    body = {"version": t["version"]}
    body.update(extra)
    return tms("POST", f"/api/v1/planning/trips/{trip_id}/{action}", body)


def tms_events(trip_id):
    return expect(tms("GET", f"/api/v1/planning/trips/{trip_id}/events"), 200, what="events").json


def tms_warehouse(trip_id):
    return expect(tms("GET", f"/api/v1/planning/trips/{trip_id}/warehouse"), 200, what="warehouse").json


def tms_control_tower(date=None):
    return expect(tms("GET", f"/api/v1/monitoring/control-tower?date={date or today()}"), 200,
                  what="control tower").json


def tms_set_mode(mode):
    cur = expect(tms("GET", "/api/v1/admin/companies/current"), 200).json
    s = cur["settings"]
    body = {"name": cur["name"], "timeZone": cur["timeZone"], "defaultCountry": s["defaultCountry"],
            "orderNumberPrefix": s["orderNumberPrefix"], "shipmentNumberPrefix": s["shipmentNumberPrefix"],
            "dispatchConfirmationMode": mode}
    r = expect(tms("PUT", "/api/v1/admin/companies/current", body), 200, what="company mode")
    assert r.json["settings"]["dispatchConfirmationMode"] == mode
    return mode


def tms_deliveries_for(shipment_number):
    rows = tms_sql(f"""SELECT d.id, d.event_type, d.status, d.attempt_count, d.last_status_code,
                              coalesce(d.last_error,'')
                         FROM tms.webhook_delivery d
                        WHERE d.payload::text LIKE '%{shipment_number}%' ORDER BY d.created_at""")
    return rows


def trip_row(trip_id):
    return tms_sql(f"""SELECT status, coalesce(dispatch_source,''), coalesce(actual_departure_at::text,''),
                              coalesce(dispatched_by_client::text,''), coalesce(dispatched_by::text,'')
                         FROM tms.trip WHERE id='{trip_id}'""")[0]


def external_dispatch_rows(shipment_number):
    return tms_sql(f"""SELECT dispatch_reference, revision, outcome, verification_status,
                              (superseded_at IS NULL) AS current, actual_dispatch_at, received_at
                         FROM tms.external_dispatch WHERE transport_reference='{shipment_number}'
                        ORDER BY received_at""")


# ------------------------------------------------------------------------------------------ EWM

def ewm(method, path, body=None, headers=None):
    return http(method, EWM_URL + path, body, headers)


def ewm_seed_stock(qty, wh_key=""):
    st = state()
    loc = st[f"EWM_LOC{wh_key}_RACK_A01"]
    wh = WH if not wh_key else "33333333-3333-4333-8333-333333333302"
    expect(ewm("POST", "/api/v1/inventory/movements", {
        "movementTypeId": st["EWM_MOVTYPE_SEED"],
        "lines": [{"warehouseId": wh, "locationId": loc, "inventoryTypeId": st["EWM_INVTYPE_0001"],
                   "materialId": st["EWM_ITEM_SKU100"], "quantity": str(qty)}]}), 201, what="seed stock")


def ewm_order(ref, qty=100, wh=WH, wh_key=""):
    st = state()
    ewm_seed_stock(qty, wh_key)
    r = expect(ewm("POST", "/api/v1/outbound-orders", {
        "externalOrderNumber": ref, "sourceSystem": "SAPB1_PE",
        "header": {"warehouseId": wh, "documentTypeId": st["EWM_DOCTYPE_STD"], "orderType": "STANDARD",
                   "orderDate": today(), "destinationId": st["EWM_STORE"]},
        "lines": [{"lineNumber": 1, "materialId": st["EWM_ITEM_SKU100"], "requestedQuantity": str(qty)}]}),
        201, what="EWM order " + ref)
    oid = r.json["id"]
    o = expect(ewm("GET", f"/api/v1/outbound-orders/{oid}"), 200).json
    expect(ewm("PATCH", f"/api/v1/outbound-orders/{oid}/status", {"status": "OPEN", "version": o["version"]}), 200,
           what="EWM order OPEN")
    expect(ewm("POST", f"/api/v1/outbound-orders/{oid}/reserve", {"policy": "STRICT_FULL"}), 200, what="reserve")
    expect(ewm("POST", f"/api/v1/outbound-orders/{oid}/allocate", {}), 200, what="allocate")
    return oid


def ewm_wait_load(shipment_number, timeout=90):
    def find():
        rows = ewm_sql(f"""SELECT id, load_number, status FROM out_shipment_load
                            WHERE external_load_number='{shipment_number}' AND status<>'CANCELLED'""")
        return rows[0] if rows else None
    return wait_until(find, timeout, 2, "EWM load for " + shipment_number)


def ewm_pick(order_id, qty, tote, wh=WH, wh_key=""):
    st = state()
    wave = expect(ewm("POST", "/api/v1/waves", {"warehouseId": wh, "orderIds": [order_id]}), 201,
                  what="wave").json["wave"]["id"]
    expect(ewm("POST", f"/api/v1/waves/{wave}/release", {}), 200, what="wave release")
    tasks = expect(ewm("GET", f"/api/v1/waves/{wave}/pick-tasks"), 200, what="pick tasks").json
    task_list = tasks if isinstance(tasks, list) else tasks.get("content") or tasks.get("tasks")
    remaining = qty
    for t in task_list:
        tid = t["id"]
        expect(ewm("POST", f"/api/v1/rf/picking/tasks/{tid}/assign", {}), 200, what="task assign")
        detail = expect(ewm("GET", f"/api/v1/rf/picking/tasks/{tid}"), 200).json
        lines = detail.get("lines") or detail.get("task", {}).get("lines")
        for ln in lines:
            pend = float(ln["pendingQuantity"])
            take = min(pend, remaining)
            if take <= 0:
                continue
            expect(ewm("POST", f"/api/v1/rf/picking/tasks/{tid}/confirm", {
                "lineId": ln["id"], "quantity": str(int(take)), "destinationLocationId": st[f"EWM_LOC{wh_key}_STG_01"],
                "pickContainerCode": tote, "pickContainerTypeId": st["EWM_LU_TOTE"]}), 200, what="pick confirm")
            remaining -= take
    return wave


def ewm_pack(order_id, qty, box, wh=WH, wh_key=""):
    st = state()
    sid = expect(ewm("POST", "/api/v1/packing/sessions", {
        "outboundOrderId": order_id, "warehouseId": wh, "packingStationLocationId": st[f"EWM_LOC{wh_key}_PACK_01"]}),
        201, what="packing session").json["session"]["id"]
    pkg = expect(ewm("POST", f"/api/v1/packing/sessions/{sid}/containers",
                     {"code": box, "packageTypeId": st["EWM_LU_CJ"]}), 201, what="container").json["id"]
    expect(ewm("POST", f"/api/v1/packing/sessions/{sid}/pack", {"packageId": pkg, "quantity": str(qty)}), 200,
           what="pack")
    expect(ewm("POST", f"/api/v1/packing/containers/{pkg}/close", {"markReady": True}), 200, what="close box")
    return box


def ewm_load_units(load_id, boxes):
    for b in boxes:
        expect(ewm("POST", f"/api/v1/shipment-loads/{load_id}/units", {"packageCode": b, "markLoaded": True}), 201,
               what="load unit")


def ewm_ready(load_id):
    expect(ewm("POST", f"/api/v1/shipment-loads/{load_id}/ready", {}), 200, what="load ready")


def ewm_ship(load_id, seal="PRE-00991", bol="T001-000123", shipped_at=None):
    body = {"sealNumber": seal, "bolNumber": bol}
    if shipped_at:
        body["shippedAt"] = shipped_at
    return expect(ewm("POST", f"/api/v1/shipment-loads/{load_id}/ship", body), 200, what="ship").json


def ewm_outbound_messages(interface, load_or_ref=None):
    return ewm_sql(f"""SELECT m.id, m.status, coalesce(m.document_reference,''), m.revision, m.created_at
                         FROM intg_outbound_message m WHERE m.interface_type='{interface}'
                        ORDER BY m.created_at""")


def ewm_tms_disp_for(sls_or_shipment):
    """TMS_DISP deliveries whose payload mentions the reference: (msg_id, delivery_id, status, attempts, code)."""
    return ewm_sql(f"""SELECT m.id, d.id, d.status, d.attempts, coalesce(d.last_http_status::text,''),
                              coalesce(d.last_error_code,''), coalesce(d.delivery_reference,''), m.revision
                         FROM intg_outbound_message m JOIN intg_outbound_delivery d ON d.message_id=m.id
                        WHERE m.interface_type='TMS_DISP' AND m.payload::text LIKE '%{sls_or_shipment}%'
                        ORDER BY m.created_at, d.created_at""")
