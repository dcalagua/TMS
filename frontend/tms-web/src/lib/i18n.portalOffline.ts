// ── Portal de transportistas sin señal y en el teléfono (ADR-019 §1) ──
// Su propio mapa, en su propio fichero, para no pelear con otras pantallas por el diccionario
// grande: `i18n.ts` solo lo funde con el resto. La clave sigue siendo el texto en español.
export const EN_PORTAL_OFFLINE: Record<string, string> = {
  // Conectividad y cola
  "En línea": "Online", "Sin conexión": "Offline", "Sin conexión · {{n}}": "Offline · {{n}}",
  "Sincronizando": "Syncing", "Sincronizando...": "Syncing...", "{{n}} con error": "{{n}} failed",
  "{{n}} por sincronizar": "{{n}} to sync", "Sincronización": "Sync", "Sincronización: {{state}}": "Sync: {{state}}",
  "Sincronizar ahora": "Sync now", "Con conexión.": "Connected.",
  "Sin conexión. Los reportes se enviarán solos al recuperar señal.":
    "Offline. Reports will be sent automatically when the signal is back.",
  "Todo sincronizado": "All synced", "No hay reportes esperando en este teléfono.": "No reports are waiting on this phone.",
  "Reportes en este teléfono": "Reports on this phone", "Guardado {{when}}": "Saved {{when}}",
  "El servidor no lo aceptó.": "The server did not accept it.", "El servidor respondió {{status}}.": "The server answered {{status}}.",
  "Sin conexión con el servidor.": "No connection to the server.", "Esperando para enviar.": "Waiting to be sent.",
  "Intento {{n}} sin éxito. Se reintenta a las {{time}}.": "Attempt {{n}} failed. Retrying at {{time}}.",
  "¿Descartar este reporte?": "Discard this report?",
  "El reporte de {{doc}} no se enviará. Podrás volver a reportar el documento desde el viaje.":
    "The report for {{doc}} will not be sent. You can report the document again from the trip.",
  "Los reportes sin sincronizar se guardan solo en este teléfono. Si borras los datos del navegador o cierras sesión, se pierden.":
    "Unsynced reports are stored on this phone only. Clearing the browser data or signing out loses them.",
  "Pendiente de sincronizar": "Waiting to sync", "No se pudo sincronizar": "Could not sync",
  "Sin conexión · datos de las {{time}}": "Offline · data from {{time}}",
  "Sin conexión · datos del {{when}}": "Offline · data from {{when}}",
  "Sin conexión y sin datos guardados en este teléfono. Abre el portal con señal al menos una vez.":
    "Offline and no data saved on this phone. Open the portal with signal at least once.",
  "No se pudo conectar con el servidor. Revisa la señal y vuelve a intentarlo.":
    "Could not reach the server. Check your signal and try again.",
  // Cuenta y versión
  "¿Cerrar sesión con reportes sin enviar?": "Sign out with unsent reports?",
  "Tienes {{count}} reporte sin sincronizar; si cierras sesión se perderá.":
    "You have {{count}} unsynced report; signing out will lose it.",
  "Tienes {{count}} reportes sin sincronizar; si cierras sesión se perderán.":
    "You have {{count}} unsynced reports; signing out will lose them.",
  "Cerrar sesión y perderlos": "Sign out and lose them", "Seguir conectado": "Stay signed in",
  "Nueva versión disponible": "New version available",
  // Lista de viajes
  "Qué viajes ver": "Which trips to show", "Recargando": "Reloading",
  "Aún no tienes viajes despachados": "You have no dispatched trips yet",
  "Los viajes aparecen aquí cuando el almacén los despacha a tu transportista. Si esperas uno, tira hacia abajo para recargar o pregunta en el almacén.":
    "Trips show up here once the warehouse dispatches them to your carrier. If you are expecting one, pull down to reload or ask the warehouse.",
  "Cargo recibido: {{n}}": "Signed copies returned: {{n}}",
  "{{done}} de {{total}} documentos reportados": "{{done}} of {{total}} documents reported",
  // Viaje
  "{{done}} de {{total}} reportados": "{{done}} of {{total}} reported", "Documentos reportados": "Documents reported",
  "Cómo llegar": "Directions", "Cómo llegar a {{place}}": "Directions to {{place}}",
  "Cerrado por oficina": "Closed by the office", "{{n}} fotos o firmas": "{{n}} photos or signatures",
  "Siguiente pendiente": "Next pending", "Siguiente pendiente: {{doc}}": "Next pending: {{doc}}",
  "Siguiente documento": "Next document", "No quedan documentos pendientes en este viaje.": "No documents left to report on this trip.",
  // Hoja de reporte
  "Guardar rechazo": "Save rejection", "Guardar no entrega": "Save non-delivery", "Guardar sin conexión": "Save offline",
  "Guardado sin conexión. Se enviará al recuperar señal.": "Saved offline. It will be sent when the signal is back.",
  "Sin conexión. El reporte se guardará en el teléfono y se enviará al recuperar señal.":
    "Offline. The report will be saved on the phone and sent when the signal is back.",
  "No se pudo guardar en el teléfono. Libera espacio o vuelve a intentarlo con señal.":
    "Could not save on the phone. Free up space or try again with signal.",
  "Añadir firma del cliente (opcional)": "Add customer signature (optional)", "Quitar firma": "Remove signature",
  "{{n}}/{{max}} fotos": "{{n}}/{{max}} photos",
};
