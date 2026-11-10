// ── Seguimiento de la entrega (V75): contacto del destino, productos rechazados, ubicación de
// llegada y dos alertas del viaje ──
// Su propio mapa, como los demás módulos recientes; `i18n.ts` solo lo funde. La clave es el español.
export const EN_DELIVERY_FOLLOW_UP: Record<string, string> = {
  // Campana
  "Viaje en ruta": "Trip on the road",
  "El viaje {{trip}} salió a ruta.": "Trip {{trip}} is on the road.",
  "Viaje listo para cerrar": "Trip ready to close",
  "El viaje {{trip}} terminó sus {{stopCount}} paradas. Falta completarlo.":
    "Trip {{trip}} has finished its {{stopCount}} stops. It still needs to be completed.",
  // Ubicación: contacto de entrega
  "Contacto de entrega": "Delivery contact",
  "Quién recibe aquí y qué debe saber el conductor al llegar. Lo ve el transportista en la parada del portal.":
    "Who receives here and what the driver must know on arrival. The carrier sees it at the portal stop.",
  "Persona de contacto": "Contact person",
  "p. ej. Rosa Pérez, jefa de bodega": "e.g. Rosa Pérez, warehouse lead",
  "Teléfono de contacto": "Contact phone",
  "Solo dígitos, espacios y los signos + - ( )": "Digits, spaces and + - ( ) only",
  "Indicaciones de entrega": "Delivery instructions",
  "p. ej. Descargar por el portón trasero; recepción de 8:00 a 16:00":
    "e.g. Unload at the back gate; receiving from 8:00 to 16:00",
  "Recibe": "Receives",
  "Llamar a {{phone}}": "Call {{phone}}",
  // Portal: productos rechazados
  "¿Qué productos rechazó el cliente?": "Which products did the customer refuse?",
  "Rechazadas de {{material}}": "Refused of {{material}}",
  "Unos {{kg}} kg rechazados. Lo que no rechazó se registra como entregado.":
    "About {{kg}} kg refused. Whatever was not refused is recorded as delivered.",
  "Indica cuántas unidades de qué producto rechazó el cliente.":
    "Say how many units of which product the customer refused.",
  "No se puede rechazar más de lo que pidió el cliente.": "You cannot refuse more than the customer ordered.",
  // Historia del viaje: ubicación de la llegada
  "Distancia en línea recta entre la ubicación del teléfono al pulsar «Llegué» y las coordenadas del destino. Solo informa.":
    "Straight-line distance between the phone's location when «I arrived» was pressed and the destination's coordinates. For information only.",
  "Llegó a {{distance}} del destino": "Arrived {{distance}} from the destination",
  "A {{distance}} del destino": "{{distance}} from the destination",
};
