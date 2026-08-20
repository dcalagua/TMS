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
    # The Location domain close-out: a location's TYPE says what the place is, its OPERATIONAL
    # USE says how it may be used in a movement. The old wording called the second one "roles"
    # and listed five values that were really types, so the screen showed "Tipo: Tienda" beside
    # "Roles: Tienda". Every string below exists to keep those two questions apart in words.
    "statuses": {
        "es": {
            "locationRole": {
                "ORIGIN": "Origen",
                "DESTINATION": "Destino",
            },
        },
        "en": {
            "locationRole": {
                "ORIGIN": "Origin",
                "DESTINATION": "Destination",
            },
        },
    },
    "masters": {
        "es": {
            "locations": {
                "description": "Lugares f\u00edsicos utilizados por la operaci\u00f3n: tiendas, "
                               "almacenes, plantas, hubs y puntos de entrega.",
                "columns": {
                    "use": "Uso operacional",
                },
                "use": {
                    "none": "Sin uso definido",
                },
                "filters": {
                    "anyUse": "Cualquier uso",
                },
                "form": {
                    "sectionUse": "Uso operacional",
                    "useHelp": "Define c\u00f3mo puede utilizarse este lugar en el transporte. "
                               "Un mismo sitio puede ser origen y destino: la tienda recibe la "
                               "entrega y despacha la devoluci\u00f3n.",
                    "canBeOrigin": "Puede utilizarse como origen",
                    "canBeDestination": "Puede utilizarse como destino",
                },
                "import": {
                    "columns": {
                        "roles": "Uso operacional",
                    },
                },
            },
            "origins": {
                "title": "Or\u00edgenes",
                "description": "Ubicaciones habilitadas para despachar. Es la misma ficha de "
                               "Ubicaciones, filtrada por uso operacional.",
                "new": "Nuevo origen",
                "empty": {
                    "title": "Sin or\u00edgenes",
                    "message": "Marca \u00abPuede utilizarse como origen\u00bb en una ubicaci\u00f3n "
                               "o crea una nueva.",
                },
            },
            "destinations": {
                "title": "Destinos",
                "description": "Ubicaciones habilitadas para recibir entregas. Es la misma ficha "
                               "de Ubicaciones, filtrada por uso operacional.",
                "new": "Nuevo destino",
                "empty": {
                    "title": "Sin destinos",
                    "message": "Marca \u00abPuede utilizarse como destino\u00bb en una ubicaci\u00f3n "
                               "o crea una nueva.",
                },
            },
        },
        "en": {
            "locations": {
                "description": "Physical places the operation uses: stores, warehouses, plants, "
                               "hubs and delivery points.",
                "columns": {
                    "use": "Operational use",
                },
                "use": {
                    "none": "No use set",
                },
                "filters": {
                    "anyUse": "Any use",
                },
                "form": {
                    "sectionUse": "Operational use",
                    "useHelp": "How this place may be used in transport. One site can be both: "
                               "the store receives the delivery and ships the return.",
                    "canBeOrigin": "Can be used as origin",
                    "canBeDestination": "Can be used as destination",
                },
                "import": {
                    "columns": {
                        "roles": "Operational use",
                    },
                },
            },
            "origins": {
                "title": "Origins",
                "description": "Locations enabled to ship. The same Locations record, filtered "
                               "by operational use.",
                "new": "New origin",
                "empty": {
                    "title": "No origins",
                    "message": "Tick \u201cCan be used as origin\u201d on a location, or create "
                               "a new one.",
                },
            },
            "destinations": {
                "title": "Destinations",
                "description": "Locations enabled to receive deliveries. The same Locations "
                               "record, filtered by operational use.",
                "new": "New destination",
                "empty": {
                    "title": "No destinations",
                    "message": "Tick \u201cCan be used as destination\u201d on a location, or "
                               "create a new one.",
                },
            },
        },
    },
}


# Dotted key paths deleted from an existing namespace, applied after the merge above. Keyed by
# namespace. A key whose last caller is gone has to go too, or the bundles slowly fill with
# strings nobody can trace to a screen - and both languages must lose it together, which is why
# this is one list rather than a per-language one.
REMOVED_KEYS = {
    # The two legacy type vocabularies and the five role values that were really types. Their
    # last callers went with tms.origin and tms.destination.
    "statuses": [
        "originType",
        "destinationType",
        "locationRole.SHIP_TO",
        "locationRole.STORE",
        "locationRole.DC",
        "locationRole.PLANT",
        "locationRole.HUB",
        "locationRole.OTHER",
    ],
    "masters": [
        # "Roles" and "Utilizable como" were the same fact told twice; one "Uso operacional"
        # column replaces both.
        "locations.columns.roles",
        "locations.columns.usableAs",
        "locations.usableAs",
        "locations.filters.allRoles",
        "locations.form.sectionRoles",
        "locations.form.rolesHelp",
        # Origins and Destinations no longer have forms of their own: both open the Location
        # drawer with one use pre-ticked.
        "origins.form",
        "destinations.form",
    ],
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
