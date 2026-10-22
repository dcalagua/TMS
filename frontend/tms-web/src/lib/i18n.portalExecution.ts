// ── Portal de transportistas: paradas, entregas e incidencias (V70) ──
// Su propio mapa, como `i18n.portalOffline.ts`: `i18n.ts` solo lo funde. La clave es el español.
export const EN_PORTAL_EXECUTION: Record<string, string> = {
  // Quién registró un hecho
  "Transportista (portal)": "Carrier (portal)", "Sistema": "System",
  "Registrado por la oficina": "Recorded by the office",
  // La parada
  "Llegué": "I arrived", "Empecé la descarga": "Unloading started", "Terminé la parada": "Stop finished",
  "No se pudo atender": "Could not serve", "No se pudo atender la parada": "The stop could not be served",
  "Guardar como no atendida": "Save as not served", "Parada actualizada": "Stop updated",
  "Llegada {{time}}": "Arrived {{time}}", "Descarga {{time}}": "Unloading {{time}}", "Salida {{time}}": "Left {{time}}",
  "¿Terminar la parada?": "Finish the stop?", "Terminar igual": "Finish anyway",
  "Hay pedidos de esta parada sin resultado de entrega. Podrás registrarlo después mientras el viaje siga en ruta.":
    "Some orders of this stop have no delivery result. You can still record it while the trip is on the road.",
  "Marca «Llegué» para registrar la entrega de los pedidos.": "Tap “I arrived” to record the delivery of the orders.",
  "Pedidos de la parada": "Orders of the stop",
  // La entrega
  "Entrega del pedido {{order}}": "Delivery of order {{order}}", "Cargado": "Loaded",
  "¿Qué pasó con el pedido?": "What happened to the order?", "Corregir el resultado": "Correct the result",
  "Entregado completo": "Delivered in full", "Rechazado por el cliente": "Refused by the customer",
  "No se pudo entregar": "Could not be delivered", "No se intentó (sigue en el camión)": "Not attempted (still on the truck)",
  "¿Cuánto rechazó el cliente?": "How much did the customer refuse?",
  "Lo que no rechazó se registra como entregado.": "Whatever was not refused is recorded as delivered.",
  "Quién recibió (opcional)": "Received by (optional)", "Quién lo rechazó (opcional)": "Refused by (optional)",
  "Documento de identidad (opcional)": "ID document (optional)", "Qué pasó (obligatorio)": "What happened (required)",
  "Corregir entrega": "Correct delivery",
  "Este sistema no guarda fotos ni firmas: el resultado se registra sin evidencia.":
    "This system does not store photos or signatures: the result is recorded without evidence.",
  "Sin conexión. La entrega se guardará en el teléfono y se enviará al recuperar señal.":
    "Offline. The delivery will be saved on this phone and sent when the signal is back.",
  "Elige qué pasó con el pedido.": "Choose what happened to the order.",
  "Cuenta qué pasó: es obligatorio cuando no se entregó todo.": "Say what happened: it is required when not everything was delivered.",
  "Escribe cantidades válidas, sin negativos.": "Enter valid amounts, no negatives.",
  "Indica cuánto rechazó el cliente.": "Enter how much the customer refused.",
  "No se puede rechazar más de lo que se cargó para este pedido.": "You cannot refuse more than was loaded for this order.",
  "No se pudo guardar.": "Could not save.",
  // Incidencias
  "Reportar incidencia del viaje": "Report a trip incident", "Reportar incidencia en esta parada": "Report an incident at this stop",
  "Incidencia": "Incident", "¿Qué pasó?": "What happened?", "Elige qué pasó.": "Choose what happened.",
  "Con «Otra», cuenta qué pasó.": "With “Other”, say what happened.",
  "Sin conexión. Se guardará en el teléfono y se enviará al recuperar señal.":
    "Offline. It will be saved on this phone and sent when the signal is back.",
  // La cola
  "«{{what}}» no se enviará. Podrás volver a registrarlo desde el viaje.":
    "“{{what}}” will not be sent. You can record it again from the trip.",
};
