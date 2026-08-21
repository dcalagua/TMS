# -*- coding: utf-8 -*-
"""Job 10 (Alerts & Notifications V1) locale additions.

Writes one brand-new namespace and layers one key onto an existing one:

  * `notifications.*`             - the seven alert sentences, plus the two panel controls;
  * `navigation.notificationsUnread` - the bell's accessible name when the badge is showing.

`notifications` is a namespace of its own rather than a section of `navigation`. `navigation` owns
the control - its label, its empty state - and `notifications` owns the sentences, which a
translator needs to be able to read as a list of seven messages with nothing else in the way.

Wording notes that are product decisions, not translation choices:

  * every message is a full sentence ending in a full stop, and names the shipment. The bell is
    read out of context - the operator is on some other screen - so a fragment like "95 min tarde"
    would need the row above it to mean anything.
  * `DELIVERY_FAILED.title` is "El cliente no recibió todo" / "The customer did not get
    everything", never "Entrega fallida" / "Delivery failed". FAILED is also one of the five
    DeliveryResult values (statuses.deliveryResult.DELIVERY_FAILED), and this alert covers three of
    them - PARTIAL, REJECTED and FAILED. Reusing that word for the group would make the title
    contradict the `{{result}}` the same row interpolates.
  * `TRIP_DELAYED.title` is "Salió tarde" / "Departed late" and never "Retrasado" / "Delayed", for
    the reason job 09 gives about `departureTimeliness`: a truck that left late and a truck that
    should have left and has not are two different phone calls, and only the first has an alert.
  * `TENDER_EXPIRED` says "Nadie respondió" / "Nobody answered" rather than "Oferta vencida" alone.
    The deadline passing is not the news; the shipment still needing a carrier is.
  * `markAllRead` is "Marcar todo como leído" and not "Limpiar": nothing is deleted, and a verb
    that promised otherwise would be describing a feature the table has no DELETE grant for.
  * `resolved` is one word because it is a suffix on a timestamp line, not a status chip.
  * `notificationsUnread` interpolates `{{unread}}` and deliberately not `count`. `count` would put
    the string through i18next's plural machinery and require `_one`/`_other` forms for a value
    that is only ever read out by a screen reader.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job10.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


# Written from scratch: namespace -> language -> the whole tree.
NEW_NAMESPACES = {
    "notifications": {
        "es": {
            "markAllRead": "Marcar todo como leído",
            "resolved": "Resuelto",
            "types": {
                "DELIVERY_FAILED": {
                    "message": "Pedido {{orderNumber}} del envío {{shipmentNumber}}, parada {{stopSequence}}: {{result}}.",
                    "title": "El cliente no recibió todo",
                },
                "DRIVER_LICENSE_EXPIRING": {
                    "message": "La licencia de {{driverName}} vence el {{expiresOn}}. Va asignado al envío {{shipmentNumber}}.",
                    "title": "Licencia por vencer",
                },
                "EXCEPTION_OPENED": {
                    "message": "{{exceptionType}} en el envío {{shipmentNumber}}.",
                    "title": "Incidencia abierta",
                },
                "TENDER_EXPIRED": {
                    "message": "Nadie respondió el intento {{attempt}} del envío {{shipmentNumber}} antes del plazo.",
                    "title": "Oferta vencida sin respuesta",
                },
                "TENDER_REJECTED": {
                    "message": "El transportista rechazó el intento {{attempt}} del envío {{shipmentNumber}}.",
                    "title": "Oferta rechazada",
                },
                "TRIP_COMPLETED": {
                    "message": "El envío {{shipmentNumber}} quedó cerrado.",
                    "title": "Envío completado",
                },
                "TRIP_DELAYED": {
                    "message": "El envío {{shipmentNumber}} salió {{minutes}} minutos después de lo planificado.",
                    "title": "Salió tarde",
                },
            },
        },
        "en": {
            "markAllRead": "Mark everything as read",
            "resolved": "Resolved",
            "types": {
                "DELIVERY_FAILED": {
                    "message": "Order {{orderNumber}} on shipment {{shipmentNumber}}, stop {{stopSequence}}: {{result}}.",
                    "title": "The customer did not get everything",
                },
                "DRIVER_LICENSE_EXPIRING": {
                    "message": "{{driverName}}'s licence expires on {{expiresOn}}. They are assigned to shipment {{shipmentNumber}}.",
                    "title": "Licence about to expire",
                },
                "EXCEPTION_OPENED": {
                    "message": "{{exceptionType}} on shipment {{shipmentNumber}}.",
                    "title": "Problem opened",
                },
                "TENDER_EXPIRED": {
                    "message": "Nobody answered attempt {{attempt}} on shipment {{shipmentNumber}} before the deadline.",
                    "title": "Offer lapsed unanswered",
                },
                "TENDER_REJECTED": {
                    "message": "The carrier rejected attempt {{attempt}} on shipment {{shipmentNumber}}.",
                    "title": "Offer rejected",
                },
                "TRIP_COMPLETED": {
                    "message": "Shipment {{shipmentNumber}} is closed out.",
                    "title": "Shipment completed",
                },
                "TRIP_DELAYED": {
                    "message": "Shipment {{shipmentNumber}} left {{minutes}} minutes after its planned time.",
                    "title": "Departed late",
                },
            },
        },
    },
}

# Layered onto namespaces that already exist.
UPDATES = {
    "navigation": {
        "es": {"notificationsUnread": "Avisos ({{unread}} sin leer)"},
        "en": {"notificationsUnread": "Alerts ({{unread}} unread)"},
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
