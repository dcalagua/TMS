# -*- coding: utf-8 -*-
"""Job 09 (Import Center) locale additions, applied through the shared upsert helpers.

Adds the strings the new master-data bulk import drawers need: one generic `actions.import`
verb in `common`, and one `<entity>.import` section per entity in `masters` (locations) and
`fleet` (carriers, vehicleTypes, vehicles), mirroring `orders.import`'s existing shape so the
four new screens read like siblings of the order import rather than a different feature.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job09.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


def _generic_import_strings(es_entity_plural, es_entity_article, en_entity_plural, en_entity_singular):
    """The ~30 keys every `<entity>.import` section shares, parameterised by wording only."""
    es = {
        "apply": "Importar %s" % es_entity_plural,
        "applied": "Importación completa",
        "appliedText": "{{created}} creados y {{skipped}} omitidos por ya existir.",
        "applying": "Importando...",
        "blocked": "El archivo tiene errores y no se importó nada. Corrige las filas indicadas "
                   "y valida de nuevo.",
        "cancel": "Cancelar",
        "close": "Cerrar",
        "columnColumn": "Columna",
        "columnIdentifier": "Código",
        "columnMessage": "Problema",
        "columnRow": "Fila",
        "confirmText": "Se crearán {{count}} %s en una sola operación. Si algo falla, no se "
                       "guarda nada." % es_entity_plural,
        "confirmTitle": "¿Importar %s?" % es_entity_plural,
        "countCreate": "Se crearán",
        "countDuplicates": "Ya existen",
        "countIssues": "Problemas",
        "countItems": es_entity_plural.capitalize(),
        "countRejected": "Rechazados",
        "countRows": "Filas leídas",
        "downloadCsv": "Plantilla CSV",
        "downloadError": "No se pudo descargar la plantilla",
        "downloadIssuesReport": "Descargar reporte de errores",
        "downloadXlsx": "Plantilla XLSX",
        "file": "Archivo",
        "fileHelp": ".xlsx o .csv, hasta {{mb}} MB y {{rows}} filas.",
        "fileSection": "2. Archivo",
        "issuesTitle": "Filas con problemas",
        "issuesTruncated": "Mostrando los primeros {{shown}} de {{total}} problemas.",
        "nothingToCreate": "Nada nuevo: todos los códigos del archivo ya existen en esta empresa.",
        "outcomeCreate": "Se creará",
        "outcomeRejected": "Rechazado",
        "outcomeSkipped": "Ya existe",
        "previewSection": "3. Validación",
        "previewing": "Validando...",
        "readyToApply": "El archivo es válido. Aún no se ha guardado nada.",
        "reset": "Empezar de nuevo",
        "templateSection": "1. Plantilla",
        "validate": "Validar el archivo",
    }
    en = {
        "apply": "Import %s" % en_entity_plural,
        "applied": "Import complete",
        "appliedText": "{{created}} created and {{skipped}} skipped as duplicates.",
        "applying": "Importing...",
        "blocked": "The file has errors and nothing was imported. Fix the rows listed below "
                   "and validate it again.",
        "cancel": "Cancel",
        "close": "Close",
        "columnColumn": "Column",
        "columnIdentifier": "Code",
        "columnMessage": "Problem",
        "columnRow": "Row",
        "confirmText": "{{count}} %s will be created in a single operation. If anything fails, "
                       "nothing is saved." % en_entity_plural,
        "confirmTitle": "Import %s?" % en_entity_plural,
        "countCreate": "Will be created",
        "countDuplicates": "Already exist",
        "countIssues": "Problems",
        "countItems": en_entity_plural.capitalize(),
        "countRejected": "Rejected",
        "countRows": "Rows read",
        "downloadCsv": "CSV template",
        "downloadError": "Could not download the template",
        "downloadIssuesReport": "Download error report",
        "downloadXlsx": "XLSX template",
        "file": "File",
        "fileHelp": ".xlsx or .csv, up to {{mb}} MB and {{rows}} rows.",
        "fileSection": "2. File",
        "issuesTitle": "Rows with problems",
        "issuesTruncated": "Showing the first {{shown}} of {{total}} problems.",
        "nothingToCreate": "Nothing new: every code in the file already exists in this company.",
        "outcomeCreate": "Will be created",
        "outcomeRejected": "Rejected",
        "outcomeSkipped": "Already exists",
        "previewSection": "3. Validation",
        "previewing": "Validating...",
        "readyToApply": "The file is valid. Nothing has been saved yet.",
        "reset": "Start over",
        "templateSection": "1. Template",
        "validate": "Validate the file",
    }
    return es, en


UPDATES = {
    "common": {
        "es": {"actions": {"import": "Importar"}},
        "en": {"actions": {"import": "Import"}},
    },
    "masters": {
        "es": {},
        "en": {},
    },
    "fleet": {
        "es": {},
        "en": {},
    },
}

_LOC_ES, _LOC_EN = _generic_import_strings("ubicaciones", "las", "locations", "location")
_LOC_ES.update({
    "title": "Importar ubicaciones",
    "subtitle": "Carga masiva de ubicaciones desde XLSX o CSV, validada antes de guardar nada.",
    "templateHelp": "Descarga la plantilla, complétala y súbela. code es obligatorio y único "
                     "por empresa; roles acepta varios valores separados por comas.",
    "itemsTitle": "Ubicaciones en el archivo",
    "columns": {
        "type": "Tipo", "roles": "Roles", "zone": "Zona", "coordinates": "Coordenadas",
    },
})
_LOC_EN.update({
    "title": "Import locations",
    "subtitle": "Bulk location upload from XLSX or CSV, validated before anything is saved.",
    "templateHelp": "Download the template, fill it in and upload it. code is required and "
                     "unique per company; roles accepts several values separated by commas.",
    "itemsTitle": "Locations in the file",
    "columns": {
        "type": "Type", "roles": "Roles", "zone": "Zone", "coordinates": "Coordinates",
    },
})
UPDATES["masters"]["es"]["locations"] = {"import": _LOC_ES}
UPDATES["masters"]["en"]["locations"] = {"import": _LOC_EN}

_CARR_ES, _CARR_EN = _generic_import_strings("transportistas", "los", "carriers", "carrier")
_CARR_ES.update({
    "title": "Importar transportistas",
    "subtitle": "Carga masiva de transportistas desde XLSX o CSV, validada antes de guardar nada.",
    "templateHelp": "Descarga la plantilla, complétala y súbela. code es obligatorio y único "
                     "por empresa; taxIdType y taxIdValue juntos también deben ser únicos.",
    "itemsTitle": "Transportistas en el archivo",
    "columns": {"businessName": "Razón social", "taxId": "Documento", "contact": "Contacto"},
})
_CARR_EN.update({
    "title": "Import carriers",
    "subtitle": "Bulk carrier upload from XLSX or CSV, validated before anything is saved.",
    "templateHelp": "Download the template, fill it in and upload it. code is required and "
                     "unique per company; taxIdType and taxIdValue together must be unique too.",
    "itemsTitle": "Carriers in the file",
    "columns": {"businessName": "Business name", "taxId": "Tax id", "contact": "Contact"},
})
UPDATES["fleet"]["es"]["carriers"] = {"import": _CARR_ES}
UPDATES["fleet"]["en"]["carriers"] = {"import": _CARR_EN}

_VT_ES, _VT_EN = _generic_import_strings("tipos de vehículo", "los", "vehicle types", "vehicle type")
_VT_ES.update({
    "title": "Importar tipos de vehículo",
    "subtitle": "Carga masiva de tipos de vehículo desde XLSX o CSV, validada antes de guardar nada.",
    "templateHelp": "Descarga la plantilla, complétala y súbela. Las unidades son explícitas: "
                     "kilogramos, metros cúbicos, metros, Celsius. code es único por empresa.",
    "itemsTitle": "Tipos de vehículo en el archivo",
    "columns": {"capacity": "Capacidad", "dimensions": "Dimensiones", "temperature": "Temperatura"},
})
_VT_EN.update({
    "title": "Import vehicle types",
    "subtitle": "Bulk vehicle type upload from XLSX or CSV, validated before anything is saved.",
    "templateHelp": "Download the template, fill it in and upload it. Units are explicit: "
                     "kilograms, cubic meters, meters, Celsius. code is unique per company.",
    "itemsTitle": "Vehicle types in the file",
    "columns": {"capacity": "Capacity", "dimensions": "Dimensions", "temperature": "Temperature"},
})
UPDATES["fleet"]["es"]["vehicleTypes"] = {"import": _VT_ES}
UPDATES["fleet"]["en"]["vehicleTypes"] = {"import": _VT_EN}

_VEH_ES, _VEH_EN = _generic_import_strings("vehículos", "los", "vehicles", "vehicle")
_VEH_ES.update({
    "title": "Importar vehículos",
    "subtitle": "Carga masiva de vehículos desde XLSX o CSV, validada antes de guardar nada.",
    "templateHelp": "Descarga la plantilla, complétala y súbela. vehicleTypeCode debe existir "
                     "en esta empresa; carrierCode es opcional (vacío = flota propia).",
    "itemsTitle": "Vehículos en el archivo",
    "columns": {"plate": "Placa", "carrier": "Transportista", "type": "Tipo", "status": "Estado"},
})
_VEH_EN.update({
    "title": "Import vehicles",
    "subtitle": "Bulk vehicle upload from XLSX or CSV, validated before anything is saved.",
    "templateHelp": "Download the template, fill it in and upload it. vehicleTypeCode must "
                     "exist in this company; carrierCode is optional (blank = owned fleet).",
    "itemsTitle": "Vehicles in the file",
    "columns": {"plate": "Plate", "carrier": "Carrier", "type": "Type", "status": "Status"},
})
UPDATES["fleet"]["es"]["vehicles"] = {"import": _VEH_ES}
UPDATES["fleet"]["en"]["vehicles"] = {"import": _VEH_EN}


def main():
    for name, langs in UPDATES.items():
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), langs[lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
