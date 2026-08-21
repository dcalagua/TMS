# -*- coding: utf-8 -*-
"""Job 06 (Tracking Provider Abstraction) locale additions.

Adds the strings the trip workspace needs to answer "where is it":

  * `trips.workspace.sections.tracking` - the new card's heading;
  * `trips.workspace.tracking.*`        - the five states that card can be in.

Wording notes that are product decisions, not translation choices:

  * the five states are five different sentences on purpose. "No position" has several causes and
    a dispatcher does something different about each: the trip has not left (nothing to do), this
    deployment has no feed at all (somebody's job, not today's), or there is a feed that has said
    nothing about this shipment (the one worth a phone call). One shared "Sin datos" would collapse
    all three into the least useful of them;
  * `noProvider` says tracking is "not configured" and never "not available". The first is a
    deployment fact somebody can change; the second reads like an outage and generates a ticket;
  * `age` is "hace N min" / "N min ago" and is shown beside the absolute time, never instead of it.
    The relative form is what a person reads at a glance; the absolute one is what they quote on
    the phone, and a card that only had the first would force a mental subtraction;
  * `source` names the feed ("via acme-telematics"). A position is a claim by somebody, and a
    screen that showed coordinates with no attribution would present a vendor's measurement as
    TMS's own statement;
  * nothing here says "en tiempo real" / "real time", in any state. TMS stores at most one point
    per configured interval and the feed itself buffers; a label promising real time would be the
    UI making a claim the architecture deliberately does not.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job06.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


UPDATES = {
    "trips": {
        "es": {
            "workspace": {
                "sections": {
                    "tracking": "Ubicación",
                },
                "tracking": {
                    "loading": "Consultando la ubicación...",
                    "failed": "No se pudo consultar la ubicación. El resto del viaje sí está actualizado.",
                    "notOnTheRoad": "El viaje todavía no ha salido, así que no hay posiciones que mostrar.",
                    "noProvider": "Esta instalación no tiene un proveedor de seguimiento configurado.",
                    "noneReported": "El proveedor no ha reportado ninguna posición de este viaje.",
                    "openInMaps": "Ver en el mapa",
                    "reportedAt": "Reportada el {{at}}",
                    "age": "hace {{minutes}} min",
                    "ageJustNow": "hace menos de un minuto",
                    "speed": "{{speed}} km/h",
                    "source": "vía {{provider}}",
                    "trackPoints_one": "{{count}} punto en el recorrido reciente",
                    "trackPoints_other": "{{count}} puntos en el recorrido reciente",
                },
            },
        },
        "en": {
            "workspace": {
                "sections": {
                    "tracking": "Location",
                },
                "tracking": {
                    "loading": "Checking the location...",
                    "failed": "The location could not be checked. The rest of the trip is up to date.",
                    "notOnTheRoad": "The trip has not left yet, so there are no positions to show.",
                    "noProvider": "This installation has no tracking provider configured.",
                    "noneReported": "The provider has reported no position for this trip.",
                    "openInMaps": "Open in maps",
                    "reportedAt": "Reported at {{at}}",
                    "age": "{{minutes}} min ago",
                    "ageJustNow": "less than a minute ago",
                    "speed": "{{speed}} km/h",
                    "source": "via {{provider}}",
                    "trackPoints_one": "{{count}} point in the recent track",
                    "trackPoints_other": "{{count}} points in the recent track",
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
