// Scorecard de transportistas (ITEM 3) y avance automático de la cascada de ofertas (ITEM 10): sus
// textos en inglés, en un fichero propio como los demás módulos. `i18n.ts` lo funde en `EN`.

export const EN_CARRIER_SCORECARD: Record<string, string> = {
  "Scorecard de transportistas": "Carrier scorecard",
  "Cargando el scorecard...": "Loading the scorecard...",
  "Periodo": "Period",
  "Transportistas evaluados": "Carriers assessed",
  "Con viajes, ofertas o facturas en el periodo": "With trips, offers or invoices in the period",
  "Viajes atribuidos": "Attributed trips",
  "Sin contar los cancelados": "Cancelled ones excluded",
  "Viajes sin transportista": "Trips without a carrier",
  "Sin transportista ni vehículo: no cuentan para nadie": "No carrier and no vehicle: they count for nobody",
  "Desempeño por transportista": "Performance by carrier",
  "Ningún transportista tuvo viajes, ofertas ni facturas en el periodo.":
    "No carrier had trips, offers or invoices in the period.",
  "Salida a tiempo": "On-time departure",
  "En ventana": "In window",
  "Incidencias / 100 viajes": "Incidents / 100 trips",
  "Ofertas aceptadas": "Offers accepted",
  "Respuesta media": "Average response",
  "Cargos devueltos": "Signed copies returned",
  "Facturas con diferencia": "Invoices with a difference",
  "Costo por viaje": "Cost per trip",
  "Costo por kg": "Cost per kg",
  "Ver viajes": "See trips",
  "Ver desempeño": "See performance",
  "sin datos": "no data",
  "sin medir": "not measured",
  "de {{n}}": "of {{n}}",
  "{{n}} salieron": "{{n}} departed",
  "{{n}} incidencias": "{{n}} incidents",
  "Tarde": "Late",
  "Cómo se calcula": "How it is calculated",
  "«sin datos» quiere decir que no hubo nada que medir — nunca 0%. El color es una ayuda de lectura de esta pantalla.":
    "“no data” means there was nothing to measure — never 0%. The colour is a reading aid of this screen only.",
  "Viajes del periodo (por fecha de planificación) que no se cancelaron. Debajo, cuántos salieron (en tránsito o completados).":
    "Trips of the period (by planning date) that were not cancelled. Below, how many departed (in transit or completed).",
  "Salidas reales no posteriores a la planificada, sobre los viajes con las dos horas registradas.":
    "Actual departures no later than planned, over the trips with both times recorded.",
  "Paradas con llegada dentro de su ventana de servicio (hora de la empresa), sobre las paradas con llegada y ventana.":
    "Stops reached inside their service window (company time), over the stops with an arrival and a window.",
  "Entregas completas sobre todas las entregas registradas: parciales, rechazadas, fallidas y no intentadas cuentan en contra.":
    "Full deliveries over every recorded delivery: partial, rejected, failed and not attempted count against.",
  "Incidencias reportadas en sus viajes por cada 100 viajes del periodo. Puede pasar de 100.":
    "Incidents reported on its trips per 100 trips of the period. It can exceed 100.",
  "Ofertas aceptadas sobre las resueltas por el transportista: aceptadas, rechazadas y vencidas sin respuesta.":
    "Offers accepted over the ones the carrier settled: accepted, rejected and lapsed without an answer.",
  "Tiempo medio entre el envío de la oferta y la respuesta, sobre las aceptadas y rechazadas.":
    "Average time from sending the offer to the answer, over the accepted and rejected ones.",
  "Documentos con el cargo firmado devuelto a la oficina, sobre los documentos entregados.":
    "Documents whose signed copy came back to the office, over the documents delivered.",
  "Facturas con al menos una diferencia en su último cotejo, sobre las facturas con fecha en el periodo. Una fila por moneda; debajo, lo facturado de más.":
    "Invoices with at least one difference on their latest match, over the invoices dated in the period. One row per currency; below, what was over-billed.",
  "Costo conocido (el real si se registró, si no el estimado) dividido entre los viajes con costo. Por moneda: no se convierte.":
    "Known cost (the actual when recorded, the estimate otherwise) divided by the trips with a cost. Per currency: nothing is converted.",
  "El costo de los viajes con peso asignado dividido entre esos kilos. Los viajes sin peso no entran.":
    "The cost of the trips with assigned weight divided by those kilograms. Trips with no weight are left out.",
  "{{n}} viajes · {{from}} → {{to}}": "{{n}} trips · {{from}} → {{to}}",
  "Se muestran los {{shown}} viajes más recientes de {{total}}. Acota el periodo para verlos todos.":
    "Showing the {{shown}} most recent trips of {{total}}. Narrow the period to see them all.",
  "Sin viajes en el periodo.": "No trips in the period.",
  // Cascada de ofertas (ITEM 10)
  "Avance automático": "Automatic advance",
  "La oferta actual venció sin respuesta. El avance automático la pasará al siguiente transportista en su próxima vuelta; puedes adelantarlo.":
    "The current offer lapsed without an answer. The automatic advance will move it to the next carrier on its next pass; you can do it now.",
};
