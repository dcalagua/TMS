// ── Arreglos rápidos de QAS (D1–D5) y filtros en la URL (ítem 11) ──
// Su propio mapa, como los demás módulos recientes; `i18n.ts` solo lo funde. La clave es el español.
export const EN_QUICK_WINS: Record<string, string> = {
  // D3: los roles del catálogo, por su código
  "Administrador de la organización": "Organization administrator",
  "Administrador de la empresa": "Company administrator",
  "Planificador": "Planner",
  "Consulta": "Viewer",
  "Operador del transportista": "Carrier operator",
  "Control total de una organización: sus empresas, usuarios, accesos y datos operativos.":
    "Full control of one organization: its companies, users, memberships and operational data.",
  "Control total de una empresa: datos maestros, flota, pedidos, planificación y los accesos de esa empresa.":
    "Full control of one company: master data, fleet, orders, planning, and the memberships of that company.",
  "Lee datos maestros y flota, gestiona pedidos y arma viajes en una empresa.":
    "Reads master data and fleet, manages orders and builds trips for one company.",
  "Acceso de solo lectura a los datos operativos de una empresa.": "Read-only access to the operational data of one company.",
  "Usuario de un transportista: ve los viajes despachados asignados a su transportista y reporta la entrega de sus documentos. Nada más.":
    "A user of a carrier company: sees the dispatched trips assigned to that carrier and reports the delivery of their documents. Nothing else.",

  // D4: el catálogo de alcances de una credencial
  "ERP": "ERP",
  "Almacén (EWM)": "Warehouse (EWM)",
  "Transportista y telemetría": "Carrier and telematics",
  "Finanzas": "Finance",
  "Otros (fuera del catálogo actual)": "Other (not in the current catalogue)",
  "Cargando los alcances...": "Loading the scopes...",
  "No se pudieron cargar los alcances. Cierra y vuelve a abrir el formulario.":
    "The scopes could not be loaded. Close and reopen the form.",
  "Plantilla: conector EWM": "Template: EWM connector",
  "Marca los {{count}} alcances que necesita el almacén.": "Ticks the {{count}} scopes the warehouse needs.",
  "Registrar documentos": "Record documents",
  "Registrar los documentos que el ERP emitió para los pedidos (factura, guía de remisión). TMS los recibe; no emite ninguno.":
    "Record the documents the ERP issued for orders (invoice, remission guide). TMS receives them; it issues none.",
  "Reportar despachos": "Report dispatches",
  "Informar lo que el almacén despachó físicamente. Solo escritura: para leer el plan contra el que despacha, la credencial necesita también «Leer embarques».":
    "Report what the warehouse physically dispatched. Write-only: to read the plan it dispatches against, the credential also needs \"Read shipments\".",
  "Reportar hitos del almacén": "Report warehouse milestones",
  "Informar que la carga empezó, quedó lista o se anuló. Solo escritura.":
    "Report that loading started, the load is ready or it was cancelled. Write-only.",
  "Escribir perfiles de material": "Write material profiles",
  "Escribir el perfil físico que midió el almacén: dimensiones, peso y apilado. Solo escritura.":
    "Write the physical profile the warehouse measured: dimensions, weight and stacking. Write-only.",
  "Leer pedidos": "Read orders",
  "Leer los pedidos de transporte, uno por número o el feed por estado y fecha. Solo lectura.":
    "Read transport orders, one by number or the feed by status and date. Read-only.",
  "Reportar eventos de pedido del almacén": "Report warehouse order events",
  "Informar que el almacén anuló su pedido o lo cerró con saldo. Como mucho genera una retención: TMS nunca anula por sí solo.":
    "Report that the warehouse cancelled its order or closed it short. At most it places a hold: TMS never cancels by itself.",
  "Registrar pagos de facturas": "Record invoice payments",
  "El ERP informa lo que pagó contra una factura de transportista exportada. TMS lo registra y no mueve dinero.":
    "The ERP reports what it paid against an exported carrier invoice. TMS records it and moves no money.",

  // D5: «Mi cuenta»
  "Identidad, contraseña y apariencia": "Identity, password and appearance",
  "Cambiar la contraseña": "Change the password",
  "Contraseña actual": "Current password",
  "La contraseña actual es obligatoria": "The current password is required",
  "La contraseña actual no es correcta.": "The current password is not correct.",
  "La nueva contraseña debe ser distinta de la actual.": "The new password must differ from the current one.",
  "La sesión no tiene correo: vuelve a iniciar sesión para cambiar la contraseña.":
    "The session has no email: sign in again to change the password.",
  "Contraseña actualizada": "Password updated",
  "La próxima vez entra con la nueva.": "Sign in with the new one next time.",
  "Cambiar contraseña": "Change password",
};
