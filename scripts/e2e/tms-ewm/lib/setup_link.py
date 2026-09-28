#!/usr/bin/env python3
"""Wires the two products together: EWM connector <-> TMS credential + webhook subscription.

Order: the EWM connector is saved first with a placeholder signing secret (its id is the webhook
path), then the TMS subscription is created against that path, then the connector is saved again
with the secret TMS showed once.
"""
import secrets

from common import EWM_URL, STATE_FILE, expect, http, log, save_env, state, tms


def put_connector(webhook_secret):
    st = state()
    cur = http("GET", EWM_URL + "/api/v1/admin/integrations/tms/connector")
    version = cur.json.get("version") if cur.status == 200 and cur.json else None
    body = {"baseUrl": "http://localhost:8080", "credential": st["TMS_INTEGRATION_BEARER"],
            "webhookSecret": webhook_secret, "sourceSystem": "EWM_EBIM", "tmsCompanyId": st["TMS_CO"],
            "defaultWarehouseId": None, "includeSlsDocument": True, "active": True, "version": version}
    return expect(http("PUT", EWM_URL + "/api/v1/admin/integrations/tms/connector", body), 200, 201,
                  what="EWM connector")


def main():
    st = state()
    if "EWM_CONNECTOR_ID" not in st:
        r = put_connector("placeholder-" + secrets.token_hex(16))
        save_env(STATE_FILE, EWM_CONNECTOR_ID=r.json["id"], EWM_WEBHOOK_PATH=r.json["webhookPath"])
        st = state()
    if "TMS_SUBSCRIPTION_ID" not in st:
        r = expect(tms("POST", "/api/v1/webhooks", {
            "name": "EWM by EBIM CD01 (E2E)", "description": "local e2e",
            "targetUrl": "http://localhost:8081" + st["EWM_WEBHOOK_PATH"],
            "eventTypes": ["SHIPMENT_CONFIRMED", "SHIPMENT_CHANGED", "SHIPMENT_CANCELLED"]}), 201, 200,
            what="TMS webhook subscription")
        save_env(STATE_FILE, TMS_SUBSCRIPTION_ID=r.json["subscription"]["id"])
        r2 = put_connector(r.json["secret"])
        log(f"EWM connector {r2.json['id']} holds the TMS signing secret; webhookPath {r2.json['webhookPath']}")
    log("link ready")


if __name__ == "__main__":
    main()
