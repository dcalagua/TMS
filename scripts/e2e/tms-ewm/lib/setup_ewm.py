#!/usr/bin/env python3
"""EWM master data for the E2E run, through EWM's own API (profile `local`, dev auth).

Warehouse CD01 itself is a Supabase-owned row created by ewm_supabase_owned_tables.sql.
Idempotent through state.env.
"""
from common import EWM_URL, STATE_FILE, expect, http, log, save_env, state

WH = "33333333-3333-4333-8333-333333333301"
WH2 = "33333333-3333-4333-8333-333333333302"
UOM_UN = "a0000000-0000-4000-8000-000000000001"


def ewm(method, path, body=None):
    return http(method, EWM_URL + path, body)


def ensure(key, path, body, id_path=("id",)):
    st = state()
    if key in st:
        return st[key]
    r = expect(ewm("POST", path, body), 201, 200, what=f"EWM {path} {body.get('code', '')}")
    v = r.json
    for p in id_path:
        v = v[p]
    save_env(STATE_FILE, **{key: v})
    return v


def main():
    inv = ensure("EWM_INVTYPE_0001", "/api/v1/inventory/types", {
        "code": "0001", "name": "Disponible", "allocatable": True, "availableForSale": True,
        "blocksPicking": False, "requiresQualityRelease": False, "displayOrder": 10})
    for code, direction, category, manual in [("SEED", "IN", "RECEIPT", True), ("321", "TRANSFER", "TRANSFER", False),
                                              ("322", "TRANSFER", "TRANSFER", False), ("323", "TRANSFER", "TRANSFER", False),
                                              ("601", "OUT", "ISSUE", False)]:
        ensure(f"EWM_MOVTYPE_{code}", "/api/v1/inventory/movement-types", {
            "code": code, "name": "Mov " + code, "direction": direction, "category": category,
            "affectsOnHand": True, "affectsReserved": False, "requiresSource": False,
            "requiresDestination": False, "requiresReason": False, "allowsManualPosting": manual})
    for wh_key, wh in (("", WH), ("_CD02", WH2)):
        zone = ensure("EWM_ZONE" + wh_key, "/api/v1/warehouse-zones", {
            "warehouseId": wh, "code": "Z-SALIDA", "name": "Salida", "zoneCategory": "STORAGE", "displayOrder": 10})
        if wh_key == "":
            ltype_rack = ensure("EWM_LTYPE_RACK", "/api/v1/location-types", {
                "code": "RACK", "name": "Rack", "category": "RACK", "receivingEnabled": False, "storageEnabled": True,
                "pickingEnabled": True, "packingEnabled": False, "shippingEnabled": False, "allowsInventory": True,
                "allowsLogisticUnits": True})
            ltype_stage = ensure("EWM_LTYPE_STAGE", "/api/v1/location-types", {
                "code": "STAGE", "name": "Playa", "category": "STAGING", "receivingEnabled": False, "storageEnabled": True,
                "pickingEnabled": True, "packingEnabled": False, "shippingEnabled": False, "allowsInventory": True,
                "allowsLogisticUnits": True})
        st = state()
        for code, lt in (("RACK-A01", st["EWM_LTYPE_RACK"]), ("STG-01", st["EWM_LTYPE_STAGE"]),
                         ("PACK-01", st["EWM_LTYPE_STAGE"]), ("DOCK-01", st["EWM_LTYPE_STAGE"])):
            ensure(f"EWM_LOC{wh_key}_{code.replace('-', '_')}", "/api/v1/warehouse-locations", {
                "warehouseId": wh, "zoneId": zone, "locationTypeId": lt, "code": code, "receivingEnabled": False,
                "putawayEnabled": True, "pickingEnabled": True, "packingEnabled": False, "shippingEnabled": False,
                "allowedInventoryTypes": [{"inventoryTypeId": inv, "isDefault": True}]})
    ensure("EWM_LU_TOTE", "/api/v1/logistic-unit-types", {
        "code": "TOTE", "name": "Canasta", "category": "TOTE", "canContainInventory": True, "canContainLogisticUnits": False})
    ensure("EWM_LU_CJ", "/api/v1/logistic-unit-types", {
        "code": "CJ", "name": "Caja de despacho", "codePrefix": "OBLPN", "category": "BOX",
        "canContainInventory": True, "canContainLogisticUnits": False})
    ensure("EWM_DOCTYPE_STD", "/api/v1/document-types", {"documentFamily": "OUTBOUND_ORDER", "code": "STD", "name": "Estandar"})
    ensure("EWM_ITEM_SKU100", "/api/v1/items", {
        "code": "SKU-100", "skuPartA": "SKU-100", "description": "Cemento 42.5kg", "baseUomId": UOM_UN,
        "trackingType": "NONE", "expiryControl": False, "hazmat": False,
        # 10 kg / 0.01 m3 per unit, the same as the ERP line TMS receives, so EWM can report weight
        "weight": "10", "weightUomCode": "KG", "volume": "0.01", "volumeUomCode": "M3"})
    ensure("EWM_CARRIER_TRSA", "/api/v1/carriers", {"code": "TRSA", "name": "Transportes SA"})
    st = state()
    ensure("EWM_VEHICLE_B7K812", "/api/v1/vehicles", {
        "code": "CAM-001", "plateNumber": "B7K-812", "carrierId": st["EWM_CARRIER_TRSA"], "vehicleType": "FURGON"})
    ensure("EWM_STORE", "/api/v1/stores", {"code": "SODIMAC-ATOCONGO", "name": "Sodimac Atocongo", "storeType": "STORE"})
    log("EWM master data ready")


if __name__ == "__main__":
    main()
