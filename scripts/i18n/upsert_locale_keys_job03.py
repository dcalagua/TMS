# -*- coding: utf-8 -*-
"""Job 03 (Drivers & Trip Assignment) locale additions, applied through the shared upsert helpers.

Adds the strings the driver master and the trip driver assignment need:

  * `navigation.items.drivers` - the Fleet menu entry, placed after Vehicles;
  * `statuses.driverLicenseStatus.*` - the four values of the backend's DriverLicenseStatus. The
    values themselves are contract and are never translated; only these labels are;
  * `fleet.drivers.*` - the master screen and its drawer, mirroring `fleet.vehicles`;
  * `planning.trip.form.driver*` / `planning.drawer.*Driver*` - the planning board's picker;
  * `trips.workspace.fields.driver*` and the workspace's assign action.

Wording notes that are product decisions, not translation choices:

  * "Por vencer" / "Expiring soon" is a warning, never a refusal - only an expired licence blocks
    an assignment, and the label must not suggest otherwise;
  * "Sin registrar" / "Not recorded" is deliberately not "Sin vencimiento": the company simply has
    no date on file, which is a gap in the master rather than a licence that never expires;
  * the driver picker's empty option says "Sin conductor" rather than "Ninguno", because clearing
    it is a real state a dispatcher records ("the driver we had is off"), not the absence of a
    choice.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job03.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


UPDATES = {
    "navigation": {
        "es": {"items": {"drivers": "Conductores"}},
        "en": {"items": {"drivers": "Drivers"}},
    },
    "statuses": {
        "es": {
            "driverLicenseStatus": {
                "UNRECORDED": "Sin registrar",
                "VALID": "Vigente",
                "EXPIRING_SOON": "Por vencer",
                "EXPIRED": "Vencida",
            },
        },
        "en": {
            "driverLicenseStatus": {
                "UNRECORDED": "Not recorded",
                "VALID": "Valid",
                "EXPIRING_SOON": "Expiring soon",
                "EXPIRED": "Expired",
            },
        },
    },
    "fleet": {
        "es": {
            "drivers": {
                "title": "Conductores",
                "description": "Las personas que conducen: identidad, licencia y transportista. "
                               "Se dan de baja, nunca se borran, para que los viajes que hicieron "
                               "sigan mostrando su nombre.",
                "new": "Nuevo conductor",
                "ownStaff": "Personal propio",
                "empty": {
                    "title": "Sin conductores",
                    "message": "Registra a los conductores para poder asignarlos a un viaje y "
                               "controlar el vencimiento de sus licencias.",
                },
                "columns": {
                    "name": "Conductor",
                    "document": "Documento",
                    "license": "Licencia",
                    "expiry": "Vence",
                },
                "filters": {
                    "name": "Nombre",
                    "license": "Licencia",
                    "allLicenseStatuses": "Cualquier licencia",
                },
                "licenseNoExpiry": "Sin fecha",
                "form": {
                    "create": "Nuevo conductor",
                    "edit": "Editar conductor",
                    "subtitle": "Los datos que se imprimen en un manifiesto y los que deciden si "
                                "esta persona puede salir a ruta.",
                    "firstName": "Nombres",
                    "lastName": "Apellidos",
                    "documentType": "Tipo de documento",
                    "documentNumber": "Número de documento",
                    "licenseNumber": "Número de licencia",
                    "licenseCategory": "Categoría",
                    "licenseExpiresOn": "Vencimiento de la licencia",
                    "licenseExpiresHelp": "Último día válido. Si lo dejas vacío, no se bloquea "
                                          "ninguna asignación: no saber cuándo vence no es lo "
                                          "mismo que estar vencida.",
                    "carrier": "Transportista",
                    "ownStaff": "Personal propio (sin transportista)",
                    "sections": {
                        "license": "Licencia",
                        "employment": "Vínculo laboral",
                    },
                },
            },
        },
        "en": {
            "drivers": {
                "title": "Drivers",
                "description": "The people who drive: identity, licence and carrier. They are "
                               "deactivated, never deleted, so the trips they ran keep showing "
                               "their name.",
                "new": "New driver",
                "ownStaff": "Own staff",
                "empty": {
                    "title": "No drivers",
                    "message": "Register your drivers to assign them to a trip and keep track of "
                               "when their licences expire.",
                },
                "columns": {
                    "name": "Driver",
                    "document": "Document",
                    "license": "Licence",
                    "expiry": "Expires",
                },
                "filters": {
                    "name": "Name",
                    "license": "Licence",
                    "allLicenseStatuses": "Any licence",
                },
                "licenseNoExpiry": "No date",
                "form": {
                    "create": "New driver",
                    "edit": "Edit driver",
                    "subtitle": "What gets printed on a manifest, and what decides whether this "
                                "person may go out on the road.",
                    "firstName": "First name",
                    "lastName": "Last name",
                    "documentType": "Document type",
                    "documentNumber": "Document number",
                    "licenseNumber": "Licence number",
                    "licenseCategory": "Category",
                    "licenseExpiresOn": "Licence expiry",
                    "licenseExpiresHelp": "The last valid day. Leaving it empty blocks no "
                                          "assignment: not knowing when a licence expires is not "
                                          "the same as it having expired.",
                    "carrier": "Carrier",
                    "ownStaff": "Own staff (no carrier)",
                    "sections": {
                        "license": "Licence",
                        "employment": "Employment",
                    },
                },
            },
        },
    },
    "planning": {
        "es": {
            "card": {"noDriver": "Sin conductor"},
            "drawer": {
                "assignDriver": "Asignar conductor",
                "changeDriver": "Cambiar conductor",
                "driverSaved": "Conductor actualizado",
                "header": {"driver": "Conductor", "driverLicense": "Licencia"},
            },
            "trip": {
                "form": {
                    "driverTitle": "Conductor del viaje {{number}}",
                    "driverSubtitle": "Se revisa que la licencia siga vigente el día del viaje y "
                                      "que la persona no esté ya en otro viaje esa fecha.",
                    "driver": "Conductor",
                    "noDriver": "Sin conductor",
                    "submitDriver": "Guardar conductor",
                    "licenseExpiredOption": "licencia vencida",
                    "licenseExpiringOption": "licencia por vencer",
                },
            },
        },
        "en": {
            "card": {"noDriver": "No driver"},
            "drawer": {
                "assignDriver": "Assign driver",
                "changeDriver": "Change driver",
                "driverSaved": "Driver updated",
                "header": {"driver": "Driver", "driverLicense": "Licence"},
            },
            "trip": {
                "form": {
                    "driverTitle": "Driver for trip {{number}}",
                    "driverSubtitle": "Checked against the licence still being valid on the day "
                                      "of the trip, and against the person not already being out "
                                      "on another trip that date.",
                    "driver": "Driver",
                    "noDriver": "No driver",
                    "submitDriver": "Save driver",
                    "licenseExpiredOption": "licence expired",
                    "licenseExpiringOption": "licence expiring",
                },
            },
        },
    },
    "trips": {
        "es": {
            "columns": {"driver": "Conductor"},
            "filters": {"driver": "Conductor", "allDrivers": "Todos los conductores"},
            "workspace": {
                "actions": {"assignDriver": "Asignar conductor", "changeDriver": "Cambiar conductor"},
                "fields": {
                    "driver": "Conductor",
                    "driverPhone": "Teléfono",
                    "driverLicense": "Licencia",
                },
                "sections": {"assignment": "Vehículo, transportista y conductor"},
                "toasts": {"driverSaved": "Conductor actualizado"},
            },
        },
        "en": {
            "columns": {"driver": "Driver"},
            "filters": {"driver": "Driver", "allDrivers": "All drivers"},
            "workspace": {
                "actions": {"assignDriver": "Assign driver", "changeDriver": "Change driver"},
                "fields": {
                    "driver": "Driver",
                    "driverPhone": "Phone",
                    "driverLicense": "Licence",
                },
                "sections": {"assignment": "Vehicle, carrier and driver"},
                "toasts": {"driverSaved": "Driver updated"},
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
