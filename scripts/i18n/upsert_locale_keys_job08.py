# -*- coding: utf-8 -*-
"""Job 08 (Carrier Tendering V1) locale additions.

Adds the strings the trip workspace needs to answer "has the carrier agreed to run this":

  * `trips.tender.*`                    - the card, its actions and its confirmations;
  * `statuses.tenderStatus.*`           - the six lifecycle states;
  * `statuses.tenderResponseSource.*`   - who answered;
  * `statuses.transportEventType.TENDER_*` - the five new timeline entries.

Wording notes that are product decisions, not translation choices:

  * `tenderStatus.SENT` is "Esperando respuesta" / "Awaiting reply", not "Enviado" / "Sent". The
    state a dispatcher cares about is not that we pressed a button, it is that nobody has answered
    yet - and it is the state that quietly turns into a problem if the truck has to leave anyway;
  * `tenderStatus.CANCELLED` is "Retirado" / "Withdrawn". "Cancelado" would read as the *shipment*
    being cancelled, which is a different and much bigger fact on the same screen;
  * `tenderResponseSource` is two sentences and not two nouns: "Registrado por nosotros" /
    "Confirmado por el transportista". The distinction is evidentiary - whose word this is - and a
    label reading "Operador" / "Integracion" would make it look like a technical detail;
  * the accept and reject actions are "Registrar aceptacion" / "Record acceptance", never
    "Aceptar". The person clicking is not accepting anything; they are writing down what the
    carrier said on the phone, and a verb that hid that would make the audit trail read as though
    the shipper had answered its own offer;
  * `notOfferable` explains *when* a shipment can be offered rather than saying "no disponible".
    The window - confirmed, and not yet departed - is a rule somebody has to learn once, and the
    empty state is the cheapest place to teach it;
  * `form.intro` names the carrier the offer will go to and says it cannot be changed here. There
    is no carrier picker (the shipment's vehicle decides it), and an unexplained absence would read
    as a missing feature rather than as the model it is - see docs/domain/CARRIER_TENDERING_V1.md;
  * nothing anywhere says "licitacion" / "bidding". TMS offers one load to one carrier at a time;
    a word suggesting a competitive process would promise a product that does not exist.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job08.py
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
            "tenderStatus": {
                "DRAFT": "Borrador",
                "SENT": "Esperando respuesta",
                "ACCEPTED": "Aceptada",
                "REJECTED": "Rechazada",
                "EXPIRED": "Vencida",
                "CANCELLED": "Retirada",
            },
            "tenderResponseSource": {
                "OPERATOR": "Registrado por nosotros",
                "INTEGRATION": "Confirmado por el transportista",
            },
            "transportEventType": {
                "TENDER_SENT": "Oferta enviada al transportista",
                "TENDER_ACCEPTED": "Oferta aceptada",
                "TENDER_REJECTED": "Oferta rechazada",
                "TENDER_EXPIRED": "Oferta vencida",
                "TENDER_CANCELLED": "Oferta retirada",
            },
        },
        "en": {
            "tenderStatus": {
                "DRAFT": "Draft",
                "SENT": "Awaiting reply",
                "ACCEPTED": "Accepted",
                "REJECTED": "Rejected",
                "EXPIRED": "Expired",
                "CANCELLED": "Withdrawn",
            },
            "tenderResponseSource": {
                "OPERATOR": "Recorded by us",
                "INTEGRATION": "Confirmed by the carrier",
            },
            "transportEventType": {
                "TENDER_SENT": "Offered to the carrier",
                "TENDER_ACCEPTED": "Offer accepted",
                "TENDER_REJECTED": "Offer rejected",
                "TENDER_EXPIRED": "Offer expired",
                "TENDER_CANCELLED": "Offer withdrawn",
            },
        },
    },
    "trips": {
        "es": {
            "tender": {
                "title": "Oferta al transportista",
                "none": "Este viaje todavía no se ha ofertado a su transportista.",
                "notOfferable": "Un viaje solo se puede ofertar mientras está confirmado y no ha salido.",
                "attempt": "Intento {{number}}",
                "sentAt": "Enviada el {{at}}",
                "expiresAt": "Vence el {{at}}",
                "acceptedAt": "Aceptada el {{at}}",
                "rejectedAt": "Rechazada el {{at}}",
                "fields": {
                    "reason": "Motivo",
                },
                "actions": {
                    "offer": "Ofertar al transportista",
                    "offerAgain": "Volver a ofertar",
                    "send": "Enviar oferta",
                    "accept": "Registrar aceptación",
                    "reject": "Registrar rechazo",
                    "withdraw": "Retirar oferta",
                },
                "confirm": {
                    "sendTitle": "¿Enviar la oferta?",
                    "sendText": "{{carrier}} podrá verla y responderla. Después ya no se pueden cambiar sus condiciones.",
                    "acceptTitle": "¿Registrar la aceptación?",
                    "acceptText": "Quedará registrado que {{carrier}} aceptó el viaje. Es la respuesta definitiva de este intento.",
                    "rejectTitle": "¿Registrar el rechazo?",
                    "rejectText": "Indica el motivo que dio el transportista: es lo que necesita el planificador para decidir qué hacer.",
                    "withdrawTitle": "¿Retirar la oferta?",
                    "withdrawText": "Indica por qué se retira. El transportista dejará de poder responderla.",
                    "reasonRequired": "El motivo es obligatorio.",
                },
                "notify": {
                    "sent": "Oferta enviada",
                    "accepted": "Aceptación registrada",
                    "rejected": "Rechazo registrado",
                    "withdrawn": "Oferta retirada",
                },
                "form": {
                    "title": "Ofertar el viaje",
                    "editTitle": "Editar la oferta",
                    "intro": "La oferta va al transportista del vehículo planificado y no se puede cambiar aquí. Todo lo demás es opcional.",
                    "offeredAmount": "Importe ofrecido",
                    "offeredAmountHelp": "Déjalo vacío si se factura por tarifa acordada.",
                    "currency": "Moneda",
                    "currencyHelp": "Tres letras, ISO 4217 (PEN, USD, EUR).",
                    "expiresAt": "Responder antes de",
                    "expiresAtHelp": "Opcional. Después de esta hora la oferta ya no se puede aceptar.",
                    "notes": "Instrucciones",
                    "notesHelp": "Lo que el transportista necesita saber: hora de carga, puerta, equipamiento.",
                },
            },
        },
        "en": {
            "tender": {
                "title": "Carrier tender",
                "none": "This shipment has not been offered to its carrier yet.",
                "notOfferable": "A shipment can only be offered while it is confirmed and has not left.",
                "attempt": "Attempt {{number}}",
                "sentAt": "Sent {{at}}",
                "expiresAt": "Expires {{at}}",
                "acceptedAt": "Accepted {{at}}",
                "rejectedAt": "Rejected {{at}}",
                "fields": {
                    "reason": "Reason",
                },
                "actions": {
                    "offer": "Offer to the carrier",
                    "offerAgain": "Offer again",
                    "send": "Send offer",
                    "accept": "Record acceptance",
                    "reject": "Record refusal",
                    "withdraw": "Withdraw offer",
                },
                "confirm": {
                    "sendTitle": "Send the offer?",
                    "sendText": "{{carrier}} will be able to see it and answer. Its terms cannot be changed afterwards.",
                    "acceptTitle": "Record the acceptance?",
                    "acceptText": "It will be recorded that {{carrier}} accepted the shipment. This is the final answer for this attempt.",
                    "rejectTitle": "Record the refusal?",
                    "rejectText": "Give the reason the carrier gave: it is what the planner needs in order to decide what to do next.",
                    "withdrawTitle": "Withdraw the offer?",
                    "withdrawText": "Say why it is being withdrawn. The carrier will no longer be able to answer it.",
                    "reasonRequired": "A reason is required.",
                },
                "notify": {
                    "sent": "Offer sent",
                    "accepted": "Acceptance recorded",
                    "rejected": "Refusal recorded",
                    "withdrawn": "Offer withdrawn",
                },
                "form": {
                    "title": "Offer the shipment",
                    "editTitle": "Edit the offer",
                    "intro": "The offer goes to the carrier of the planned vehicle and cannot be changed here. Everything else is optional.",
                    "offeredAmount": "Offered amount",
                    "offeredAmountHelp": "Leave it empty when the shipment is billed under an agreed rate.",
                    "currency": "Currency",
                    "currencyHelp": "Three letters, ISO 4217 (PEN, USD, EUR).",
                    "expiresAt": "Answer before",
                    "expiresAtHelp": "Optional. After this moment the offer can no longer be accepted.",
                    "notes": "Instructions",
                    "notesHelp": "What the carrier needs to know: loading time, gate, equipment.",
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
