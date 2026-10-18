#!/usr/bin/env python3
"""The TMS <-> EWM scenarios (s1-s9 v1; s10 material profile, s11 POD and s12 order cancellation, v1.1), each driven through the real HTTP APIs of both products.

    scenarios.py [s1 s2 ...]     # default: all, in order

Scenarios 3 and 4 stop and restart a product; they call back into env.sh for that.
Each scenario prints its evidence and asserts the expected outcome; a failed assertion is reported
and the run continues with the next scenario. Evidence is written to $E2E_WORK/evidence-*.json.
"""
import base64
import datetime as dt
import json
import os
import subprocess
import sys
import threading
import time
import traceback
import uuid

from common import (EWM_URL, STATE_FILE, TMS_URL, dump_evidence, expect, http, log, record, save_env, state,
                    tms, tms_integration, tms_sql, ewm_sql, wait_until)
from flows import (LIMA, WH, ewm, ewm_load_units, ewm_order, ewm_pack, ewm_pick, ewm_ready, ewm_ship,
                   ewm_tms_disp_for, ewm_wait_load, external_dispatch_rows, now_lima, run_id, tms_control_tower,
                   tms_deliveries_for, tms_events, tms_order, tms_order_view, tms_plan, tms_set_mode, tms_trip,
                   tms_trip_action, tms_warehouse, today, trip_row)

E2E_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = {}
UOM_UN = "a0000000-0000-4000-8000-000000000001"   # EWM local seed: UN, no decimals (a countable unit)


def sh(fn):
    """Run a function of env.sh (start_tms, stop_pid ewm, ...)."""
    subprocess.run(["bash", "-c", f"source '{E2E_DIR}/env.sh' && {fn}"], check=True)


def event_types(trip_id):
    return [e["eventType"] for e in tms_events(trip_id)]


def advisories_for(shipment_number, trip_id):
    date = tms_trip(trip_id).get("planningDate") or today()
    return [a for a in tms_control_tower(date)["advisories"] if a.get("shipmentNumber") == shipment_number]


def wait_disp(ref, statuses=("SENT",), timeout=90):
    def f():
        rows = ewm_tms_disp_for(ref)
        return rows if rows and rows[-1][2] in statuses else None
    return wait_until(f, timeout, 2, f"TMS_DISP for {ref} in {statuses}")


def wait_external(shipment_number, n=1, timeout=90):
    return wait_until(lambda: (lambda r: r if len(r) >= n else None)(external_dispatch_rows(shipment_number)),
                      timeout, 2, f"{n} external_dispatch row(s) for {shipment_number}")


def prepare(tag, mode, qty=100, partial=None):
    """ERP order in both products, planned and confirmed in TMS, load created in EWM from the plan."""
    tms_set_mode(mode)
    ref = f"PED-{tag}-{run_id()}"
    o = tms_order(ref, qty)
    eo = ewm_order(ref, qty)
    trip, shn, _ = tms_plan([(o["id"], partial)])
    record(tag, "orders", {"erpKey": ["SAPB1_PE", ref], "tmsOrder": o["orderNumber"], "tmsStatus": o["status"],
                           "ewmOrderId": eo, "trip": trip, "shipmentNumber": shn, "mode": mode})
    return ref, o, eo, trip, shn


def physical(tag, eo, shn, qty, ship=True, shipped_at=None):
    load = ewm_wait_load(shn)
    ewm_pick(eo, qty, f"TOTE-{tag}-{run_id()}")
    box = ewm_pack(eo, qty, f"OBL-{tag}-{run_id()}")
    ewm_load_units(load[0], [box])
    ewm_ready(load[0])
    record(tag, "ewmLoad", {"id": load[0], "loadNumber": load[1], "externalLoadNumber": shn})
    if not ship:
        return load, None
    s = ewm_ship(load[0], shipped_at=shipped_at)["shipment"]
    record(tag, "ewmShipped", {"sls": s["shipmentNumber"], "shippedAt": s["shippedAt"], "load": s["loadNumber"]})
    return load, s


def post_raw_dispatch(payload_text, key):
    return tms_integration("POST", "/integration/v1/dispatch-confirmations", raw=payload_text.encode(),
                           headers={"Idempotency-Key": key, "Content-Type": "application/json"})


def disp_payload(sls):
    """(message id, exact JSON EWM sends, the Idempotency-Key header EWM uses for it)."""
    rows = ewm_sql(f"""SELECT m.id, translate(encode(convert_to(m.payload, 'UTF8'), 'base64'), E'\\n', ''),
                              d.idempotency_key
                         FROM intg_outbound_message m JOIN intg_outbound_delivery d ON d.message_id = m.id
                        WHERE m.interface_type='TMS_DISP' AND m.document_reference='{sls}'
                        ORDER BY m.revision DESC LIMIT 1""")
    msg_id, b64, key = rows[0][0], rows[0][1], "|".join(rows[0][2:])   # a DEFAULT delivery's key has '|'
    return msg_id, base64.b64decode(b64).decode("utf-8"), key


# ----------------------------------------------------------------------------------------- 1
def s1():
    tag = "S1"
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED")
    record(tag, "tmsWebhookDeliveries", wait_until(
        lambda: (lambda r: r if r and r[0][2] == "PROCESSED" else None)(tms_deliveries_for(shn)), 60, 2, "webhook"))
    record(tag, "ewmWebhookEvent", ewm_sql(f"""SELECT contract_type, status, outcome FROM intg_tms_webhook_event
                                               WHERE shipment_number='{shn}'"""))
    load, s = physical(tag, eo, shn, 97)
    disp = wait_disp(s["shipmentNumber"])
    record(tag, "ewmTmsDispDelivery", disp)
    wait_external(shn)
    w = tms_warehouse(trip)
    doc = w["documents"][0]
    tr = trip_row(trip)
    record(tag, "tmsTrip", {"status": tr[0], "dispatchSource": tr[1], "actualDepartureAt": tr[2],
                            "dispatchedByClient": tr[3]})
    record(tag, "tmsWarehouseView", {"mode": w["dispatchConfirmationMode"], "dispatchSource": w["dispatchSource"],
                                     "verification": w["verificationStatus"], "outcome": doc["outcome"],
                                     "discrepancies": doc["discrepancies"], "orders": doc["orders"],
                                     "milestones": w["milestones"]})
    ev = tms_events(trip)
    record(tag, "tmsTimeline", [(e["eventType"], e["eventTime"], e["source"]) for e in ev])
    adv = advisories_for(shn, trip)
    record(tag, "controlTowerAdvisories", adv)
    record(tag, "orderStates", {"tms": tms_sql(f"SELECT status FROM tms.transport_order WHERE id='{o['id']}'")[0][0],
                                "ewm": ewm_sql(f"SELECT status FROM out_order WHERE id='{eo}'")[0][0]})
    types = [e["eventType"] for e in ev]
    assert tr[0] == "IN_TRANSIT" and tr[1] == "INTEGRATION"
    assert doc["outcome"] == "APPLIED" and w["verificationStatus"] == "MISMATCH"
    qv = [d for d in doc["discrepancies"] if d["code"] == "QUANTITY_VARIANCE"]
    assert qv and float(qv[0]["planned"]) == 100 and float(qv[0]["dispatched"]) == 97
    assert "WAREHOUSE_LOADING_STARTED" in types and "WAREHOUSE_LOAD_READY" in types
    assert types.count("TRIP_DISPATCHED") == 1
    assert any(a["type"] == "DISPATCH_MISMATCH" for a in adv)
    save_env(STATE_FILE, S1_SLS=s["shipmentNumber"], S1_TRIP=trip, S1_SH=shn)


# ----------------------------------------------------------------------------------------- 2
def s2():
    tag = "S2"
    ref, o, eo, trip, shn = prepare(tag, "HYBRID")
    ewm_wait_load(shn)
    r1 = expect(tms_trip_action(trip, "ready"), 200, what="ready")
    r2 = expect(tms_trip_action(trip, "dispatch"), 200, what="manual dispatch")
    record(tag, "tmsManualDispatch", {"ready": r1.status, "dispatch": r2.status, "trip": trip_row(trip)})
    load, s = physical(tag, eo, shn, 100)
    record(tag, "ewmTmsDispDelivery", wait_disp(s["shipmentNumber"]))
    wait_external(shn)
    w = tms_warehouse(trip)
    doc = w["documents"][0]
    types = event_types(trip)
    tr = trip_row(trip)
    record(tag, "result", {"outcome": doc["outcome"], "verification": doc["verificationStatus"],
                           "discrepancies": doc["discrepancies"], "dispatchSource": tr[1],
                           "tripDispatchedEvents": types.count("TRIP_DISPATCHED"), "timeline": types})
    assert doc["outcome"] == "RECONCILED"
    assert tr[1] == "OPERATOR" and types.count("TRIP_DISPATCHED") == 1


# ----------------------------------------------------------------------------------------- 3
def s3():
    tag = "S3"
    tms_set_mode("HYBRID")
    ref = f"PED-{tag}-{run_id()}"
    o = tms_order(ref)
    eo = ewm_order(ref)
    log("  stopping EWM")
    sh("stop_pid ewm")
    trip, shn, _ = tms_plan([(o["id"], None)])
    record(tag, "planned", {"trip": trip, "shipmentNumber": shn, "ewm": "DOWN"})

    def failing_attempts():
        rows = tms_sql(f"""SELECT a.attempt_number, a.outcome, coalesce(a.status_code::text,''),
                                  left(coalesce(a.error,''),80)
                             FROM tms.webhook_delivery_attempt a JOIN tms.webhook_delivery d
                               ON d.id=a.webhook_delivery_id
                            WHERE d.payload::text LIKE '%{shn}%' ORDER BY a.attempt_number""")
        return rows if len(rows) >= 2 else None
    attempts = wait_until(failing_attempts, 120, 3, "two failed webhook attempts")
    record(tag, "tmsWebhookAttemptsWhileEwmDown", attempts)
    record(tag, "tmsWebhookDelivery", tms_deliveries_for(shn))
    expect(tms_trip_action(trip, "ready"), 200, what="ready")
    d = expect(tms_trip_action(trip, "dispatch"), 200, what="manual dispatch with EWM down")
    record(tag, "tmsManualDispatchWhileEwmDown", {"status": d.status, "trip": trip_row(trip)})
    log("  restarting EWM")
    sh("start_ewm")
    delivered = wait_until(lambda: (lambda r: r if r and r[0][2] == "PROCESSED" else None)(tms_deliveries_for(shn)),
                           200, 3, "webhook redelivery after EWM restart")
    record(tag, "tmsWebhookRedelivered", delivered)
    ev = wait_until(lambda: (lambda r: r if r and r[0][1] in ("PROCESSED", "IGNORED", "FAILED") else None)(
        ewm_sql(f"SELECT contract_type, status, coalesce(outcome,''), coalesce(load_id::text,'') "
                f"FROM intg_tms_webhook_event WHERE shipment_number='{shn}'")), 60, 2, "EWM processed event")
    record(tag, "ewmWebhookEventAfterRecovery", ev)
    loads = ewm_sql(f"SELECT id, load_number, status FROM out_shipment_load WHERE external_load_number='{shn}'")
    if not loads:
        sync = http("POST", EWM_URL + "/api/v1/admin/integrations/tms/sync", {"shipmentNumber": shn})
        record(tag, "ewmSyncAfterRecovery", {"status": sync.status, "body": sync.json})
        loads = ewm_sql(f"SELECT id, load_number, status FROM out_shipment_load WHERE external_load_number='{shn}'")
    record(tag, "ewmLoadAfterRecovery", loads)
    assert loads, "EWM has no load for a trip TMS dispatched during the outage: its SLS can never reach TMS"
    load, s = physical(tag, eo, shn, 100)
    record(tag, "ewmTmsDispDelivery", wait_disp(s["shipmentNumber"]))
    wait_external(shn)
    doc = tms_warehouse(trip)["documents"][0]
    types = event_types(trip)
    record(tag, "result", {"outcome": doc["outcome"], "verification": doc["verificationStatus"],
                           "discrepancies": doc["discrepancies"], "tripDispatched": types.count("TRIP_DISPATCHED"),
                           "trip": trip_row(trip)})
    assert doc["outcome"] == "RECONCILED" and types.count("TRIP_DISPATCHED") == 1


# ----------------------------------------------------------------------------------------- 4
def s4():
    tag = "S4"
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED")
    load, _ = physical(tag, eo, shn, 100, ship=False)
    time.sleep(8)  # let the two milestones reach TMS before it goes down
    log("  stopping TMS")
    sh("stop_pid tms")
    s = ewm_ship(load[0])["shipment"]
    record(tag, "ewmShippedWhileTmsDown", {"sls": s["shipmentNumber"], "shippedAt": s["shippedAt"]})
    dead = wait_disp(s["shipmentNumber"], ("DEAD_LETTER",), timeout=300)
    record(tag, "ewmDeliveryDeadLettered", dead)
    time.sleep(5)
    log("  restarting TMS")
    sh("start_tms")
    msg_id = dead[-1][0]
    r = http("POST", EWM_URL + f"/api/v1/admin/integrations/outbound/messages/{msg_id}/retry", {})
    record(tag, "ewmMonitorRetry", {"status": r.status, "body": (r.json if r.json else r.raw[:300])})
    sent = wait_disp(s["shipmentNumber"], ("SENT",), timeout=60)
    record(tag, "ewmDeliveryAfterResend", sent)
    wait_external(shn)
    tr = trip_row(trip)
    doc = tms_warehouse(trip)["documents"][0]
    record(tag, "result", {"outcome": doc["outcome"], "verification": doc["verificationStatus"],
                           "discrepancies": doc["discrepancies"], "actualDispatchAt(doc)": doc["actualDispatchAt"],
                           "trip.actual_departure_at": tr[2], "dispatchSource": tr[1], "receivedAt": doc["receivedAt"],
                           "ewm.shippedAt": s["shippedAt"]})
    assert doc["outcome"] == "APPLIED" and tr[0] == "IN_TRANSIT"
    shipped = dt.datetime.fromisoformat(s["shippedAt"].replace("Z", "+00:00"))
    departed = dt.datetime.fromisoformat(tr[2].replace(" ", "T") + (":00" if tr[2][-3] in "+-" else ""))
    received = dt.datetime.fromisoformat(doc["receivedAt"].replace("Z", "+00:00"))
    assert abs((departed - shipped).total_seconds()) < 1.5, (departed, shipped)
    assert (received - departed).total_seconds() > 60, "departure should be the historical SLS time"

    # 4b: a dispatch time earlier than the trip's confirmation (EWM clock/back-dating) -> UNAPPLIED + NOT_APPLIED
    ref, o, eo, trip, shn = prepare(tag + "b", "EXTERNAL_REQUIRED")
    confirmed_at = tms_sql(f"SELECT confirmed_at FROM tms.trip WHERE id='{trip}'")[0][0]
    early = (dt.datetime.fromisoformat(confirmed_at.replace(" ", "T") + (":00" if confirmed_at[-3] in "+-" else ""))
             - dt.timedelta(minutes=10)).astimezone(LIMA).isoformat()
    load, s = physical(tag + "b", eo, shn, 100, shipped_at=early)
    wait_disp(s["shipmentNumber"])
    wait_external(shn)
    doc = tms_warehouse(trip)["documents"][0]
    advs = advisories_for(shn, trip)
    record(tag + "b", "result", {"shippedAt": s["shippedAt"], "tripConfirmedAt": confirmed_at,
                                 "outcome": doc["outcome"], "verification": doc["verificationStatus"],
                                 "discrepancies": doc["discrepancies"], "trip": trip_row(trip), "advisories": advs})
    assert doc["outcome"] == "UNAPPLIED" and any(d["code"] == "NOT_APPLIED" for d in doc["discrepancies"])
    assert doc["verificationStatus"] == "MISMATCH" and any(a["type"] == "DISPATCH_MISMATCH" for a in advs)
    assert trip_row(trip)[0] == "CONFIRMED"


# ----------------------------------------------------------------------------------------- 5
def s5():
    tag = "S5"
    st = state()
    sls, trip, shn = st["S1_SLS"], st["S1_TRIP"], st["S1_SH"]
    msg_id, payload, key = disp_payload(sls)
    before = event_types(trip).count("TRIP_DISPATCHED")
    same_key = post_raw_dispatch(payload, key)
    new_key = post_raw_dispatch(payload, "e2e-dup-" + uuid.uuid4().hex[:12])
    # EWM monitor: a SENT delivery cannot be resent (409); the message-level retry has nothing to resend
    d = ewm_tms_disp_for(sls)[-1]
    mon = http("POST", EWM_URL + f"/api/v1/admin/integrations/outbound/messages/{msg_id}/deliveries/{d[1]}/retry", {})
    after = event_types(trip).count("TRIP_DISPATCHED")
    rows = external_dispatch_rows(shn)
    record(tag, "result", {"sameKey": [same_key.status, same_key.json], "newKey": [new_key.status, new_key.json],
                           "ewmMonitorResendOfSent": [mon.status, (mon.json or {}).get("code")],
                           "tripDispatchedBefore": before, "tripDispatchedAfter": after,
                           "externalDispatchRows": rows})
    assert new_key.status == 200 and new_key.json["outcome"] == "UNCHANGED"
    assert same_key.status in (200, 201)
    assert after == before == 1 and len(rows) == 1


# ----------------------------------------------------------------------------------------- 6
def s6():
    tag = "S6"
    ref, o, eo, trip, shn = prepare(tag, "HYBRID")
    load, _ = physical(tag, eo, shn, 100, ship=False)
    dest = [x for x in http("GET", EWM_URL + "/api/v1/admin/integrations/outbound/destinations").json["content"]
            if x["code"] == "TMS_DISPATCH"][0]
    # Hold EWM's own delivery so the race is fired at a moment we choose, with EWM's exact bytes and key.
    expect(http("PATCH", EWM_URL + f"/api/v1/admin/integrations/outbound/destinations/{dest['id']}/active",
                {"active": False, "version": dest.get("version")}), 200, what="pause TMS_DISPATCH")
    expect(tms_trip_action(trip, "ready"), 200, what="ready")   # before the truck leaves, as on the floor
    try:
        s = ewm_ship(load[0])["shipment"]
        msg = wait_until(lambda: ewm_sql(f"""SELECT m.id, d.destination_code, d.status
                                               FROM intg_outbound_message m JOIN intg_outbound_delivery d
                                                 ON d.message_id = m.id
                                              WHERE m.interface_type='TMS_DISP'
                                                AND m.document_reference='{s['shipmentNumber']}'"""), 60, 1,
                         "TMS_DISP generated")
        record(tag, "ewmMessageWhileDestinationPaused", msg)
        _, payload, _ = disp_payload(s["shipmentNumber"])
        key = f"ewm-dispatch:{s['shipmentNumber']}:r1"   # the key EWM's TMS_DISPATCH delivery uses
        version = tms_trip(trip)["version"]
        barrier = threading.Barrier(2)
        out = {}

        def manual():
            barrier.wait()
            out["manual"] = tms("POST", f"/api/v1/planning/trips/{trip}/dispatch", {"version": version})

        def external():
            barrier.wait()
            out["external"] = post_raw_dispatch(payload, key)
        th = [threading.Thread(target=manual), threading.Thread(target=external)]
        [t.start() for t in th]
        [t.join() for t in th]
    finally:
        cur = [x for x in http("GET", EWM_URL + "/api/v1/admin/integrations/outbound/destinations").json["content"]
               if x["code"] == "TMS_DISPATCH"][0]
        http("PATCH", EWM_URL + f"/api/v1/admin/integrations/outbound/destinations/{cur['id']}/active",
             {"active": True, "version": cur.get("version")})
    # EWM's own copy: the message generated while paused is bound to the DEFAULT destination and does
    # not move on reactivation; the monitor's regenerate issues revision 2 to the TMS destinations.
    time.sleep(5)
    record(tag, "ewmMessageAfterReactivation", ewm_sql(f"""SELECT m.revision, m.status, d.destination_code, d.status
                                                             FROM intg_outbound_message m JOIN intg_outbound_delivery d
                                                               ON d.message_id = m.id
                                                            WHERE m.interface_type='TMS_DISP'
                                                              AND m.document_reference='{s['shipmentNumber']}'"""))
    regen = http("POST", EWM_URL + f"/api/v1/admin/integrations/outbound/messages/{msg[0][0]}/regenerate", {})
    record(tag, "ewmRegenerate", {"status": regen.status, "revision": (regen.json or {}).get("revision")})
    ewm_delivery = wait_disp(s["shipmentNumber"], ("SENT",), timeout=90)
    wait_external(shn, 2)
    types = event_types(trip)
    tr = trip_row(trip)
    doc = tms_warehouse(trip)["documents"][0]
    record(tag, "race", {"manual": [out["manual"].status, (out["manual"].json or {}).get("trip", {}).get("status")
                                    if out["manual"].json else out["manual"].raw[:200]],
                         "external": [out["external"].status, out["external"].json],
                         "ewmOwnDeliveryAfterwards": ewm_delivery,
                         "tripDispatchedEvents": types.count("TRIP_DISPATCHED"), "dispatchSource": tr[1],
                         "externalDispatchRows": external_dispatch_rows(shn)})
    assert types.count("TRIP_DISPATCHED") == 1 and tr[0] == "IN_TRANSIT"
    first = out["external"].json["outcome"]
    assert (tr[1] == "INTEGRATION") == (first == "APPLIED") and first in ("APPLIED", "RECONCILED")
    assert out["manual"].status in (200, 409)

    # Repeat the race on fresh trips with EWM's payload re-keyed for each (TMS-only, no warehouse cycle).
    template = json.loads(payload)
    rounds = []
    for i in range(4):
        ref2 = f"PED-{tag}R{i}-{run_id()}"
        o2 = tms_order(ref2)
        t2, sh2, _ = tms_plan([(o2["id"], None)])
        expect(tms_trip_action(t2, "ready"), 200)
        v2 = tms_trip(t2)["version"]
        body = dict(template, transportReference=sh2, dispatchReference=f"SLS-R{i}-{run_id()}",
                    actualDispatchAt=dt.datetime.now(LIMA).isoformat(), loadReference=None)
        body["orders"] = [dict(template["orders"][0], externalReference=ref2)]
        b2 = threading.Barrier(2)
        res = {}

        def m2():
            b2.wait()
            res["m"] = tms("POST", f"/api/v1/planning/trips/{t2}/dispatch", {"version": v2})

        def e2():
            b2.wait()
            res["e"] = tms_integration("POST", "/integration/v1/dispatch-confirmations", body,
                                       {"Idempotency-Key": "e2e-race-" + uuid.uuid4().hex[:12]})
        ths = [threading.Thread(target=m2), threading.Thread(target=e2)]
        [t.start() for t in ths]
        [t.join() for t in ths]
        rounds.append({"trip": sh2, "manual": res["m"].status, "external": [res["e"].status,
                       (res["e"].json or {}).get("outcome")], "dispatched": event_types(t2).count("TRIP_DISPATCHED"),
                       "source": trip_row(t2)[1]})
    record(tag, "extraRaces", rounds)
    assert all(r["dispatched"] == 1 for r in rounds)
    assert all(r["external"][1] in ("APPLIED", "RECONCILED") for r in rounds)
    assert all((r["source"] == "INTEGRATION") == (r["external"][1] == "APPLIED") for r in rounds)


# ----------------------------------------------------------------------------------------- 7
def s7():
    tag = "S7"
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED")
    load = ewm_wait_load(shn)
    ewm_pick(eo, 100, f"TOTE-{tag}-{run_id()}")
    box = ewm_pack(eo, 100, f"OBL-{tag}-{run_id()}")
    ewm_load_units(load[0], [box])  # load is now LOADING
    c = expect(tms_trip_action(trip, "cancel", reason="Cliente anulo el pedido (e2e)"), 200, what="cancel trip")
    record(tag, "tmsCancel", {"status": c.status, "trip": trip_row(trip)})
    ev = wait_until(lambda: ewm_sql(f"""SELECT contract_type, status, coalesce(outcome,'') FROM intg_tms_webhook_event
                                         WHERE shipment_number='{shn}' AND contract_type='TRANSPORT_PLAN_CANCELLED'
                                           AND status='PROCESSED'"""), 90, 2, "EWM processed cancellation")
    record(tag, "ewmCancelEvent", ev)
    record(tag, "ewmLoadAfterCancel", ewm_sql(f"SELECT load_number, status FROM out_shipment_load WHERE id='{load[0]}'"))
    record(tag, "ewmExceptions", ewm_sql(f"SELECT code, status FROM intg_tms_exception WHERE shipment_number='{shn}'"))
    ewm_ready(load[0])
    s = ewm_ship(load[0])["shipment"]
    record(tag, "ewmShippedAfterCancel", s["shipmentNumber"])
    record(tag, "ewmTmsDispDelivery", wait_disp(s["shipmentNumber"]))
    wait_external(shn)
    doc = tms_warehouse(trip)["documents"][0]
    advs = advisories_for(shn, trip)
    record(tag, "result", {"outcome": doc["outcome"], "verification": doc["verificationStatus"],
                           "discrepancies": [d["code"] for d in doc["discrepancies"]], "trip": trip_row(trip),
                           "advisories": advs})
    assert doc["outcome"] == "UNAPPLIED" and any(d["code"] == "TRIP_CANCELLED" for d in doc["discrepancies"])
    assert trip_row(trip)[0] == "CANCELLED" and advs
    assert ev[0][2] == "CANCEL_REQUIRES_INTERVENTION"

    # 7b: cancelled while the EWM load is still DRAFT -> EWM cancels the load and says LOAD_CANCELLED
    ref, o, eo, trip, shn = prepare(tag + "b", "EXTERNAL_REQUIRED")
    load = ewm_wait_load(shn)
    expect(tms_trip_action(trip, "cancel", reason="Replanificado (e2e)"), 200, what="cancel trip b")
    wait_until(lambda: ewm_sql(f"""SELECT 1 FROM intg_tms_webhook_event WHERE shipment_number='{shn}'
                                    AND contract_type='TRANSPORT_PLAN_CANCELLED' AND status='PROCESSED'"""), 90, 2,
               "EWM processed cancellation b")
    time.sleep(10)
    record(tag + "b", "result", {
        "ewmEvent": ewm_sql(f"SELECT contract_type, outcome FROM intg_tms_webhook_event WHERE shipment_number='{shn}'"),
        "ewmLoad": ewm_sql(f"SELECT load_number, status FROM out_shipment_load WHERE id='{load[0]}'"),
        "ewmPlanOrders": ewm_sql(f"SELECT status FROM out_shipment_load_plan_order WHERE load_id='{load[0]}'"),
        "tmsTimeline": event_types(trip)})
    assert ewm_sql(f"SELECT status FROM out_shipment_load WHERE id='{load[0]}'")[0][0] == "CANCELLED"
    assert "WAREHOUSE_LOAD_CANCELLED" in event_types(trip)


# ----------------------------------------------------------------------------------------- 8
def s8():
    tag = "S8"
    st = state()
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED")
    load, _ = physical(tag, eo, shn, 100, ship=False)
    # Master-data drift: the TMS origin is re-pointed at warehouse CD02 after the plan went to CD01.
    loc = expect(tms("GET", f"/api/v1/masterdata/locations/{st['TMS_ORIGIN_ID']}"), 200).json
    upd = {k: loc.get(k) for k in ("code", "name", "type", "roles", "country", "timeZone", "serviceTimeMinutes",
                                  "latitude", "longitude", "address", "district", "province", "department",
                                  "zoneId")}
    upd.update(externalSystem="EWM_EBIM", externalReference="CD02", version=loc.get("version"))
    r = tms("PUT", f"/api/v1/masterdata/locations/{st['TMS_ORIGIN_ID']}", upd)
    record(tag, "tmsOriginRepointed", {"status": r.status, "externalReference": (r.json or {}).get("externalReference"),
                                       "body": None if r.status == 200 else r.raw[:300]})
    try:
        expect(r, 200, what="origin update")
        s = ewm_ship(load[0])["shipment"]
        wait_disp(s["shipmentNumber"])
        wait_external(shn)
        doc = tms_warehouse(trip)["documents"][0]
        record(tag, "result", {"slsWarehouseCode": doc["warehouseCode"], "outcome": doc["outcome"],
                               "verification": doc["verificationStatus"], "discrepancies": doc["discrepancies"],
                               "advisories": advisories_for(shn, trip)})
        assert doc["verificationStatus"] == "MISMATCH"
        assert any(d["code"] == "WAREHOUSE_MISMATCH" for d in doc["discrepancies"])
    finally:
        loc = expect(tms("GET", f"/api/v1/masterdata/locations/{st['TMS_ORIGIN_ID']}"), 200).json
        upd.update(externalReference="CD01", version=loc.get("version"))
        expect(tms("PUT", f"/api/v1/masterdata/locations/{st['TMS_ORIGIN_ID']}", upd), 200, what="origin revert")


# ----------------------------------------------------------------------------------------- 9
def s9():
    tag = "S9"
    part = {"weightKg": 600, "volumeM3": 0.6, "pallets": 6}
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED", partial=part)
    view = tms_order_view(o["id"])
    record(tag, "tmsOrderAfterPlanning", {k: view.get(k) for k in view if "ending" in k or "llocat" in k or
                                          k in ("status", "version")})
    load, s = physical(tag, eo, shn, 60)
    wait_disp(s["shipmentNumber"])
    wait_external(shn)
    doc = tms_warehouse(trip)["documents"][0]
    view = tms_order_view(o["id"])
    st = state()
    elig = expect(tms("GET", f"/api/v1/planning/eligible-orders?originId={st['TMS_ORIGIN_ID']}&orderNumber="
                             f"{o['orderNumber']}"), 200).json
    items = elig.get("content", elig if isinstance(elig, list) else [])
    record(tag, "result", {"trip": trip_row(trip), "outcome": doc["outcome"], "verification": doc["verificationStatus"],
                           "discrepancies": doc["discrepancies"],
                           "order": {k: view.get(k) for k in view if "ending" in k or "llocat" in k or k == "status"},
                           "eligible": [{k: i.get(k) for k in i if "ending" in k or k in ("orderNumber",
                                                                                          "partiallyAllocated")}
                                        for i in items],
                           "ewmOrder": ewm_sql(f"SELECT status FROM out_order WHERE id='{eo}'")})
    assert trip_row(trip)[0] == "IN_TRANSIT" and doc["outcome"] == "APPLIED"
    assert view["status"] == "READY_FOR_PLANNING"
    assert items and float(items[0].get("pendingWeightKg", 0)) == 400

    # 9b: the remaining 40 go on a second trip -> second EWM load for the same ERP order.
    trip2, sh2, _ = tms_plan([(o["id"], None)])
    view = tms_order_view(o["id"])
    record(tag + "b", "tmsOrderAfterPlanningRemainder", {"status": view["status"], "pending": view["pendingWeightKg"],
                                                         "allocated": view["allocatedWeightKg"], "trip2": sh2})
    load2 = ewm_wait_load(sh2)
    tid, tote = ewm_sql(f"""SELECT DISTINCT t.id, u.code FROM out_pick_task t
                              JOIN out_pick_task_line l ON l.task_id = t.id
                              JOIN lu_unit u ON u.id = l.pick_container_id
                             WHERE l.outbound_order_id = '{eo}' AND l.pending_quantity > 0""")[0]
    task = expect(ewm("GET", f"/api/v1/rf/picking/tasks/{tid}"), 200).json
    for ln in task.get("lines") or task.get("task", {}).get("lines"):
        if float(ln["pendingQuantity"]) > 0:   # the rest of the same pick task, into the same tote
            expect(ewm("POST", f"/api/v1/rf/picking/tasks/{tid}/confirm", {
                "lineId": ln["id"], "quantity": "40", "destinationLocationId": state()["EWM_LOC_STG_01"],
                "pickContainerCode": tote, "pickContainerTypeId": state()["EWM_LU_TOTE"]}), 200, what="pick rest")
    box2 = ewm_pack(eo, 40, f"OBL-{tag}B-{run_id()}")
    ewm_load_units(load2[0], [box2])
    ewm_ready(load2[0])
    s2 = ewm_ship(load2[0])["shipment"]
    wait_disp(s2["shipmentNumber"])
    wait_external(sh2)
    doc2 = tms_warehouse(trip2)["documents"][0]
    view = tms_order_view(o["id"])
    record(tag + "b", "result", {"sls": s2["shipmentNumber"], "load": load2[1], "trip2": trip_row(trip2),
                                 "outcome": doc2["outcome"], "verification": doc2["verificationStatus"],
                                 "discrepancies": doc2["discrepancies"],
                                 "tmsOrder": {"status": view["status"], "pending": view["pendingWeightKg"]},
                                 "ewmOrder": ewm_sql(f"SELECT status FROM out_order WHERE id='{eo}'")})
    assert doc2["outcome"] == "APPLIED" and view["status"] == "IN_EXECUTION"
    save_env(STATE_FILE, S9_TMS_ORDER=o["id"], S9_EWM_ORDER=eo, S9_REF=ref)


# ----------------------------------------------------------------------------------------- 11
# ----------------------------------------------------------------------------------------- 10
def s10():
    """Material profile (v1.1 7.1, phases 20/25): EWM declares a nestable item, TMS_MATP carries it to
    /integration/v1/material-profiles/batch, an edit raises the TMS version, and a TMS order line that
    arrives without measures takes the profile as its snapshot. A material of its own, so the SKU-100
    the other scenarios load keeps no profile."""
    tag = "S10"
    code = f"NEST-{run_id()}"
    item = expect(ewm("POST", "/api/v1/items", {
        "code": code, "skuPartA": code, "description": "Balde anidable 20 L", "baseUomId": UOM_UN,
        "trackingType": "NONE", "expiryControl": False, "hazmat": False,
        "weight": "0.5", "weightUomCode": "KG", "volume": "0.0225", "volumeUomCode": "M3",
        "length": "30", "width": "30", "height": "25", "dimensionUomCode": "CM",
        "stackMaxQuantity": "50", "stackMode": "NESTABLE", "nestIncrement": "1.2",
        "maxTopLoad": "25", "maxTopLoadUomCode": "KG"}), 201, 200, what="EWM nestable item").json

    def tms_profile(min_version=0):
        rows = tms_sql(f"""SELECT stack_mode, height_m, nest_increment_m, stack_max_quantity, max_top_load_kg,
                                  source_system, version
                             FROM tms.material_profile WHERE material_code = '{code}'""")
        return rows if rows and int(rows[0][6]) >= min_version else None

    created = wait_until(tms_profile, 120, 3, f"TMS material profile {code}")
    assert created[0][0] == "NESTABLE" and float(created[0][1]) == 0.25 and float(created[0][2]) == 0.012, created
    assert int(created[0][3]) == 50 and float(created[0][4]) == 25 and created[0][5] == "EWM_EBIM", created

    # An edit in EWM travels as a new snapshot: same code, a higher TMS version.
    cur = expect(ewm("GET", f"/api/v1/items/{item['id']}"), 200, what="EWM item").json
    # ItemResponse nests skuParts, category and baseUom; the PUT takes them flat.
    update = {k: cur.get(k) for k in (
        "version", "externalCode", "description", "shortDescription", "weight", "weightUomCode", "volume",
        "volumeUomCode", "length", "width", "height", "dimensionUomCode", "trackingType", "expiryControl",
        "shelfLifeDays", "storageConditions", "hazmat", "brand", "manufacturerPartNumber", "stackMaxQuantity",
        "stackMode", "nestIncrement", "maxTopLoad", "maxTopLoadUomCode")}
    update.update({"skuPartA": code, "baseUomId": UOM_UN, "categoryId": None, "nestIncrement": "1.5"})
    expect(ewm("PUT", f"/api/v1/items/{item['id']}", update), 200, what="EWM item edit")
    edited = wait_until(lambda: (lambda r: r if r and float(r[0][2]) == 0.015 else None)(
        tms_profile(int(created[0][6]) + 1)), 120, 3, f"TMS profile {code} edited")

    # An ERP line without measures snapshots the profile it finds (D-02: the ERP block would win).
    ref = f"PED-{tag}-{run_id()}"
    order = expect(tms_integration("POST", "/integration/v1/orders", {
        "externalSource": "SAPB1_PE", "externalReference": ref, "originCode": "CD-LIMA",
        "destinationCode": "SODIMAC-ATOCONGO", "customerName": "Sodimac Peru", "serviceDate": today(),
        "priority": "NORMAL", "markReadyForPlanning": False,
        "lines": [{"materialCode": code, "materialDescription": "Balde anidable 20 L", "quantity": 100,
                   "uom": "UN", "unitWeightKg": 0.5}]},
        {"Idempotency-Key": "e2e-order-" + ref}), 201, 200, what="TMS order " + ref).json
    line = tms_sql(f"""SELECT l.physical_source, l.stack_mode, l.nest_increment_m, l.material_profile_version
                         FROM tms.transport_order_line l WHERE l.order_id = '{order['id']}'""")
    matp = ewm_sql(f"""SELECT m.interface_type, d.status FROM intg_outbound_message m
                         JOIN intg_outbound_delivery d ON d.message_id = m.id
                        WHERE m.interface_type = 'TMS_MATP' AND m.payload LIKE '%{code}%'""")
    record(tag, "result", {"material": code, "tmsProfileCreated": created, "tmsProfileEdited": edited,
                           "tmsOrderLine": line, "ewmMatp": matp})
    assert line[0][0] == "PROFILE" and line[0][1] == "NESTABLE" and float(line[0][2]) == 0.015, line
    assert int(line[0][3]) == int(edited[0][6]), line
    assert matp and all(r[1] == "SENT" for r in matp), matp


# ----------------------------------------------------------------------------------------- 11
def ensure_v11_link():
    """The warehouse link after v1.1 (WAREHOUSE_EXECUTION_V1 7.7): POD and order events subscribed."""
    st = state()
    sub = expect(tms("GET", f"/api/v1/webhooks/{st['TMS_SUBSCRIPTION_ID']}"), 200, what="subscription").json
    sub = sub.get("subscription", sub)
    wanted = ["SHIPMENT_CONFIRMED", "SHIPMENT_CHANGED", "SHIPMENT_CANCELLED", "DELIVERY_RESULT_RECORDED",
              "ORDER_CANCELLED"]
    if sorted(sub.get("eventTypes") or []) != sorted(wanted):
        expect(tms("PUT", f"/api/v1/webhooks/{sub['id']}", {
            "name": sub["name"], "description": sub.get("description"), "targetUrl": sub["targetUrl"],
            "eventTypes": wanted}), 200, what="subscription v1.1")


def s11():
    """POD back to the warehouse: a delivery recorded in TMS reaches EWM's projection, which posts nothing."""
    tag = "S11"
    ensure_v11_link()
    ref, o, eo, trip, shn = prepare(tag, "EXTERNAL_REQUIRED")
    load, s = physical(tag, eo, shn, 100)
    wait_disp(s["shipmentNumber"])
    wait_external(shn)
    detail = expect(tms("GET", f"/api/v1/planning/trips/{trip}"), 200, what="trip detail").json
    stop = detail["stops"][0]["id"]
    # Nothing is delivered at a stop nobody reached: the driver arrives and completes it first, and the
    # delivery keeps full precision (whole seconds would fall before the arrival of the same second).
    expect(tms("POST", f"/api/v1/planning/trips/{trip}/stops/{stop}/arrive", {}), 200, what="arrive at stop")
    expect(tms("POST", f"/api/v1/planning/trips/{trip}/stops/{stop}/complete", {}), 200, what="complete stop")
    expect(tms("PUT", f"/api/v1/planning/trips/{trip}/stops/{stop}/orders/{o['id']}/delivery", {
        "result": "DELIVERED", "deliveredAt": dt.datetime.now(LIMA).isoformat(), "receiverName": "Recepción Sodimac",
        "notes": "E2E s11"}), 200, what="record delivery")
    pod = wait_until(lambda: ewm_sql(f"""SELECT d.result, d.shipment_number, d.tms_order_number, d.receiver_name
                                           FROM out_shipment_delivery d
                                          WHERE d.shipment_number = '{shn}'""") or None,
                     120, 3, f"POD of {shn} projected in EWM")
    record(tag, "result", {"tmsWebhook": tms_deliveries_for(shn), "ewmDelivery": pod,
                           "ewmOrder": ewm_sql(f"SELECT status FROM out_order WHERE id='{eo}'")})
    assert pod[0][0] == "DELIVERED"


# ----------------------------------------------------------------------------------------- 12
def s12():
    """Order cancellation both ways (D-03): each side holds or alerts, neither cancels by itself."""
    tag = "S12"
    ensure_v11_link()

    # 12a: TMS -> EWM. An open TMS order is cancelled; EWM raises an alert on its order and cancels nothing.
    ref = f"PED-{tag}A-{run_id()}"
    o = tms_order(ref, 50)
    eo = ewm_order(ref, 50)
    expect(tms("POST", f"/api/v1/orders/{o['id']}/cancel?reason=E2E%20s12"), 200, what="TMS cancel")
    alert = wait_until(lambda: ewm_sql(f"""SELECT code, status FROM intg_tms_exception
                                             WHERE outbound_order_id = '{eo}'""") or None,
                       120, 3, "EWM alert TMS_TRANSPORT_CANCELLED")
    ewm_status = ewm_sql(f"SELECT status FROM out_order WHERE id='{eo}'")[0][0]
    record(tag + "a", "result", {"tmsOrder": o["orderNumber"], "ewmAlert": alert, "ewmOrderStatus": ewm_status,
                                 "tmsWebhook": tms_sql(f"""SELECT event_type, status FROM tms.webhook_delivery
                                                            WHERE payload LIKE '%{o['orderNumber']}%'""")})
    assert alert[0][0] == "TMS_TRANSPORT_CANCELLED" and ewm_status != "CANCELLED"

    # 12b: EWM -> TMS. An open EWM order is cancelled; TMS places a blocking hold and keeps the order.
    ref = f"PED-{tag}B-{run_id()}"
    o = tms_order(ref, 50)
    eo = ewm_order(ref, 50)
    cur = expect(ewm("GET", f"/api/v1/outbound-orders/{eo}"), 200, what="EWM order").json
    expect(ewm("PATCH", f"/api/v1/outbound-orders/{eo}/status", {"status": "CANCELLED", "version": cur["version"]}),
           200, what="EWM cancel")
    hold = wait_until(lambda: tms_sql(f"""SELECT reason_code, source, blocking FROM tms.order_hold
                                           WHERE order_id = '{o['id']}' AND released_at IS NULL""") or None,
                      120, 3, "TMS hold WAREHOUSE_CANCELLED")
    view = tms_order_view(o["id"])
    record(tag + "b", "result", {"tmsHold": hold, "tmsOrderStatus": view["status"],
                                 "ewmOrdcx": ewm_sql(f"""SELECT m.interface_type, d.status
                                                           FROM intg_outbound_message m
                                                           JOIN intg_outbound_delivery d ON d.message_id = m.id
                                                          WHERE m.interface_type = 'TMS_ORDCX'
                                                            AND m.payload LIKE '%{ref}%'""")})
    assert hold[0][0] == "WAREHOUSE_CANCELLED" and hold[0][1] == "INTEGRATION"
    assert view["status"] != "CANCELLED"


ALL = ["s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "s12"]


def main(names):
    for n in names:
        log(f"\n######## {n.upper()} ########")
        try:
            globals()[n]()
            RESULTS[n] = "PASS"
        except Exception as e:  # keep going; the report says which failed and why
            RESULTS[n] = f"FAIL: {e}"
            traceback.print_exc()
        log(f"######## {n.upper()}: {RESULTS[n]}")
    dump_evidence("-".join(names))
    log("\nSUMMARY " + json.dumps(RESULTS, indent=1))
    return 0 if all(v == "PASS" for v in RESULTS.values()) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ALL))
