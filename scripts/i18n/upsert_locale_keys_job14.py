# -*- coding: utf-8 -*-
"""Job 14 (global UX/product polish) locale additions.

One key, onto one existing namespace:

  * `trips.workspace.timeline.failed` - what the trip timeline says when its request fails.

Why it exists at all. `TripTimeline` was given `events` and `loading` and nothing else, so
`TripWorkspacePage` passed `eventsQuery.data ?? []` and a failed request arrived as an empty
array - rendering `timeline.empty`, "Todavia no se ha registrado nada en este viaje". For an
append-only execution log that is not a cosmetic problem: it is the screen asserting, as a fact,
that a trip has no history, when what actually happened is that TMS could not read it. A
dispatcher checking whether a driver reported an arrival would conclude they had not.

Wording notes that are product decisions, not translation choices:

  * the sentence names the *request* as what failed and says the log itself is intact. This is
    the same move the tracking card makes with `tracking.failed` ("El resto del viaje si esta
    actualizado"), and for the same reason: one broken card must not leave an operator doubting
    the values in the cards beside it.
  * it never reuses the word "vacio"/"empty". The whole point of the key is to be the sentence
    `timeline.empty` is not.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job14.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


# Deep-merged onto what is already there: namespace -> language -> partial tree.
EXISTING_NAMESPACE_UPDATES = {
    "trips": {
        "es": {
            "workspace": {
                "timeline": {
                    "failed": "No se pudo cargar la línea de tiempo. Lo que ya ocurrió en el viaje sigue registrado.",
                },
            },
        },
        "en": {
            "workspace": {
                "timeline": {
                    "failed": "The timeline could not be loaded. Everything that has already happened on the trip is still recorded.",
                },
            },
        },
    },
}


def main():
    for name in sorted(EXISTING_NAMESPACE_UPDATES):
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), EXISTING_NAMESPACE_UPDATES[name][lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
