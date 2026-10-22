// La hoja de ruta imprimible del viaje (`/trips/:tripId/print`): sus textos en inglés. `i18n.ts`
// lo funde en su diccionario `EN`.

export const EN_ROUTE_SHEET: Record<string, string> = {
  "Imprimir hoja de ruta": "Print route sheet",
  "Hoja de ruta": "Route sheet",
  "Hoja de ruta {{shipment}}": "Route sheet {{shipment}}",
  "Preparando la hoja de ruta...": "Preparing the route sheet...",
  "Volver al viaje": "Back to the trip",
  "Imprimir": "Print",
  "No se pudieron leer todas las líneas o documentos; la hoja sale con lo que llegó.":
    "Not every line or document could be read; the sheet prints with what arrived.",
  "Plan {{plan}} · {{date}}": "Plan {{plan}} · {{date}}",
  "Vehículo (placa)": "Vehicle (plate)",
  "Licencia {{n}}": "Licence {{n}}",
  "Totales del envío": "Shipment totals",
  "Tu perfil no puede leer pedidos: la hoja muestra lo asignado a cada parada, sin las líneas ni los documentos del ERP.":
    "Your profile cannot read orders: the sheet shows what is assigned to each stop, without the lines or the ERP's documents.",
  "Pedidos sin parada": "Orders without a stop",
  "Ventana {{window}}": "Window {{window}}",
  "Llegada estimada {{eta}}": "Estimated arrival {{eta}}",
  "{{n}} pallets": "{{n}} pallets",
  "Pide {{window}}": "Requests {{window}}",
  "Viaja una parte del pedido (las cifras de arriba). Las líneas son las del pedido completo.":
    "Part of the order travels (the figures above). The lines are those of the whole order.",
  "Líneas del pedido {{number}}": "Lines of order {{number}}",
  "Documentos del pedido {{number}}": "Documents of order {{number}}",
  "Recibido": "Received",
  "Recibí conforme": "Received in good order",
  "Nombre de quien recibe": "Name of the recipient",
  "Hora de llegada / salida": "Arrival / departure time",
  "Firma y sello": "Signature and stamp",
  "Observaciones (faltantes, daños, rechazos)": "Remarks (shortages, damage, refusals)",
  "Firma del conductor": "Driver's signature",
  "Despacho (origen)": "Dispatch (origin)",
  "Garita / control de salida": "Gate / exit check",
  "Estado del envío al imprimir: {{status}} · Impreso el {{when}}":
    "Shipment status when printed: {{status}} · Printed on {{when}}",
};
