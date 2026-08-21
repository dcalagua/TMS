# -*- coding: utf-8 -*-
"""Job 07 (Planning/Shipment V2) locale additions, applied through the shared upsert helpers.

Kept as its own entry point rather than by rewriting `upsert_locale_keys.py`'s dicts: that file
records the previous job's additions and re-running it is how those are reproduced. This one adds
the shipment-header, route-suggestion and stop-sequence strings the Trip drawer and card now
render, and wires up the drawer strings that were still hard-coded English.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job07.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402

UPDATES = {
    "planning": {
        "es": {
            "card": {
                "route": "Ruta",
                "shipment": "Envío",
                "vehicleType": "Tipo",
            },
            "drawer": {
                "coordinatesMissing": "Sin coordenadas",
                "coordinatesMissingHint": "Este destino aún no tiene latitud y longitud, "
                                          "así que no puede ubicarse en el mapa.",
                "header": {
                    "carrier": "Transportista",
                    "departure": "Salida planificada",
                    "origin": "Origen",
                    "plan": "Corrida",
                    "planningDate": "Fecha de planificación",
                    "route": "Ruta",
                    "shipment": "Envío",
                    "title": "Datos del envío",
                    "vehicle": "Vehículo",
                    "vehicleType": "Tipo de vehículo",
                },
                "route": {
                    "apply": "Guardar la ruta",
                    "applySequence": "Reordenar los destinos según la ruta",
                    "applySequenceHelp": "Los destinos que la ruta no incluye se conservan al "
                                         "final: una ruta nunca quita ni agrega una parada.",
                    "clear": "Quitar la ruta",
                    "cleared": "Ruta quitada",
                    "error": "No se pudo guardar la ruta",
                    "none": "Sin ruta",
                    "saved": "Ruta guardada",
                    "subtitle": "Una ruta maestra es una sugerencia: define el orden habitual "
                                "del corredor, no qué destinos lleva este envío.",
                    "title": "Ruta sugerida",
                },
                "saving": "Guardando...",
                "vehicleSaved": "Vehículo guardado",
            },
        },
        "en": {
            "card": {
                "route": "Route",
                "shipment": "Shipment",
                "vehicleType": "Type",
            },
            "drawer": {
                "coordinatesMissing": "No coordinates",
                "coordinatesMissingHint": "This destination has no latitude and longitude yet, "
                                          "so it cannot be placed on the map.",
                "header": {
                    "carrier": "Carrier",
                    "departure": "Planned departure",
                    "origin": "Origin",
                    "plan": "Planning run",
                    "planningDate": "Planning date",
                    "route": "Route",
                    "shipment": "Shipment",
                    "title": "Shipment details",
                    "vehicle": "Vehicle",
                    "vehicleType": "Vehicle type",
                },
                "route": {
                    "apply": "Save the route",
                    "applySequence": "Reorder the stops to follow the route",
                    "applySequenceHelp": "Destinations the route does not name are kept, at the "
                                         "end: a route never adds or removes a stop.",
                    "clear": "Clear the route",
                    "cleared": "Route cleared",
                    "error": "Could not save the route",
                    "none": "No route",
                    "saved": "Route saved",
                    "subtitle": "A master route is a suggestion: it defines the corridor's usual "
                                "order, not which destinations this shipment carries.",
                    "title": "Suggested route",
                },
                "saving": "Saving...",
                "vehicleSaved": "Vehicle saved",
            },
        },
    },
}


def main():
    for name, langs in UPDATES.items():
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), langs[lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
