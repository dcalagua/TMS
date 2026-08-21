# -*- coding: utf-8 -*-
"""Writes new ES/EN i18next namespace bundles under frontend/tms-web/src/shared/i18n/locales.

The locale JSON files are generated artifacts, not hand-edited (see the project memory note
"frontend-locales-generated"): ES is the source of truth and EN must cover the same keys, or a
typed `t()` call fails to compile. To add a namespace, extend NEW_NAMESPACES below and re-run
this script, then `npx tsc -b --noEmit` from frontend/tms-web.

Only ASCII text goes through print()/raise here - this Windows shell's stdout is cp1252, not
UTF-8, and printing accented text aborts the script before it writes anything.
"""
import io
import json
import os

LOCALES_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "frontend", "tms-web", "src", "shared", "i18n", "locales",
)

# Brand-new namespace files, written from scratch. Keyed by namespace name -> language -> tree.
#
# Job 07, V30: rates and costing. One namespace covering both halves of the module - the tariff
# master and the per-shipment cost card that reads it - because they are one product idea and a
# translator needs to see "Estimado"/"Real" next to the components that produce them.
#
# Wording notes that are product decisions, not translation choices:
#   * "No calculable" is never softened to "-" or "0,00". A component the tariff charges for and
#     the shipment cannot supply has to read as missing, or an estimate that is short by the whole
#     line haul gets read as a price.
#   * "Diferencia" and not "Ahorro"/"Sobrecosto": the sign is shown, and naming it either way
#     would editorialise a number an operations manager reads in both directions.
#   * scopeHint says what each scope covers in one sentence, in the form the commercial team
#     states it ("todo lo que salga de este origen"), because the choice between the three is the
#     only part of this form somebody can get wrong without noticing.
NEW_NAMESPACES = {
    "rates": {
        "es": {
            "activateText": "La tarifa volverá a aplicarse a los envíos que cubra.",
            "anyVehicleType": "Cualquier tipo",
            "columns": {
                "components": "Componentes",
                "scope": "Alcance",
                "validity": "Vigencia",
            },
            "componentShort": {
                "base": "Base",
                "minimum": "Mínimo",
                "perKg": "/kg",
                "perKm": "/km",
                "perM3": "/m³",
                "perPallet": "/pallet",
            },
            "deactivateText": "La tarifa dejará de aplicarse. Los costos ya calculados con ella no cambian.",
            "description": "Lo que cobra cada transportista, por qué concepto y entre qué fechas.",
            "empty": {
                "message": "Crea una tarifa para que TMS pueda estimar el costo de cada envío.",
                "title": "Sin tarifas",
            },
            "filters": {
                "allScopes": "Todos los alcances",
                "onDate": "Vigentes el día",
            },
            "form": {
                "amountPerKg": "Importe por kg",
                "amountPerKm": "Importe por km",
                "amountPerM3": "Importe por m³",
                "amountPerPallet": "Importe por pallet",
                "baseAmount": "Importe base",
                "carrierLocked": "El transportista no se puede cambiar: sería otro acuerdo. Crea una tarifa nueva.",
                "componentsHelp": "Se cobra la suma de los componentes definidos. Deja en blanco los que no apliquen.",
                "create": "Nueva tarifa",
                "currency": "Moneda",
                "currencyHelp": "Código ISO de 3 letras, por ejemplo PEN o USD. No hay conversión entre monedas.",
                "edit": "Editar tarifa",
                "minimumAmount": "Importe mínimo",
                "minimumHelp": "Piso que se cobra aunque los componentes sumen menos.",
                "needsAComponent": "La tarifa debe definir al menos un componente además del mínimo.",
                "route": "Ruta",
                "sections": {
                    "components": "Componentes",
                    "scope": "Alcance",
                    "validity": "Vigencia y moneda",
                },
                "selectCarrier": "Selecciona un transportista",
                "selectOrigin": "Selecciona un origen",
                "selectRoute": "Selecciona una ruta",
                "subtitle": "Un acuerdo comercial con un transportista, vigente entre dos fechas.",
                "validToHelp": "En blanco es una vigencia sin fecha de fin.",
                "vehicleTypeHelp": "En blanco aplica a cualquier tipo de vehículo.",
            },
            "new": "Nueva tarifa",
            "noEndDate": "sin fin",
            "scopeHint": {
                "CARRIER": "Aplica a todo lo que mueva este transportista.",
                "ORIGIN": "Aplica a todo lo que salga de este origen.",
                "ROUTE": "Aplica solo a los envíos armados desde esta ruta maestra.",
            },
            "title": "Tarifas",
            "tripCost": {
                "actions": {
                    "close": "Cerrar costo",
                    "estimate": "Estimar",
                    "reEstimate": "Volver a estimar",
                    "recordActual": "Registrar real",
                    "reopen": "Reabrir",
                },
                "actual": "Real",
                "breakdown": {
                    "amount": "Importe",
                    "component": "Concepto",
                    "detail": "Cálculo",
                    "notCalculable": "No calculable",
                    "title": "Detalle de la estimación",
                },
                "closedOn": "Costo cerrado el {{date}}",
                "confirm": {
                    "closeText": "Después de cerrarlo no se podrá modificar hasta reabrirlo.",
                    "closeTitle": "¿Cerrar el costo?",
                    "reopenText": "El costo volverá a ser editable. La reapertura queda registrada en la auditoría.",
                    "reopenTitle": "¿Reabrir el costo?",
                },
                "estimated": "Estimado",
                "form": {
                    "amount": "Importe real",
                    "currencyHelp": "Este envío no tiene estimación, así que hay que indicar la moneda.",
                    "reference": "Documento del transportista",
                    "referenceHelp": "Número de factura o liquidación, tal como lo emitió el transportista.",
                    "subtitle": "Lo que el transportista cobró realmente por este envío.",
                    "title": "Costo real",
                },
                "incomplete": "La estimación está incompleta: hay conceptos que no se pudieron calcular y no suman nada.",
                "notPriced": "Este envío todavía no tiene costo estimado.",
                "notify": {
                    "closed": "Costo cerrado",
                    "estimated": "Costo estimado",
                    "reopened": "Costo reabierto",
                },
                "rateCard": "Tarifa {{code}} — {{name}}",
                "title": "Costo",
                "variance": "Diferencia",
            },
        },
        "en": {
            "activateText": "The rate card will apply again to the shipments it covers.",
            "anyVehicleType": "Any type",
            "columns": {
                "components": "Components",
                "scope": "Scope",
                "validity": "Validity",
            },
            "componentShort": {
                "base": "Base",
                "minimum": "Minimum",
                "perKg": "/kg",
                "perKm": "/km",
                "perM3": "/m³",
                "perPallet": "/pallet",
            },
            "deactivateText": "The rate card will stop applying. Costs already calculated from it do not change.",
            "description": "What each carrier charges, for what, and between which dates.",
            "empty": {
                "message": "Create a rate card so TMS can estimate what each shipment costs.",
                "title": "No rate cards",
            },
            "filters": {
                "allScopes": "All scopes",
                "onDate": "In force on",
            },
            "form": {
                "amountPerKg": "Amount per kg",
                "amountPerKm": "Amount per km",
                "amountPerM3": "Amount per m³",
                "amountPerPallet": "Amount per pallet",
                "baseAmount": "Base amount",
                "carrierLocked": "The carrier cannot be changed: that would be a different agreement. Create a new card.",
                "componentsHelp": "The shipment is charged the sum of the components defined here. Leave the rest blank.",
                "create": "New rate card",
                "currency": "Currency",
                "currencyHelp": "Three-letter ISO code, for example PEN or USD. There is no conversion between currencies.",
                "edit": "Edit rate card",
                "minimumAmount": "Minimum amount",
                "minimumHelp": "The floor charged even when the components add up to less.",
                "needsAComponent": "A rate card must define at least one component besides the minimum.",
                "route": "Route",
                "sections": {
                    "components": "Components",
                    "scope": "Scope",
                    "validity": "Validity and currency",
                },
                "selectCarrier": "Select a carrier",
                "selectOrigin": "Select an origin",
                "selectRoute": "Select a route",
                "subtitle": "A commercial agreement with one carrier, in force between two dates.",
                "validToHelp": "Blank means an open-ended agreement.",
                "vehicleTypeHelp": "Blank applies to any vehicle type.",
            },
            "new": "New rate card",
            "noEndDate": "open-ended",
            "scopeHint": {
                "CARRIER": "Applies to anything this carrier runs.",
                "ORIGIN": "Applies to anything leaving this origin.",
                "ROUTE": "Applies only to shipments built from this master route.",
            },
            "title": "Rate cards",
            "tripCost": {
                "actions": {
                    "close": "Close cost",
                    "estimate": "Estimate",
                    "reEstimate": "Re-estimate",
                    "recordActual": "Record actual",
                    "reopen": "Reopen",
                },
                "actual": "Actual",
                "breakdown": {
                    "amount": "Amount",
                    "component": "Component",
                    "detail": "Calculation",
                    "notCalculable": "Not calculable",
                    "title": "Estimate breakdown",
                },
                "closedOn": "Cost closed on {{date}}",
                "confirm": {
                    "closeText": "Once closed it cannot be changed until it is reopened.",
                    "closeTitle": "Close this cost?",
                    "reopenText": "The cost becomes editable again. Reopening is recorded in the audit trail.",
                    "reopenTitle": "Reopen this cost?",
                },
                "estimated": "Estimated",
                "form": {
                    "amount": "Actual amount",
                    "currencyHelp": "This shipment has no estimate, so the currency has to be stated.",
                    "reference": "Carrier document",
                    "referenceHelp": "Invoice or settlement number, exactly as the carrier issued it.",
                    "subtitle": "What the carrier actually charged for this shipment.",
                    "title": "Actual cost",
                },
                "incomplete": "The estimate is incomplete: some components could not be calculated and add nothing.",
                "notPriced": "This shipment has not been priced yet.",
                "notify": {
                    "closed": "Cost closed",
                    "estimated": "Cost estimated",
                    "reopened": "Cost reopened",
                },
                "rateCard": "Rate card {{code}} — {{name}}",
                "title": "Cost",
                "variance": "Variance",
            },
        },
    },
}


# Keys layered over an existing namespace file, deep-merged into what is already there. Same
# shape as NEW_NAMESPACES: namespace -> language -> partial tree. Only the branches named here
# are touched; everything else in the file survives.
EXISTING_NAMESPACE_UPDATES = {
    # The four V30 enums. They live in `statuses` with every other value the API transports,
    # because `enums.test.ts` walks that bundle and fails if any value the client can receive has
    # no label - which is what stops NOT_CALCULABLE reaching an operator.
    "statuses": {
        "es": {
            "costComponentReason": {
                "DISTANCE_UNKNOWN": "Sin distancia conocida",
                "PALLETS_UNKNOWN": "Sin pallets declarados",
                "VOLUME_UNKNOWN": "Sin volumen declarado",
                "WEIGHT_UNKNOWN": "Sin peso declarado",
            },
            "costQuantitySource": {
                "ORDER_DECLARED_TOTALS": "Totales declarados de los pedidos",
                "ROUTE_REFERENCE": "Distancia de referencia de la ruta",
            },
            "rateCardScope": {
                "CARRIER": "Transportista",
                "ORIGIN": "Origen",
                "ROUTE": "Ruta",
            },
            "rateComponent": {
                "BASE": "Base",
                "DISTANCE": "Distancia",
                "MINIMUM_ADJUSTMENT": "Ajuste al mínimo",
                "PALLETS": "Pallets",
                "VOLUME": "Volumen",
                "WEIGHT": "Peso",
            },
        },
        "en": {
            "costComponentReason": {
                "DISTANCE_UNKNOWN": "No known distance",
                "PALLETS_UNKNOWN": "No pallets declared",
                "VOLUME_UNKNOWN": "No volume declared",
                "WEIGHT_UNKNOWN": "No weight declared",
            },
            "costQuantitySource": {
                "ORDER_DECLARED_TOTALS": "Declared order totals",
                "ROUTE_REFERENCE": "Route reference distance",
            },
            "rateCardScope": {
                "CARRIER": "Carrier",
                "ORIGIN": "Origin",
                "ROUTE": "Route",
            },
            "rateComponent": {
                "BASE": "Base",
                "DISTANCE": "Distance",
                "MINIMUM_ADJUSTMENT": "Minimum adjustment",
                "PALLETS": "Pallets",
                "VOLUME": "Volume",
                "WEIGHT": "Weight",
            },
        },
    },
    # The Rates group and its single screen. `items.drivers` is here too and does not belong to
    # this job: navConfig has referenced it since job 03 with no key behind it, which makes
    # `ParseKeys<'navigation'>` reject the whole file. Adding the two words it needs is cheaper
    # than leaving the navigation bundle uncompilable for every job after it.
    "navigation": {
        "es": {
            "groups": {"rates": "Tarifas"},
            "items": {"drivers": "Conductores", "rateCards": "Tarifas"},
        },
        "en": {
            "groups": {"rates": "Rates"},
            "items": {"drivers": "Drivers", "rateCards": "Rate cards"},
        },
    },
}


# Dotted key paths deleted from an existing namespace, applied after the merge above. Keyed by
# namespace. A key whose last caller is gone has to go too, or the bundles slowly fill with
# strings nobody can trace to a screen - and both languages must lose it together, which is why
# this is one list rather than a per-language one.
REMOVED_KEYS = {}


def remove_key(tree, dotted):
    parts = dotted.split(".")
    node = tree
    for part in parts[:-1]:
        node = node.get(part)
        if not isinstance(node, dict):
            return False
    return node.pop(parts[-1], None) is not None


def check_parity(node_es, node_en, path=""):
    keys_es = set(node_es.keys())
    keys_en = set(node_en.keys())
    if keys_es != keys_en:
        only_es = sorted(keys_es - keys_en)
        only_en = sorted(keys_en - keys_es)
        raise SystemExit("parity mismatch at '%s': only in es=%s only in en=%s" % (path, only_es, only_en))
    for key in sorted(keys_es):
        child_path = ("%s.%s" % (path, key)) if path else key
        value_es, value_en = node_es[key], node_en[key]
        if isinstance(value_es, dict) != isinstance(value_en, dict):
            raise SystemExit("shape mismatch at '%s'" % child_path)
        if isinstance(value_es, dict):
            check_parity(value_es, value_en, child_path)
        elif not str(value_es).strip() or not str(value_en).strip():
            raise SystemExit("empty value at '%s'" % child_path)


def read_namespace(name, lang):
    path = os.path.join(LOCALES_DIR, lang, name + ".json")
    if not os.path.exists(path):
        raise SystemExit("cannot update '%s': %s does not exist (add it to NEW_NAMESPACES instead)" % (name, path))
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def deep_merge(base, updates, path=""):
    """Returns `base` with `updates` layered over it, recursing into nested objects.

    An update that replaces a leaf is allowed and is how a wording fix is applied; an update
    that would turn a leaf into an object, or vice versa, is a mistake in the caller's tree and
    stops the run rather than silently reshaping a namespace.
    """
    merged = dict(base)
    for key in sorted(updates.keys()):
        child_path = ("%s.%s" % (path, key)) if path else key
        update = updates[key]
        current = merged.get(key)
        if key in merged and isinstance(current, dict) != isinstance(update, dict):
            raise SystemExit("shape change at '%s': cannot merge a leaf and an object" % child_path)
        if isinstance(update, dict):
            # A branch absent from the file is merged onto an empty one - that is how a whole
            # new section like `orders.import` gets added without a separate code path.
            merged[key] = deep_merge(current if isinstance(current, dict) else {}, update, child_path)
        else:
            merged[key] = update
    return merged


def write_namespace(name, es_tree, en_tree):
    check_parity(es_tree, en_tree)
    for lang, tree in (("es", es_tree), ("en", en_tree)):
        path = os.path.join(LOCALES_DIR, lang, name + ".json")
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(tree, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
    print("wrote namespace: " + name)


def main():
    for name, langs in NEW_NAMESPACES.items():
        write_namespace(name, langs["es"], langs["en"])
    # Merged second, so a namespace that appears in both is created and then updated rather
    # than updated against a file the same run is about to overwrite.
    updated = set(EXISTING_NAMESPACE_UPDATES) | set(REMOVED_KEYS)
    for name in sorted(updated):
        langs = EXISTING_NAMESPACE_UPDATES.get(name, {"es": {}, "en": {}})
        merged = {}
        for lang in ("es", "en"):
            tree = deep_merge(read_namespace(name, lang), langs[lang])
            for dotted in REMOVED_KEYS.get(name, []):
                remove_key(tree, dotted)
            merged[lang] = tree
        # check_parity inside write_namespace is what proves a removal took both languages with
        # it: drop a key from one and the run stops before either file is written.
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
