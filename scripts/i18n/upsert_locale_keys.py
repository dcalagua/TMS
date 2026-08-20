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


# Keys layered over an existing namespace file, deep-merged into what is already there. Same
# shape as NEW_NAMESPACES: namespace -> language -> partial tree. Only the branches named here
# are touched; everything else in the file survives.
EXISTING_NAMESPACE_UPDATES = {
    "common": {
        "es": {
            "empty": {
                "noMatches": "Sin coincidencias",
            },
            "loading": {
                "generic": "Cargando...",
            },
        },
        "en": {
            "empty": {
                "noMatches": "No matches",
            },
            "loading": {
                "generic": "Loading...",
            },
        },
    },
    "orders": {
        "es": {
            "actions": {
                "import": "Importar",
            },
            "form": {
                "declaredHelp": "Lo que el remitente afirma que pesa u ocupa el pedido, al margen "
                                "de las líneas. Déjalo vacío si las líneas ya lo describen.",
                "declaredPallets": "Pallets declarados",
                "declaredTotals": "Totales declarados",
                "declaredVolume": "Volumen declarado (m³)",
                "declaredWeight": "Peso declarado (kg)",
                "declaredWithoutLines": "Sin líneas, estos son los totales con los que se "
                                        "planificará el pedido.",
                "declaredWithLines": "Las líneas mandan: si además declaras una cifra, ambas "
                                     "deben coincidir con un margen del 1 % o el pedido se rechaza.",
                "searchDestination": "Escribe un código o un nombre de destino",
                "searchOrigin": "Escribe un código o un nombre de origen",
                "storedTotals": "Totales guardados",
                "totalsSourceCalculated": "Calculados desde las líneas",
                "totalsSourceDeclared": "Declarados",
            },
            "import": {
                "apply": "Importar los pedidos",
                "applied": "Importación completada",
                "appliedText": "{{created}} pedidos creados y {{skipped}} omitidos por duplicado.",
                "applying": "Importando...",
                "blocked": "El archivo tiene errores y no se importó nada. Corrige las filas "
                           "indicadas y vuelve a validarlo.",
                "columnColumn": "Columna",
                "columnMessage": "Problema",
                "columnOutcome": "Resultado",
                "columnReference": "Referencia externa",
                "columnRow": "Fila",
                "confirmText": "Se crearán {{count}} pedidos en una sola operación. Si algo "
                               "falla no se guardará nada.",
                "confirmTitle": "¿Importar los pedidos?",
                "counts": {
                    "create": "Se crearán",
                    "duplicates": "Ya existen",
                    "issues": "Errores",
                    "orders": "Pedidos",
                    "rejected": "Rechazados",
                    "rows": "Filas leídas",
                },
                "downloadCsv": "Plantilla CSV",
                "downloadError": "No se pudo descargar la plantilla",
                "downloadXlsx": "Plantilla XLSX",
                "error": "No se pudo procesar el archivo",
                "externalSource": "Sistema de origen",
                "externalSourceHelp": "Identifica el sistema del que viene el archivo. Junto con "
                                      "la referencia externa de cada fila es lo que hace que "
                                      "reimportar el mismo archivo sea inofensivo.",
                "file": "Archivo",
                "fileHelp": "Formatos .xlsx o .csv, hasta {{mb}} MB y {{rows}} filas.",
                "fileSection": "2. Archivo",
                "issuesTitle": "Filas con problemas",
                "issuesTruncated": "Se muestran los primeros {{shown}} de {{total}} problemas.",
                "nothingToCreate": "No hay pedidos nuevos: todas las referencias del archivo ya "
                                   "existen en esta compañía.",
                "ordersTitle": "Pedidos del archivo",
                "outcomeCreate": "Se creará",
                "outcomeRejected": "Rechazado",
                "outcomeSkipped": "Ya existe",
                "previewSection": "3. Validación",
                "previewing": "Validando...",
                "readyToApply": "El archivo es válido. Todavía no se ha guardado nada.",
                "reset": "Empezar de nuevo",
                "selectFile": "Selecciona un archivo.",
                "subtitle": "Carga masiva de pedidos desde XLSX o CSV, validada antes de guardar.",
                "templateHelp": "Descarga la plantilla, complétala y súbela. Un pedido con varias "
                                "líneas se escribe en varias filas que comparten la misma "
                                "referencia externa.",
                "templateSection": "1. Plantilla",
                "title": "Importar pedidos",
                "validate": "Validar el archivo",
            },
        },
        "en": {
            "actions": {
                "import": "Import",
            },
            "form": {
                "declaredHelp": "What the sender asserts the order weighs or occupies, "
                                "independently of the lines. Leave empty when the lines already "
                                "describe it.",
                "declaredPallets": "Declared pallets",
                "declaredTotals": "Declared totals",
                "declaredVolume": "Declared volume (m³)",
                "declaredWeight": "Declared weight (kg)",
                "declaredWithoutLines": "With no lines, these are the totals the order will be "
                                        "planned with.",
                "declaredWithLines": "The lines win: if you also declare a figure the two must "
                                     "agree within 1% or the order is rejected.",
                "searchDestination": "Type a destination code or name",
                "searchOrigin": "Type an origin code or name",
                "storedTotals": "Stored totals",
                "totalsSourceCalculated": "Calculated from the lines",
                "totalsSourceDeclared": "Declared",
            },
            "import": {
                "apply": "Import the orders",
                "applied": "Import complete",
                "appliedText": "{{created}} orders created and {{skipped}} skipped as duplicates.",
                "applying": "Importing...",
                "blocked": "The file has errors and nothing was imported. Fix the rows listed "
                           "below and validate it again.",
                "columnColumn": "Column",
                "columnMessage": "Problem",
                "columnOutcome": "Outcome",
                "columnReference": "External reference",
                "columnRow": "Row",
                "confirmText": "{{count}} orders will be created in a single operation. If "
                               "anything fails, nothing is saved.",
                "confirmTitle": "Import the orders?",
                "counts": {
                    "create": "Will be created",
                    "duplicates": "Already exist",
                    "issues": "Problems",
                    "orders": "Orders",
                    "rejected": "Rejected",
                    "rows": "Rows read",
                },
                "downloadCsv": "CSV template",
                "downloadError": "Could not download the template",
                "downloadXlsx": "XLSX template",
                "error": "Could not process the file",
                "externalSource": "Source system",
                "externalSourceHelp": "Identifies the system the file came from. Together with "
                                      "each row's external reference it is what makes "
                                      "re-importing the same file harmless.",
                "file": "File",
                "fileHelp": ".xlsx or .csv, up to {{mb}} MB and {{rows}} rows.",
                "fileSection": "2. File",
                "issuesTitle": "Rows with problems",
                "issuesTruncated": "Showing the first {{shown}} of {{total}} problems.",
                "nothingToCreate": "No new orders: every reference in the file already exists in "
                                   "this company.",
                "ordersTitle": "Orders in the file",
                "outcomeCreate": "Will be created",
                "outcomeRejected": "Rejected",
                "outcomeSkipped": "Already exists",
                "previewSection": "3. Validation",
                "previewing": "Validating...",
                "readyToApply": "The file is valid. Nothing has been saved yet.",
                "reset": "Start over",
                "selectFile": "Select a file.",
                "subtitle": "Bulk order upload from XLSX or CSV, validated before anything is saved.",
                "templateHelp": "Download the template, fill it in and upload it. An order with "
                                "several lines is written as several rows sharing one external "
                                "reference.",
                "templateSection": "1. Template",
                "title": "Import orders",
                "validate": "Validate the file",
            },
        },
    },
}


# Dotted key paths deleted from an existing namespace, applied after the merge above. Keyed by
# namespace. A key whose last caller is gone has to go too, or the bundles slowly fill with
# strings nobody can trace to a screen - and both languages must lose it together, which is why
# this is one list rather than a per-language one.
REMOVED_KEYS = {
    # Replaced by form.searchOrigin / form.searchDestination when the Orders drawer's selects
    # became async lookups: a combobox has a "type to search" placeholder, not a "pick one".
    "orders": ["form.selectOrigin", "form.selectDestination"],
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
