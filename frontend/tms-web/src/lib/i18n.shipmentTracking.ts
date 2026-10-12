// Seguimiento de despachos (ADR-019 §2): sus textos en inglés, en un fichero propio para que la
// pantalla no compita por el mismo bloque de `i18n.ts` con el trabajo del portal. `i18n.ts` lo
// funde en su diccionario `EN` igual que los demás.

export const EN_SHIPMENT_TRACKING: Record<string, string> = {
  "Seguimiento de despachos": "Dispatch tracking",
  "Seguimiento de reparto": "Delivery tracking",
  "Los envíos que el almacén despachó de verdad: qué salió, cómo avanzan sus paradas y qué pasó con sus documentos. Abre una fila para ver la ruta, las posiciones y cada documento.":
    "The shipments the warehouse actually dispatched: what left, how their stops are progressing and what happened to their documents. Open a row for the route, positions and every document.",
  "Despachos": "Dispatches",
  "Con documentos pendientes": "With pending documents",
  "Sin documentos pendientes": "No pending documents",
  "Diferencias de despacho": "Dispatch differences",
  "Lo que salió no coincide con el plan": "What left does not match the plan",
  "Documentos pendientes": "Pending documents",
  "Documentos rechazados": "Documents rejected",
  "Documentos no entregados": "Documents not delivered",
  "de {{n}} documentos": "of {{n}} documents",
  "Envío, despacho, placa o conductor": "Shipment, dispatch, plate or driver",
  "Sin despachos": "No dispatches",
  "Ningún envío despachado por el almacén coincide con las fechas y los filtros.":
    "No shipment dispatched by the warehouse matches the dates and filters.",
  "Mapa de despachos": "Dispatch map",
  "Vista": "View",
  "Progreso de paradas": "Stop progress",
  "reportados": "reported",
  "{{delivered}} entregados, {{problems}} rechazados o no entregados, {{pending}} pendientes":
    "{{delivered}} delivered, {{problems}} rejected or not delivered, {{pending}} pending",
  "{{n}} inconsistentes": "{{n}} inconsistent",
  // Mapas
  "Con rechazo o no entrega": "Rejected or not delivered",
  "Leyenda del mapa": "Map legend",
  "Paradas de los despachos": "Stops of the dispatches",
  "Seleccionar el despacho {{shipment}}": "Select dispatch {{shipment}}",
  "No hay paradas que mostrar.": "There are no stops to show.",
  "El mapa no está disponible. Abajo, las paradas de cada despacho con el estado de sus documentos.":
    "The map is not available. Below, the stops of each dispatch with the state of their documents.",
  "El mapa no está disponible en esta instalación. Abajo, las paradas de cada despacho con el estado de sus documentos.":
    "The map is not available in this installation. Below, the stops of each dispatch with the state of their documents.",
  "El mapa no está disponible. Las paradas y sus documentos se listan abajo.":
    "The map is not available. The stops and their documents are listed below.",
  "Mapa de la ruta del despacho": "Dispatch route map",
  "Última posición reportada": "Last reported position",
  // Panel de un despacho
  "Abrir espacio de trabajo": "Open workspace",
  "Despacho del almacén": "Warehouse dispatch",
  "Vehículo despachado": "Dispatched vehicle",
  "Resueltas / totales": "Resolved / total",
  "{{done}} de {{total}} reportados": "{{done}} of {{total}} reported",
  "Ruta y posiciones": "Route and positions",
  "Consultando posiciones...": "Fetching positions...",
  "Última posición GPS: {{when}}. Las posiciones informan; no cambian el estado de nada.":
    "Last GPS position: {{when}}. Positions inform; they change the state of nothing.",
  "Sin posiciones GPS": "No GPS positions",
  "Paradas y documentos": "Stops and documents",
  "Tu rol puede seguir los despachos pero no leer el detalle de sus documentos.":
    "Your role can follow dispatches but not read their documents in detail.",
  "Documentos del envío": "Shipment documents",
  "Documentos del envío sin parada identificada": "Shipment documents with no identified stop",
  "Destino sin nombre": "Unnamed destination",
  "{{pending}} pendientes de {{total}}": "{{pending}} pending of {{total}}",
  "Abrir el documento {{doc}}": "Open document {{doc}}",
};
