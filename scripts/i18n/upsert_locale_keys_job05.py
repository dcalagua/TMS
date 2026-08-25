# -*- coding: utf-8 -*-
"""Job 05 (Proof of Delivery & Delivery Result) locale additions.

Adds the strings the trip workspace needs once a stop stopped being the end of the story:

  * `statuses.deliveryResult.*`        - the five values of the backend's DeliveryResult;
  * `statuses.evidenceType.*`          - SIGNATURE / PHOTO / DOCUMENT;
  * `statuses.transportEventType.DELIVERY_RECORDED` - the timeline entry V28 adds;
  * `trips.workspace.deliveries.*`     - recording and correcting one order's outcome;
  * `trips.workspace.evidence.*`       - attaching a signature, a photo or a document;
  * `trips.workspace.toasts.delivery*` - the two new confirmations.

The enum values themselves are contract and are never translated - only these labels are, and
`enums.test.ts` fails if any value ever lacks one in either language.

Wording notes that are product decisions, not translation choices:

  * `stopExecutionStatus.COMPLETED` stays "Atendida"/"Served" and `deliveryResult.DELIVERED` is
    "Entregado"/"Delivered". Job 04's note explained why the first could not say "delivered" -
    there was no delivery model. There is one now, and the two labels must stay different words,
    because they are still two different facts: the vehicle served the destination, and the goods
    of one order changed hands. A stop can be served with one of its orders refused;
  * "No intentada"/"Not attempted" is not a failure and is not coloured as one. Nothing went wrong
    with the goods - the stop was not served, and the stop's own badge already says so;
  * `deliveries.notRecorded` is "Sin registrar"/"Not recorded" and never "Pendiente"/"Pending".
    There is no pending result in the model: the absence of a record is the state, and a label
    that named it would invite somebody to add the value;
  * `evidence.type.SIGNATURE` is "Firma"/"Signature" with no claim of legal validity anywhere in
    the copy. TMS stores a captured image; it makes no cryptographic claim about who drew it, and
    a label saying "firma digital" would be the screen making one;
  * `deliveries.receiverDocumentHelp` says the field is optional and why it exists. It is personal
    data, and an operator should know it is kept to settle a dispute rather than because a form
    asked for it.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job05.py
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
            "deliveryResult": {
                "DELIVERED": "Entregado",
                "PARTIAL": "Entrega parcial",
                "REJECTED": "Rechazado",
                "FAILED": "Entrega fallida",
                "NOT_ATTEMPTED": "No intentada",
            },
            "evidenceType": {
                "SIGNATURE": "Firma",
                "PHOTO": "Foto",
                "DOCUMENT": "Documento",
            },
            "transportEventType": {
                "DELIVERY_RECORDED": "Entrega registrada",
            },
        },
        "en": {
            "deliveryResult": {
                "DELIVERED": "Delivered",
                "PARTIAL": "Partial delivery",
                "REJECTED": "Rejected",
                "FAILED": "Delivery failed",
                "NOT_ATTEMPTED": "Not attempted",
            },
            "evidenceType": {
                "SIGNATURE": "Signature",
                "PHOTO": "Photo",
                "DOCUMENT": "Document",
            },
            "transportEventType": {
                "DELIVERY_RECORDED": "Delivery recorded",
            },
        },
    },
    "trips": {
        "es": {
            "workspace": {
                "deliveries": {
                    "notRecorded": "Sin registrar",
                    "record": "Registrar entrega",
                    "correct": "Corregir",
                    "attach": "Adjuntar",
                    "recordTitle": "Registrar la entrega",
                    "correctTitle": "Corregir la entrega",
                    "subtitle": "Pedido {{order}} en la parada {{stop}}.",
                    "submit": "Guardar entrega",
                    "result": "Resultado",
                    "deliveredAt": "Fecha y hora de entrega",
                    "deliveredAtHelp": (
                        "Cuándo se entregó la mercancía, no cuándo se registra. "
                        "El servidor rechaza una hora futura o anterior a la llegada."
                    ),
                    "deliveredAtRequired": "Indica cuándo cambió de manos la mercancía.",
                    "receiverName": "Recibido por",
                    "receiverDocument": "Documento de identidad",
                    "receiverDocumentHelp": (
                        "Opcional. Solo se guarda para poder resolver una entrega en disputa."
                    ),
                    "receivedBy": "Recibido por {{name}}",
                    "notes": "Observaciones",
                    "notesHelp": (
                        "Obligatorio cuando la entrega fue parcial, rechazada o fallida: "
                        "es lo que se consulta cuando el cliente reclama."
                    ),
                    "notesRequired": "Explica qué ocurrió con la mercancía.",
                },
                "evidence": {
                    "title": "Adjuntar evidencia",
                    "subtitle": "Prueba de entrega del pedido {{order}}.",
                    "submit": "Adjuntar",
                    "type": "Tipo",
                    "file": "Archivo",
                    "fileHelp": "Imagen o PDF. El archivo solo es accesible desde el TMS autenticado.",
                    "fileRequired": "Selecciona un archivo.",
                    "capturedAt": "Fecha de captura",
                    "capturedAtHelp": "Opcional, si la foto o la firma se tomó en otro momento.",
                },
                "toasts": {
                    "deliveryRecorded": "Entrega registrada",
                    "evidenceAttached": "Evidencia adjuntada",
                },
            },
        },
        "en": {
            "workspace": {
                "deliveries": {
                    "notRecorded": "Not recorded",
                    "record": "Record delivery",
                    "correct": "Correct",
                    "attach": "Attach",
                    "recordTitle": "Record the delivery",
                    "correctTitle": "Correct the delivery",
                    "subtitle": "Order {{order}} at stop {{stop}}.",
                    "submit": "Save delivery",
                    "result": "Result",
                    "deliveredAt": "Delivered at",
                    "deliveredAtHelp": (
                        "When the goods changed hands, not when this is typed. "
                        "The server refuses a future time, or one before the vehicle arrived."
                    ),
                    "deliveredAtRequired": "Say when the goods changed hands.",
                    "receiverName": "Received by",
                    "receiverDocument": "Identity document",
                    "receiverDocumentHelp": (
                        "Optional. Kept only so a disputed delivery can be settled."
                    ),
                    "receivedBy": "Received by {{name}}",
                    "notes": "Notes",
                    "notesHelp": (
                        "Required for a partial, rejected or failed delivery: "
                        "it is what gets read when the customer calls."
                    ),
                    "notesRequired": "Explain what happened to the goods.",
                },
                "evidence": {
                    "title": "Attach evidence",
                    "subtitle": "Proof of delivery for order {{order}}.",
                    "submit": "Attach",
                    "type": "Type",
                    "file": "File",
                    "fileHelp": "An image or a PDF. The file is only reachable from the authenticated TMS.",
                    "fileRequired": "Choose a file.",
                    "capturedAt": "Captured at",
                    "capturedAtHelp": "Optional, when the photo or signature was taken at another time.",
                },
                "toasts": {
                    "deliveryRecorded": "Delivery recorded",
                    "evidenceAttached": "Evidence attached",
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
