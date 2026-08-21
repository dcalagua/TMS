# -*- coding: utf-8 -*-
"""Job 04 (Stop Execution, Transport Events & Exceptions) locale additions.

Adds the strings the trip workspace needs once a stop became something a dispatcher works
rather than a line on a plan:

  * `statuses.stopExecutionStatus.*`   - the six values of the backend's StopExecutionStatus;
  * `statuses.transportEventType.*`    - the twelve entries a timeline can carry;
  * `statuses.tripExceptionType.*`     - the seven-value operational catalogue;
  * `statuses.tripExceptionStatus.*`   - OPEN / RESOLVED;
  * `trips.workspace.stops.*`          - the per-stop actual times and the five actions;
  * `trips.workspace.timeline.*`       - the day's log;
  * `trips.workspace.problems.*`       - reporting and closing an operational problem;
  * `trips.workspace.dialogs.resolve*` and the new toasts.

The enum values themselves are contract and are never translated - only these labels are, and
`enums.test.ts` fails if any value ever lacks one in either language.

Wording notes that are product decisions, not translation choices:

  * "Omitida"/"Skipped" and "No atendida"/"Not served" must stay visibly different words. They
    are two different facts - never attempted versus attempted and refused - and a UI that blurs
    them makes the delivery history unreadable;
  * `stopExecutionStatus.COMPLETED` is "Atendida"/"Served", not "Entregada"/"Delivered". TMS
    records that the vehicle served the destination, not what was handed over: there is no
    proof-of-delivery model (migration V27, "Deliberately NOT here"), and a label promising one
    would be the screen making a claim the database cannot support;
  * `problems.resolveNotesRequired` says why the note is mandatory rather than merely that it is.
    "Resolved" with no explanation records a click, not an outcome;
  * "incidencia" is used throughout the Spanish rather than "excepción": the latter reads as a
    programming error to an operator, and this catalogue is deliberately operational only.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job04.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


UPDATES = {
    "statuses": {
        "es": {
            "stopExecutionStatus": {
                # "Sin iniciar" and not "Pendiente": the trip's own lifecycle card already uses
                # "Pendiente" for a step that has not happened, and two different things wearing
                # one word on the same screen is how a dispatcher misreads it.
                "PENDING": "Sin iniciar",
                "ARRIVED": "En el punto",
                "IN_SERVICE": "En atención",
                "COMPLETED": "Atendida",
                "SKIPPED": "Omitida",
                "FAILED": "No atendida",
            },
            "transportEventType": {
                "TRIP_CONFIRMED": "Viaje confirmado",
                "TRIP_READY": "Listo para salir",
                "TRIP_DISPATCHED": "Salida",
                "TRIP_COMPLETED": "Viaje cerrado",
                "TRIP_CANCELLED": "Viaje cancelado",
                "ARRIVED_AT_STOP": "Llegada a la parada",
                "SERVICE_STARTED": "Inicio de atención",
                "STOP_COMPLETED": "Parada atendida",
                "STOP_SKIPPED": "Parada omitida",
                "STOP_FAILED": "Parada no atendida",
                "EXCEPTION_REPORTED": "Incidencia reportada",
                "EXCEPTION_RESOLVED": "Incidencia resuelta",
            },
            "tripExceptionType": {
                "TRAFFIC_DELAY": "Demora por tráfico",
                "VEHICLE_BREAKDOWN": "Avería del vehículo",
                "CUSTOMER_CLOSED": "Cliente cerrado",
                "DELIVERY_REJECTED": "Entrega rechazada",
                "ADDRESS_NOT_FOUND": "Dirección no encontrada",
                "DELIVERY_FAILED": "Entrega fallida",
                "OTHER": "Otra",
            },
            "tripExceptionStatus": {
                "OPEN": "Abierta",
                "RESOLVED": "Resuelta",
            },
        },
        "en": {
            "stopExecutionStatus": {
                "PENDING": "Not started",
                "ARRIVED": "Arrived",
                "IN_SERVICE": "In service",
                "COMPLETED": "Served",
                "SKIPPED": "Skipped",
                "FAILED": "Not served",
            },
            "transportEventType": {
                "TRIP_CONFIRMED": "Trip confirmed",
                "TRIP_READY": "Ready for dispatch",
                "TRIP_DISPATCHED": "Departed",
                "TRIP_COMPLETED": "Trip closed",
                "TRIP_CANCELLED": "Trip cancelled",
                "ARRIVED_AT_STOP": "Arrived at stop",
                "SERVICE_STARTED": "Service started",
                "STOP_COMPLETED": "Stop served",
                "STOP_SKIPPED": "Stop skipped",
                "STOP_FAILED": "Stop not served",
                "EXCEPTION_REPORTED": "Problem reported",
                "EXCEPTION_RESOLVED": "Problem resolved",
            },
            "tripExceptionType": {
                "TRAFFIC_DELAY": "Traffic delay",
                "VEHICLE_BREAKDOWN": "Vehicle breakdown",
                "CUSTOMER_CLOSED": "Customer closed",
                "DELIVERY_REJECTED": "Delivery rejected",
                "ADDRESS_NOT_FOUND": "Address not found",
                "DELIVERY_FAILED": "Delivery failed",
                "OTHER": "Other",
            },
            "tripExceptionStatus": {
                "OPEN": "Open",
                "RESOLVED": "Resolved",
            },
        },
    },
    "trips": {
        "es": {
            "workspace": {
                "dialogs": {
                    "resolveTitle": "¿Cerrar la incidencia?",
                    "resolveText": "Se cierra la incidencia «{{type}}». Queda en el viaje como historial.",
                    "resolveNotesLabel": "Qué se hizo",
                    "resolveNotesPlaceholder": "Se llamó al cliente, se reprogramó para mañana",
                    "resolveNotesRequired": (
                        "Escribe qué se hizo: «resuelta» por sí sola registra un clic, no un resultado."
                    ),
                },
                "sections": {
                    "problems": "Incidencias",
                    "timeline": "Línea de tiempo",
                },
                "stops": {
                    "arrived": "Llegada",
                    "serviceStarted": "Atención",
                    "departed": "Salida",
                    "dwell": "{{minutes}} min en la parada",
                    "openProblems": "{{count}} incidencia",
                    "openProblems_other": "{{count}} incidencias",
                    "actions": {
                        "arrive": "Llegada",
                        "startService": "Iniciar atención",
                        "complete": "Atendida",
                        "skip": "Omitir",
                        "fail": "No atendida",
                    },
                },
                "timeline": {
                    "loading": "Cargando la línea de tiempo…",
                    "empty": "Todavía no se ha registrado nada en este viaje.",
                    "atStop": "Parada {{sequence}} · {{name}}",
                    "unknownActor": "Desconocido",
                    "recordedLater": "registrado {{minutes}} min después",
                },
                "problems": {
                    "empty": "No hay incidencias en este viaje.",
                    "report": "Reportar incidencia",
                    "resolve": "Cerrar incidencia",
                    "resolution": "Resuelta",
                    "openCount": "{{count}} abierta",
                    "openCount_other": "{{count}} abiertas",
                    "wholeTrip": "Todo el viaje",
                    "type": "Motivo",
                    "stop": "Parada",
                    "stopHelp": (
                        "Déjalo en todo el viaje cuando la incidencia no sea de una entrega concreta."
                    ),
                    "stopRequired": "Este motivo es de una entrega: elige la parada donde ocurrió.",
                    "notes": "Qué pasó",
                    "notesHelp": "Obligatorio cuando el motivo es Otra, que por sí sola no dice nada.",
                    "notesRequired": "Describe qué pasó.",
                    "titles": {
                        "skip": "Omitir la parada",
                        "fail": "Parada no atendida",
                        "report": "Reportar una incidencia",
                    },
                    "subtitles": {
                        "skip": "La parada no se intentó. El motivo queda en el viaje.",
                        "fail": "Se intentó la parada y no se pudo atender. El motivo queda en el viaje.",
                        "report": "Una demora o una avería que todavía no cambió ninguna entrega.",
                    },
                    "submit": {
                        "skip": "Omitir la parada",
                        "fail": "Registrar como no atendida",
                        "report": "Reportar incidencia",
                    },
                },
                "toasts": {
                    "stopArrived": "Llegada registrada",
                    "stopInService": "Atención iniciada",
                    "stopCompleted": "Parada atendida",
                    "stopSkipped": "Parada omitida",
                    "stopFailed": "Parada registrada como no atendida",
                    "problemReported": "Incidencia reportada",
                    "problemResolved": "Incidencia cerrada",
                },
            },
        },
        "en": {
            "workspace": {
                "dialogs": {
                    "resolveTitle": "Close the problem?",
                    "resolveText": "Closing the problem “{{type}}”. It stays on the trip as history.",
                    "resolveNotesLabel": "What was done",
                    "resolveNotesPlaceholder": "Customer called, redelivery agreed for tomorrow",
                    "resolveNotesRequired": (
                        "Say what was done: \"resolved\" on its own records a click, not an outcome."
                    ),
                },
                "sections": {
                    "problems": "Problems",
                    "timeline": "Timeline",
                },
                "stops": {
                    "arrived": "Arrived",
                    "serviceStarted": "Service",
                    "departed": "Left",
                    "dwell": "{{minutes}} min at the stop",
                    "openProblems": "{{count}} problem",
                    "openProblems_other": "{{count}} problems",
                    "actions": {
                        "arrive": "Arrived",
                        "startService": "Start service",
                        "complete": "Served",
                        "skip": "Skip",
                        "fail": "Not served",
                    },
                },
                "timeline": {
                    "loading": "Loading the timeline…",
                    "empty": "Nothing has been recorded on this trip yet.",
                    "atStop": "Stop {{sequence}} · {{name}}",
                    "unknownActor": "Unknown",
                    "recordedLater": "recorded {{minutes}} min later",
                },
                "problems": {
                    "empty": "Nothing has gone wrong on this trip.",
                    "report": "Report a problem",
                    "resolve": "Close problem",
                    "resolution": "Resolved",
                    "openCount": "{{count}} open",
                    "openCount_other": "{{count}} open",
                    "wholeTrip": "Whole trip",
                    "type": "Reason",
                    "stop": "Stop",
                    "stopHelp": "Leave on the whole trip when the problem is not about one delivery.",
                    "stopRequired": "This reason is about a delivery: choose the stop it happened at.",
                    "notes": "What happened",
                    "notesHelp": "Required when the reason is Other, which says nothing on its own.",
                    "notesRequired": "Describe what happened.",
                    "titles": {
                        "skip": "Skip the stop",
                        "fail": "Stop not served",
                        "report": "Report a problem",
                    },
                    "subtitles": {
                        "skip": "The stop was never attempted. The reason is kept with the trip.",
                        "fail": (
                            "The stop was attempted and could not be served. "
                            "The reason is kept with the trip."
                        ),
                        "report": "A delay or a breakdown that has not changed any delivery yet.",
                    },
                    "submit": {
                        "skip": "Skip the stop",
                        "fail": "Record as not served",
                        "report": "Report problem",
                    },
                },
                "toasts": {
                    "stopArrived": "Arrival recorded",
                    "stopInService": "Service started",
                    "stopCompleted": "Stop served",
                    "stopSkipped": "Stop skipped",
                    "stopFailed": "Stop recorded as not served",
                    "problemReported": "Problem reported",
                    "problemResolved": "Problem closed",
                },
            },
        },
    },
}


def main():
    for name in sorted(UPDATES):
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), UPDATES[name][lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
