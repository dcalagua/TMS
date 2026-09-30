#!/usr/bin/env python3
"""TMS master data for the E2E run: origin CD01, destinations, carrier TRSA, vehicles, driver,
and the integration credential EWM will hold. Idempotent: reuses what state.env already knows."""
from common import STATE_FILE, expect, log, save_env, state, tms

PLATES = ["B7K-812"] + [f"B7K-{n}" for n in range(813, 845)]   # one per concurrent trip


def ensure_location(key, body):
    st = state()
    if key in st:
        return st[key]
    r = expect(tms("POST", "/api/v1/masterdata/locations", body), 201, 200, what="location " + body["code"])
    save_env(STATE_FILE, **{key: r.json["id"]})
    return r.json["id"]


def main():
    ensure_location("TMS_ORIGIN_ID", {
        "code": "CD-LIMA", "name": "CD Lima (EWM CD01)", "type": "DISTRIBUTION_CENTER", "roles": ["ORIGIN"],
        "country": "PE", "timeZone": "America/Lima", "serviceTimeMinutes": 0,
        "latitude": -12.0464, "longitude": -77.0428, "externalSystem": "EWM_EBIM", "externalReference": "CD01"})
    ensure_location("TMS_DEST_ID", {
        "code": "SODIMAC-ATOCONGO", "name": "Sodimac Atocongo", "type": "STORE", "roles": ["DESTINATION"],
        "country": "PE", "timeZone": "America/Lima", "serviceTimeMinutes": 30,
        "latitude": -12.1500, "longitude": -76.9800})
    st = state()
    if "TMS_CARRIER_ID" not in st:
        r = expect(tms("POST", "/api/v1/fleet/carriers", {
            "code": "TRSA", "businessName": "Transportes SA", "taxIdType": "RUC", "taxIdValue": "20100000019"}),
            201, 200, what="carrier")
        save_env(STATE_FILE, TMS_CARRIER_ID=r.json["id"])
    if "TMS_VTYPE_ID" not in st:
        r = expect(tms("POST", "/api/v1/fleet/vehicle-types", {
            "code": "FURGON", "name": "Furgon", "maxWeightKg": 20000, "maxVolumeM3": 60, "maxPallets": 400, "temperatureControlled": False}),
            201, 200, what="vehicle type")
        save_env(STATE_FILE, TMS_VTYPE_ID=r.json["id"])
    st = state()
    for i, plate in enumerate(PLATES):
        key = f"TMS_VEHICLE_{i}"
        if key in st:
            continue
        r = expect(tms("POST", "/api/v1/fleet/vehicles", {
            "code": "VEH-" + plate.replace("-", ""), "licensePlate": plate, "carrierId": st["TMS_CARRIER_ID"],
            "vehicleTypeId": st["TMS_VTYPE_ID"], "availabilityStatus": "AVAILABLE"}), 201, 200, what="vehicle")
        save_env(STATE_FILE, **{key: r.json["id"], f"TMS_PLATE_{i}": plate})
    if "TMS_DRIVER_ID" not in st:
        r = expect(tms("POST", "/api/v1/fleet/drivers", {
            "code": "DRV-044", "firstName": "Juan", "lastName": "Perez", "documentType": "DNI",
            "documentNumber": "40123456", "licenseNumber": "Q40123456", "licenseExpiresOn": "2028-01-01",
            "carrierId": st["TMS_CARRIER_ID"]}), 201, 200, what="driver")
        save_env(STATE_FILE, TMS_DRIVER_ID=r.json["id"])
    if "TMS_INTEGRATION_BEARER" not in st:
        r = expect(tms("POST", "/api/v1/integration-clients", {
            "name": "EWM by EBIM (E2E)", "description": "local e2e",
            "scopes": ["integration.shipment:read", "integration.dispatch:write",
                       "integration.warehouse-milestone:write", "integration.order:write"],
            "carrierId": None}), 201, 200, what="integration client")
        save_env(STATE_FILE, TMS_INTEGRATION_BEARER=r.json["bearerToken"],
                 TMS_INTEGRATION_CLIENT_ID=r.json["client"]["id"])
    log("TMS master data ready")


if __name__ == "__main__":
    main()
