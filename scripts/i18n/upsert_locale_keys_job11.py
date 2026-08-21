# -*- coding: utf-8 -*-
"""Job 11 (KPIs & Reporting V1) locale additions.

Writes one brand-new namespace and layers one key onto an existing one:

  * `reporting.*`         - the reports screen: cards, charts, both tables and the CSV export;
  * `navigation.items.reports` - the menu entry, beside the control tower.

`reporting` is a namespace of its own rather than a section of `planning` or `trips`. It is not a
module's screen: it reads five modules and owns none of them, exactly as `controlTower` does, and a
translator needs to be able to read it as one document about measurement.

Wording notes that are product decisions, not translation choices:

  * `scopeNote` states the dash rule in as many words. A dash on this screen means "nothing was
    measured", never "the result was zero", and that sentence has to be on the screen rather than in
    a tooltip: the whole report is arranged around the distinction and an operations manager
    quoting 0% instead of "no evidence" is the one failure this feature cannot afford.
  * every hint that sits under a percentage names its denominator ("Sobre {{measured}} salidas
    registradas"). 92% over five departures and 92% over four hundred are different claims, and the
    card is where the difference is said.
  * `kpi.utilizationHint` says "con capacidad congelada" / "with a frozen capacity" rather than
    "confirmados". The figure covers the shipments whose limit is on file, which is confirmed and
    beyond minus the cancelled ones, and naming the state would be wrong for three of the four.
  * `kpi.notPermitted` is "No disponible con tus permisos" and never "Sin datos". The caller was not
    allowed to look; saying there is no data would be the screen asserting something it was never
    told. Same distinction `charts.noData` keeps on the other side.
  * `sections.costHint` says out loud that the difference is computed only over the shipments that
    carry both figures. Without it a variance over three invoiced shipments reads as a variance over
    four hundred estimated ones.
  * `columns.variance` is "Diferencia" and not "Ahorro"/"Sobrecosto", for the reason job 07 gives
    about the same word on the trip cost card: the sign is shown and naming it either way would
    editorialise a number that is read in both directions.
  * `exceptionCell` keeps the open count in brackets rather than as a second column: the table
    already has nine, and "3 (2 abiertas)" is how a dispatcher says it out loud.

Run from the repo root:  python scripts/i18n/upsert_locale_keys_job11.py
Then, from frontend/tms-web:  npx tsc -b --noEmit

Only ASCII goes through print()/raise - this Windows shell's stdout is cp1252.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upsert_locale_keys import deep_merge, read_namespace, write_namespace  # noqa: E402


# Written from scratch: namespace -> language -> the whole tree.
NEW_NAMESPACES = {
    "reporting": {
        "es": {
            "byStatus": "Envíos por estado:",
            "charts": {
                "noData": "Sin datos",
                "onTime": "Salidas a tiempo por día",
                "onTimeHint": "Sobre las salidas registradas de cada día. Los días en gris no tienen ninguna medición.",
                "trips": "Envíos por día",
                "tripsHint": "Todos los envíos planificados del día, anulados incluidos.",
            },
            "columns": {
                "actual": "Real",
                "cancelled": "Anulados",
                "comparable": "Comparables",
                "completed": "Completados",
                "currency": "Moneda",
                "date": "Día",
                "deliveries": "Entregas completas / registradas",
                "deliverySuccess": "% entregas completas",
                "departures": "Salidas tarde / medidas",
                "estimated": "Estimado",
                "exceptions": "Incidencias",
                "onTimeDeparture": "% salidas a tiempo",
                "trips": "Envíos",
                "variance": "Diferencia",
            },
            "description": "Cómo fue la operación en un rango de días, con el detalle diario y su exportación.",
            "empty": {
                "costMessage": "Cuando se estimen o registren costos de los envíos del rango, aparecerán aquí.",
                "costTitle": "Sin costos en el rango",
                "dailyMessage": "Elige otro rango de fechas para ver el detalle día a día.",
                "dailyTitle": "Sin días que mostrar",
            },
            "exceptionCell": "{{total}} ({{open}} abiertas)",
            "exportCsv": "Exportar CSV",
            "exportError": "No se pudo exportar el reporte",
            "filters": {
                "from": "Desde",
                "to": "Hasta",
            },
            "kpi": {
                "deliverySuccess": "Entregas completas",
                "deliverySuccessHint": "{{delivered}} de {{recorded}} entregas registradas",
                "exceptions": "Incidencias",
                "exceptionsHint": "{{open}} abiertas · {{per100}} por cada 100 envíos",
                "nothingMeasured": "Ninguna salida registrada en el rango",
                "nothingRecorded": "Ninguna entrega registrada en el rango",
                "notPermitted": "No disponible con tus permisos",
                "onTimeDeparture": "Salidas a tiempo",
                "onTimeDepartureHint": "Sobre {{measured}} salidas registradas",
                "onTimeService": "Ventanas cumplidas",
                "onTimeServiceHint": "Sobre {{measured}} paradas con llegada y ventana",
                "plannedOrders": "Pedidos planificados",
                "plannedOrdersHint": "{{planned}} de {{input}} pedidos del rango",
                "tenderAcceptance": "Aceptación de transportistas",
                "tenderAcceptanceHint": "{{answered}} respondidas de {{attempts}} ofertas",
                "trips": "Envíos",
                "tripsHint": "{{run}} realizados, {{cancelled}} anulados",
                "utilization": "Ocupación por peso",
                "utilizationHint": "Sobre {{trips}} envíos con capacidad congelada",
            },
            "overTrips": "{{amount}} en {{trips}} envíos",
            "rangeBadge": "{{from}} a {{to}} ({{days}} días)",
            "refresh": "Actualizar",
            "scopeNote": "Todo el reporte cubre el mismo rango. Un guion significa que no hubo nada que medir, no que el resultado fuera cero.",
            "sections": {
                "cost": "Costo estimado contra real",
                "costHint": "Una fila por moneda: no hay conversión entre monedas. La diferencia se calcula solo sobre los envíos que tienen las dos cifras.",
                "daily": "Día a día",
                "detail": "Detalle diario",
                "detailHint": "Cada valor de los gráficos es una fila de esta tabla, y esta tabla es lo que exporta el CSV.",
                "headline": "El rango completo",
            },
            "title": "Reportes y KPIs",
        },
        "en": {
            "byStatus": "Shipments by state:",
            "charts": {
                "noData": "Nothing measured",
                "onTime": "On-time departures per day",
                "onTimeHint": "Over each day's recorded departures. Days shaded grey have nothing measured.",
                "trips": "Shipments per day",
                "tripsHint": "Every shipment planned for the day, cancelled ones included.",
            },
            "columns": {
                "actual": "Actual",
                "cancelled": "Cancelled",
                "comparable": "Comparable",
                "completed": "Completed",
                "currency": "Currency",
                "date": "Day",
                "deliveries": "Deliveries complete / recorded",
                "deliverySuccess": "% complete deliveries",
                "departures": "Departures late / measured",
                "estimated": "Estimated",
                "exceptions": "Problems",
                "onTimeDeparture": "% on-time departures",
                "trips": "Shipments",
                "variance": "Difference",
            },
            "description": "How the operation did over a range of days, with the daily detail and its export.",
            "empty": {
                "costMessage": "Costs estimated or recorded for the range's shipments will appear here.",
                "costTitle": "No costs in the range",
                "dailyMessage": "Pick another date range to see the day-by-day detail.",
                "dailyTitle": "No days to show",
            },
            "exceptionCell": "{{total}} ({{open}} open)",
            "exportCsv": "Export CSV",
            "exportError": "The report could not be exported",
            "filters": {
                "from": "From",
                "to": "To",
            },
            "kpi": {
                "deliverySuccess": "Complete deliveries",
                "deliverySuccessHint": "{{delivered}} of {{recorded}} recorded deliveries",
                "exceptions": "Problems",
                "exceptionsHint": "{{open}} open · {{per100}} per 100 shipments",
                "nothingMeasured": "No departure recorded in the range",
                "nothingRecorded": "No delivery recorded in the range",
                "notPermitted": "Not available with your permissions",
                "onTimeDeparture": "On-time departures",
                "onTimeDepartureHint": "Over {{measured}} recorded departures",
                "onTimeService": "Windows met",
                "onTimeServiceHint": "Over {{measured}} stops with an arrival and a window",
                "plannedOrders": "Planned orders",
                "plannedOrdersHint": "{{planned}} of {{input}} orders in the range",
                "tenderAcceptance": "Carrier acceptance",
                "tenderAcceptanceHint": "{{answered}} answered of {{attempts}} offers",
                "trips": "Shipments",
                "tripsHint": "{{run}} ran, {{cancelled}} cancelled",
                "utilization": "Weight utilisation",
                "utilizationHint": "Over {{trips}} shipments with a frozen capacity",
            },
            "overTrips": "{{amount}} over {{trips}} shipments",
            "rangeBadge": "{{from}} to {{to}} ({{days}} days)",
            "refresh": "Refresh",
            "scopeNote": "The whole report covers the same range. A dash means there was nothing to measure, not that the result was zero.",
            "sections": {
                "cost": "Estimated against actual cost",
                "costHint": "One row per currency: there is no conversion between them. The difference is computed only over the shipments that carry both figures.",
                "daily": "Day by day",
                "detail": "Daily detail",
                "detailHint": "Every value in the charts is a row of this table, and this table is what the CSV exports.",
                "headline": "The whole range",
            },
            "title": "Reports & KPIs",
        },
    },
}


# Deep-merged onto what is already there: namespace -> language -> partial tree.
EXISTING_NAMESPACE_UPDATES = {
    "navigation": {
        "es": {"items": {"reports": "Reportes"}},
        "en": {"items": {"reports": "Reports"}},
    },
}


def main():
    for name in sorted(NEW_NAMESPACES):
        write_namespace(name, NEW_NAMESPACES[name]["es"], NEW_NAMESPACES[name]["en"])
    for name in sorted(EXISTING_NAMESPACE_UPDATES):
        merged = {}
        for lang in ("es", "en"):
            merged[lang] = deep_merge(read_namespace(name, lang), EXISTING_NAMESPACE_UPDATES[name][lang])
        write_namespace(name, merged["es"], merged["en"])


if __name__ == "__main__":
    main()
