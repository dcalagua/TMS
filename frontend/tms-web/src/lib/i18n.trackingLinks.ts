// ── Enlace de seguimiento para el cliente (ADR-029, V74) ──
// La página pública que abre el cliente desde WhatsApp y el «Compartir seguimiento» de la oficina.
// Su propio mapa, fundido en `i18n.ts`. La clave sigue siendo el texto en español.
export const EN_TRACKING_LINKS: Record<string, string> = {
  // Página pública
  "Seguimiento de pedido": "Order tracking",
  "Cargando el seguimiento...": "Loading tracking...",
  "Seguimiento provisto por eTMS · by EBIM": "Tracking provided by eTMS · by EBIM",
  "Pedido registrado": "Order received",
  "Llegó a destino": "Arrived at destination",
  "Ref.": "Ref.",
  "Recorrido del pedido": "Order journey",
  "completado": "completed",
  "pendiente": "pending",
  "Llega entre las {{from}} y las {{to}}": "Arriving between {{from}} and {{to}}",
  "Llega el {{date}}, entre las {{from}} y las {{to}}": "Arriving on {{date}}, between {{from}} and {{to}}",
  "Recibido a las {{time}}": "Received at {{time}}",
  "Recibido a las {{time}} por {{name}}": "Received at {{time}} by {{name}}",
  "Actualizado a las {{time}} · horas de {{zone}}": "Updated at {{time}} · times in {{zone}}",
  "Este enlace vence el {{date}}.": "This link expires on {{date}}.",
  "Este enlace de seguimiento no existe o ya no está disponible. Pide uno nuevo a quien te lo envió.":
    "This tracking link does not exist or is no longer available. Ask whoever sent it for a new one.",
  "Demasiadas consultas seguidas. Espera unos minutos y vuelve a abrir el enlace.":
    "Too many requests in a row. Wait a few minutes and open the link again.",
  "No pudimos cargar el seguimiento. Revisa tu conexión y vuelve a intentarlo.":
    "We could not load the tracking. Check your connection and try again.",
  // Oficina: compartir
  "Compartir seguimiento": "Share tracking",
  "Un enlace sin contraseña para que el cliente vea el estado, la hora estimada y la entrega de este pedido. No muestra precios, direcciones ni otros pedidos, y vence 7 días después de la entrega.":
    "A password-free link for the customer to see this order's status, estimated time and delivery. It shows no prices, addresses or other orders, and expires 7 days after delivery.",
  "Enlace de seguimiento": "Tracking link",
  "Enlaces de seguimiento": "Tracking links",
  "Generar enlace": "Generate link",
  "Generando...": "Generating...",
  "Copiar enlace": "Copy link",
  "Enlace copiado": "Link copied",
  "No se pudo copiar": "Could not copy",
  "Selecciona el enlace y cópialo a mano.": "Select the link and copy it by hand.",
  "Enviar por WhatsApp": "Send via WhatsApp",
  "Por seguridad el enlace solo se muestra ahora. Si lo pierdes, genera otro y revoca este.":
    "For security the link is shown only now. If you lose it, generate another and revoke this one.",
  "Enlace de seguimiento generado": "Tracking link generated",
  "No se pudo generar el enlace": "The link could not be generated",
  "Este pedido todavía no tiene enlaces de seguimiento.": "This order has no tracking links yet.",
  "Creado {{when}} · vence {{expires}}": "Created {{when}} · expires {{expires}}",
  "Aún no lo abrieron": "Not opened yet",
  "Abierto {{n}} veces · última {{when}}": "Opened {{n}} times · last {{when}}",
  "Revocar enlace": "Revoke link",
  "¿Revocar este enlace de seguimiento?": "Revoke this tracking link?",
  "Quien lo tenga dejará de ver el pedido. Esto no se puede deshacer; puedes generar otro.":
    "Whoever has it will stop seeing the order. This cannot be undone; you can generate another.",
  "Por ejemplo: enviado al cliente equivocado": "For example: sent to the wrong customer",
  "Enlace revocado": "Link revoked",
  "Deja de funcionar ahora mismo.": "It stops working right away.",
  "No se pudo revocar el enlace": "The link could not be revoked",
  // Configuración de la empresa
  "Seguimiento para el cliente": "Customer tracking",
  "Qué ve de la placa quien abre un enlace de seguimiento. El enlace nunca muestra la placa completa, el conductor, precios ni direcciones.":
    "How much of the plate someone opening a tracking link sees. The link never shows the full plate, the driver, prices or addresses.",
  "Placa en el enlace": "Plate on the link",
  // Enums
  "Parcial: solo los tres últimos caracteres": "Partial: last three characters only",
  "Oculta": "Hidden",
  "Caducado": "Expired",
  "Revocado": "Revoked",
  "Enlace de seguimiento revocado": "Tracking link revoked",
};
