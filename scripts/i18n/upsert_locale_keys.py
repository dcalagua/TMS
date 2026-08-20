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
NEW_NAMESPACES = {}


# Keys layered over an existing namespace file, deep-merged into what is already there. Same
# shape as NEW_NAMESPACES: namespace -> language -> partial tree. Only the branches named here
# are touched; everything else in the file survives.
EXISTING_NAMESPACE_UPDATES = {
    # Frequency date exceptions get an editor. The wording carries the two-kind model: a
    # closed date removes one the cadence would have served, an open one adds one it would not.
    "masters": {
        "es": {
            "frequencies": {
                "form": {
                    "exceptions": "Excepciones por fecha",
                    "exceptionsHelp": "Fechas que se apartan de la cadencia semanal: feriados en "
                                      "los que no se atiende, o d\u00edas extra en los que s\u00ed. "
                                      "No permiten cambiar la hora de corte de una fecha.",
                    "noExceptions": "Sin excepciones registradas.",
                    "exceptionDate": "Fecha",
                    "exceptionKind": "Tipo",
                    "exceptionNote": "Nota",
                    "exceptionClosed": "Cerrado",
                    "exceptionOpen": "Abierto",
                    "addException": "Agregar excepci\u00f3n",
                    "removeException": "Eliminar la excepci\u00f3n del {{date}}",
                    "saveFirstForExceptions": "Guarda la frecuencia primero para poder registrar "
                                              "excepciones por fecha.",
                },
            },
        },
        "en": {
            "frequencies": {
                "form": {
                    "exceptions": "Date exceptions",
                    "exceptionsHelp": "Dates that depart from the weekly cadence: holidays with no "
                                      "service, or extra days with service. They cannot change a "
                                      "single date's cutoff time.",
                    "noExceptions": "No exceptions recorded.",
                    "exceptionDate": "Date",
                    "exceptionKind": "Kind",
                    "exceptionNote": "Note",
                    "exceptionClosed": "Closed",
                    "exceptionOpen": "Open",
                    "addException": "Add exception",
                    "removeException": "Remove the exception for {{date}}",
                    "saveFirstForExceptions": "Save the frequency first to record date exceptions.",
                },
            },
        },
    },
    # Automatic planning V1. The wording carries the product rule: the engine proposes, a person
    # decides, and every order it could not place is named rather than quietly dropped.
    "planning": {
        "es": {
            "boardScreen": {
                "autoPlan": "Planificar autom\u00e1ticamente",
            },
            "autoPlan": {
                "title": "Planificaci\u00f3n autom\u00e1tica",
                "subtitle": "Propuesta de viajes en borrador. Revisa antes de aplicar; nada se "
                            "confirma autom\u00e1ticamente.",
                "summary": "Resumen",
                "ordersConsidered": "Pedidos evaluados",
                "vehiclesOffered": "Unidades disponibles",
                "tripsProposed": "Viajes propuestos",
                "ordersPlanned": "Pedidos asignados",
                "engineNote": "Generado por {{engine}}. La misma entrada produce siempre la misma "
                              "propuesta.",
                "proposedTrips": "Viajes propuestos",
                "vehicle": "Unidad",
                "orders": "Pedidos",
                "stops": "Paradas",
                "orderCount_one": "{{count}} pedido",
                "orderCount_other": "{{count}} pedidos",
                "nothingToPlan": "No hay nada que planificar con los pedidos y unidades de esta fecha.",
                "unplanned": "Pedidos sin asignar",
                "unplannedHelp": "Estos pedidos siguen disponibles en el pool. Decide qu\u00e9 hacer "
                                 "con cada uno.",
                "everythingPlanned": "Todos los pedidos evaluados quedaron asignados.",
                "reason": "Motivo",
                "reasons": {
                    "exceedsLargestVehicle": "Excede la capacidad de cualquier unidad disponible. "
                                             "Divide el pedido o incorpora una unidad mayor.",
                    "noVehicleAvailable": "No qued\u00f3 capacidad disponible en la flota de esta fecha.",
                    "noFleet": "No hay unidades disponibles para esta fecha.",
                    "notServiceableOnDate": "El destino no se atiende en esta fecha seg\u00fan su "
                                            "calendario de servicio.",
                },
                "apply": "Aplicar propuesta",
                "applying": "Aplicando...",
                "appliedTitle": "Propuesta aplicada",
                "appliedText_one": "Se cre\u00f3 {{count}} viaje en borrador.",
                "appliedText_other": "Se crearon {{count}} viajes en borrador.",
                "failedTitle": "No se pudo aplicar la propuesta",
            },
        },
        "en": {
            "boardScreen": {
                "autoPlan": "Plan automatically",
            },
            "autoPlan": {
                "title": "Automatic planning",
                "subtitle": "A proposal of draft trips. Review before applying; nothing is "
                            "confirmed automatically.",
                "summary": "Summary",
                "ordersConsidered": "Orders considered",
                "vehiclesOffered": "Vehicles available",
                "tripsProposed": "Trips proposed",
                "ordersPlanned": "Orders assigned",
                "engineNote": "Produced by {{engine}}. The same input always produces the same "
                              "proposal.",
                "proposedTrips": "Proposed trips",
                "vehicle": "Vehicle",
                "orders": "Orders",
                "stops": "Stops",
                "orderCount_one": "{{count}} order",
                "orderCount_other": "{{count}} orders",
                "nothingToPlan": "There is nothing to plan with this date's orders and vehicles.",
                "unplanned": "Unplanned orders",
                "unplannedHelp": "These orders are still in the pool. Decide what to do with each "
                                 "one.",
                "everythingPlanned": "Every order considered was assigned.",
                "reason": "Reason",
                "reasons": {
                    "exceedsLargestVehicle": "Larger than any available vehicle. Split the order or "
                                             "add a bigger one.",
                    "noVehicleAvailable": "No capacity left in this date's fleet.",
                    "noFleet": "No vehicles are available for this date.",
                    "notServiceableOnDate": "The destination is not served on this date according "
                                            "to its service calendar.",
                },
                "apply": "Apply proposal",
                "applying": "Applying...",
                "appliedTitle": "Proposal applied",
                "appliedText_one": "{{count}} draft trip created.",
                "appliedText_other": "{{count}} draft trips created.",
                "failedTitle": "Could not apply the proposal",
            },
        },
    },
}


# Dotted key paths deleted from an existing namespace, applied after the merge above. Keyed by
# namespace. A key whose last caller is gone has to go too, or the bundles slowly fill with
# strings nobody can trace to a screen - and both languages must lose it together, which is why
# this is one list rather than a per-language one.
REMOVED_KEYS = {
    # The exceptions editor exists now, so the note that apologised for its absence does not.
    "masters": ["frequencies.form.exceptionsNote"],
}


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
