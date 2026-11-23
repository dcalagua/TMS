// ── Asistentes de IA (ADR-030) ──
// «Optimizar paradas» en Maestros › Rutas. Su propio mapa, fundido en `i18n.ts`. La clave sigue
// siendo el texto en español.
export const EN_AI_ASSISTANTS: Record<string, string> = {
  "Optimizar paradas": "Optimize stops",
  "Optimizando...": "Optimizing...",
  "Agrega al menos dos paradas para optimizar.": "Add at least two stops to optimize.",
  "Falta el origen": "Origin missing",
  "La ruta parte del origen: elígelo para poder optimizar las paradas.":
    "The route starts at its origin: choose it to optimize the stops.",
  "Ordena las paradas con IA para recorrer menos kilómetros. Se guarda solo cuando guardas la ruta.":
    "Orders the stops with AI to drive fewer kilometres. It is saved only when you save the route.",
  "Faltan coordenadas": "Coordinates missing",
  "Sin coordenadas: {{names}}. Cárgalas en Maestros › Ubicaciones para poder optimizar.":
    "No coordinates: {{names}}. Add them in Master data › Locations to optimize.",
  "No se pudieron optimizar las paradas": "The stops could not be optimized",
  "Orden propuesto": "Proposed order",
  "{{proposed}} km en lugar de {{current}} km ({{saving}} % menos)":
    "{{proposed}} km instead of {{current}} km ({{saving}}% less)",
  "El orden actual ya es el más corto que encontró el asistente ({{current}} km).":
    "The current order is already the shortest the assistant found ({{current}} km).",
  "Distancias estimadas a partir de las coordenadas; el recorrido real por carretera puede variar.":
    "Distances estimated from coordinates; the real road distance may differ.",
  "Descartar": "Discard",
  // Invitación de usuarios por correo (ADR-031)
  "Reenviar invitación": "Resend invitation",
  "Invitación reenviada": "Invitation resent",
  "No se pudo reenviar la invitación": "The invitation could not be resent",
  "{{email}} recibirá un correo para crear su contraseña.": "{{email}} will receive an email to create their password.",
  "Acceso otorgado": "Access granted",
  "{{email}} ya tenía cuenta: entra con su contraseña de siempre.": "{{email}} already had an account: they sign in with their usual password.",
  "Sin cuenta de acceso": "No sign-in account",
  // Paradas de la ruta: lista o mapa
  "Vista de paradas": "Stops view",
  "El origen es el 0 y las paradas van numeradas en el orden de la lista. Las líneas unen los puntos en línea recta: no son el recorrido por carretera.":
    "The origin is 0 and stops are numbered in list order. Lines join the points in a straight line: they are not the road route.",
};
