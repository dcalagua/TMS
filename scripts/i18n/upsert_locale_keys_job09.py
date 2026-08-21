# -*- coding: utf-8 -*-
"""Job 09 (Control Tower V1) locale additions.

Writes one brand-new namespace and layers two keys onto existing ones:

  * `controlTower.*`                - the whole screen: counters, panels, table, filters;
  * `statuses.departureTimeliness.*` - the six departure verdicts the backend can send;
  * `navigation.items.controlTower`  - the sidebar entry.

`controlTower` is a namespace of its own rather than a section of `trips`. The two screens answer
different questions - "what is this shipment doing" against "what is the day doing" - and a
translator working on one needs the other's wording out of the way.

Wording notes that are product decisions, not translation choices:

  * `departureTimeliness.OVERDUE` is "Salida vencida" / "Departure overdue", never "Retrasado" /
    "Delayed". A truck already out and running behind and a truck still in the yard that should
    have left are two different phone calls, and the enum exists precisely so the screen does not
    have to merge them into one word. `LATE` is "Salio tarde" / "Departed late" for the same
    reason: it names the fact, not a severity.
  * `departureTimeliness.NOT_SCHEDULED` is "Sin salida planificada" and is shown, not hidden. A
    trip planned for today with no departure time is a planning gap somebody has to close.
  * `kpi.unplannedDenied` replaces the number when the caller does not hold `orders.order:read`.
    Never "0": the backlog was not looked at, and calling it empty would be a claim the response
    is not entitled to make.
  * `scopeNote` is a full sentence and is not shortened. It states the one thing about this screen
    an operator could otherwise get wrong without noticing - the counters cover the whole day and
    only the table narrows - and a caption nobody can misread is worth the line it costs.
  * `wholeDay` labels the counter strip for the same reason. Read together they make the scope a
    stated design rather than a discovery made during an incident.
  * nothing anywhere says "alerta" / "alert". The tower is read; it pages nobody, and a word
    promising notifications would promise a product that does not exist
    (docs/domain/CONTROL_TOWER_V1.md section 8).

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job09.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


# Written from scratch: namespace -> language -> the whole tree.
NEW_NAMESPACES = {
    "controlTower": {
        "es": {
            "arrivedAt": "Llegó {{time}}",
            "board": {
                "emptyMessage": "No hay viajes para este día con los filtros aplicados.",
                "emptyTitle": "Sin viajes",
                "title": "Viajes del día",
            },
            "columns": {
                "capacity": "Ocupación",
                "carrier": "Transportista",
                "departure": "Salida",
                "exceptions": "Incidencias",
                "nextStop": "Próxima parada",
                "progress": "Paradas",
                "shipment": "Envío",
                "status": "Estado",
                "vehicle": "Vehículo",
            },
            "delayMinutes": "+{{minutes}} min",
            "description": "El día del transporte: qué sale, qué va tarde y qué sigue sin resolverse.",
            "dueBy": "Hasta {{time}}",
            "filters": {
                "allCarriers": "Todos los transportistas",
                "allOrigins": "Todos los orígenes",
                "allStatuses": "Todos los estados",
                "carrier": "Transportista",
                "date": "Día",
                "origin": "Origen",
                "status": "Estado",
            },
            "generatedAt": "Datos de las {{time}}",
            "kpi": {
                "completed": "Completados",
                "completedHint": "Viajes cerrados en el día",
                "delayed": "Con retraso",
                "delayedHint": "{{late}} salieron tarde, {{overdue}} aún no salen",
                "draft": "Sin confirmar",
                "draftHint": "Planificados para hoy y todavía en borrador",
                "exceptions": "Incidencias abiertas",
                "exceptionsHint": "Problemas del día que nadie ha cerrado",
                "inTransit": "En ruta",
                "inTransitHint": "Vehículos fuera en este momento",
                "lateStops": "Paradas demoradas",
                "lateStopsHint": "De {{outstanding}} pendientes en ruta",
                "scheduled": "Por salir",
                "scheduledHint": "Confirmados o listos, todavía en patio",
                "unplanned": "Pedidos sin planificar",
                "unplannedDenied": "Requiere permiso de lectura de pedidos",
                "unplannedHint": "Con fecha de servicio de este día",
            },
            "noWindow": "Sin ventana",
            "open": "Abrir",
            "panels": {
                "exceptions": {
                    "empty": "Ninguna incidencia abierta.",
                    "more": "Mostrando {{shown}} de {{total}}.",
                    "title": "Incidencias abiertas",
                },
                "stops": {
                    "empty": "Ninguna parada pendiente en ruta.",
                    "more": "Mostrando {{shown}} de {{total}}.",
                    "title": "Paradas pendientes",
                },
                "workload": {
                    "empty": "Ningún viaje con ocupación conocida.",
                    "hint": "Por la dimensión de capacidad más ajustada",
                    "title": "Vehículos más cargados",
                },
            },
            "pastWindowMinutes": "{{minutes}} min fuera de ventana",
            "refresh": "Actualizar",
            "scopeNote": (
                "Los indicadores y los paneles cubren el día completo. Origen, transportista y "
                "estado acotan únicamente la tabla de abajo, para que un filtro no pueda esconder "
                "un problema del resumen."
            ),
            "stopProgress": "{{done}} de {{total}}",
            "stopsLate": "{{value}} fuera de ventana",
            "title": "Torre de control",
            "wholeDay": "El día completo",
        },
        "en": {
            "arrivedAt": "Arrived {{time}}",
            "board": {
                "emptyMessage": "No trips for this day with the filters applied.",
                "emptyTitle": "No trips",
                "title": "The day's trips",
            },
            "columns": {
                "capacity": "Utilisation",
                "carrier": "Carrier",
                "departure": "Departure",
                "exceptions": "Exceptions",
                "nextStop": "Next stop",
                "progress": "Stops",
                "shipment": "Shipment",
                "status": "Status",
                "vehicle": "Vehicle",
            },
            "delayMinutes": "+{{minutes}} min",
            "description": (
                "The transport day: what is leaving, what is running late and what nobody has "
                "closed out."
            ),
            "dueBy": "By {{time}}",
            "filters": {
                "allCarriers": "All carriers",
                "allOrigins": "All origins",
                "allStatuses": "All statuses",
                "carrier": "Carrier",
                "date": "Day",
                "origin": "Origin",
                "status": "Status",
            },
            "generatedAt": "Data as of {{time}}",
            "kpi": {
                "completed": "Completed",
                "completedHint": "Trips closed out today",
                "delayed": "Running late",
                "delayedHint": "{{late}} left late, {{overdue}} have not left",
                "draft": "Unconfirmed",
                "draftHint": "Planned for today and still a draft",
                "exceptions": "Open exceptions",
                "exceptionsHint": "Today's problems nobody has closed",
                "inTransit": "On the road",
                "inTransitHint": "Vehicles out right now",
                "lateStops": "Stops past window",
                "lateStopsHint": "Of {{outstanding}} outstanding out there",
                "scheduled": "Still to leave",
                "scheduledHint": "Confirmed or loaded, still in the yard",
                "unplanned": "Unplanned orders",
                "unplannedDenied": "Needs the order read permission",
                "unplannedHint": "With this service date",
            },
            "noWindow": "No window",
            "open": "Open",
            "panels": {
                "exceptions": {
                    "empty": "No open exceptions.",
                    "more": "Showing {{shown}} of {{total}}.",
                    "title": "Open exceptions",
                },
                "stops": {
                    "empty": "No outstanding stops on the road.",
                    "more": "Showing {{shown}} of {{total}}.",
                    "title": "Outstanding stops",
                },
                "workload": {
                    "empty": "No trip with a known utilisation.",
                    "hint": "By the tightest capacity dimension",
                    "title": "Fullest vehicles",
                },
            },
            "pastWindowMinutes": "{{minutes}} min past window",
            "refresh": "Refresh",
            "scopeNote": (
                "The counters and panels cover the whole day. Origin, carrier and status narrow "
                "only the table below, so a filter can never hide a problem from the summary."
            ),
            "stopProgress": "{{done}} of {{total}}",
            "stopsLate": "{{value}} past window",
            "title": "Control tower",
            "wholeDay": "The whole day",
        },
    },
}


# Layered over what is already in the file; only the branches named here are touched.
UPDATES = {
    # The enum lives in `statuses` with every other value the API transports, because
    # `enums.test.ts` walks that bundle and fails if a value the client can receive has no label -
    # which is what stops NOT_SCHEDULED reaching an operator.
    "statuses": {
        "es": {
            "departureTimeliness": {
                "LATE": "Salió tarde",
                "NOT_APPLICABLE": "No aplica",
                "NOT_SCHEDULED": "Sin salida planificada",
                "ON_TIME": "A tiempo",
                "OVERDUE": "Salida vencida",
                "SCHEDULED": "Programado",
            },
        },
        "en": {
            "departureTimeliness": {
                "LATE": "Departed late",
                "NOT_APPLICABLE": "Not applicable",
                "NOT_SCHEDULED": "No planned departure",
                "ON_TIME": "On time",
                "OVERDUE": "Departure overdue",
                "SCHEDULED": "Scheduled",
            },
        },
    },
    # An item and no group: the control tower stands beside the dashboard rather than inside a
    # module group, because it owns nothing and describes everything - see navConfig.
    "navigation": {
        "es": {"items": {"controlTower": "Torre de control"}},
        "en": {"items": {"controlTower": "Control tower"}},
    },
}


def main():
    for name in sorted(NEW_NAMESPACES):
        write_namespace(name, NEW_NAMESPACES[name]["es"], NEW_NAMESPACES[name]["en"])
    for name in sorted(UPDATES):
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), UPDATES[name][lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
