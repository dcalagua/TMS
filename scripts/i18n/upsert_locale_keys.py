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
NEW_NAMESPACES = {
    "maps": {
        "es": {
            "advancedCoordinates": "Coordenadas exactas",
            "helpText": "Haz clic en el mapa o arrastra el marcador para ajustar la ubicación.",
            "loading": "Cargando el mapa...",
            "mapAriaLabel": "Mapa para seleccionar la ubicación",
            "search": "Buscar",
            "searchFailed": "No se pudo buscar la dirección. Inténtalo de nuevo.",
            "searchNoResults": "No se encontraron resultados para esa dirección.",
            "searchPlaceholder": "Buscar una dirección",
            "stopsAriaLabel": "Mapa de paradas",
            "unavailable": "El mapa no está disponible. Puedes ingresar la latitud y la "
                            "longitud manualmente.",
        },
        "en": {
            "advancedCoordinates": "Exact coordinates",
            "helpText": "Click the map or drag the marker to adjust the location.",
            "loading": "Loading the map...",
            "mapAriaLabel": "Map to pick the location",
            "search": "Search",
            "searchFailed": "Could not search the address. Try again.",
            "searchNoResults": "No results found for that address.",
            "searchPlaceholder": "Search an address",
            "stopsAriaLabel": "Stops map",
            "unavailable": "The map is unavailable. You can enter latitude and longitude manually.",
        },
    },
}


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


if __name__ == "__main__":
    main()
