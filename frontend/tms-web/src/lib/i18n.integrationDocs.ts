// ── Integraciones › Documentación ──
// Los ejemplos de petición y respuesta por alcance (entrada) y por evento (salida). Su propio mapa,
// fundido en `i18n.ts`. La clave sigue siendo el texto en español; `integrationDocs.test.ts` comprueba
// que cada texto del catálogo tenga aquí su traducción.
export const EN_INTEGRATION_DOCS: Record<string, string> = {
  // ── Panel ──
  "Documentación": "Documentation",
  "Solicitud": "Request",
  "Respuesta": "Response",
  "URL base": "Base URL",
  "Autenticación": "Authentication",
  "Formato": "Format",
  "Idempotencia": "Idempotency",
  "Idempotency-Key opcional, 8 a 128 caracteres": "Optional Idempotency-Key, 8 to 128 characters",
  "Conexión": "Connection",
  "Errores comunes": "Common errors",
  "Formato de error (RFC 9457)": "Error format (RFC 9457)",
  "Decide por code, nunca por detail. 400, 403, 404 y 409 no se reintentan; 5xx sí, con la misma Idempotency-Key.":
    "Branch on code, never on detail. 400, 403, 404 and 409 are not retried; 5xx are, with the same Idempotency-Key.",
  "Esta credencial está revocada: cualquier llamada con ella responde 401.":
    "This credential is revoked: any call made with it answers 401.",
  "Sin ejemplo documentado para este alcance todavía.": "No documented example for this scope yet.",
  "Sin ejemplo documentado para este evento todavía.": "No documented example for this event yet.",
  "secreto": "secret",
  "clientId": "clientId",
  "URL destino": "Target URL",
  "Método": "Method",
  "Deduplicación": "Deduplication",
  "Por X-TMS-Event-Id: la entrega es al menos una vez": "On X-TMS-Event-Id: delivery is at least once",
  "Lo que envía TMS": "What TMS sends",
  "Respuesta esperada": "Expected response",
  "HTTP/1.1 200 OK\n\n(cualquier 2xx; el cuerpo se descarta)": "HTTP/1.1 200 OK\n\n(any 2xx; the body is discarded)",
  "Después, el receptor consulta (API de integración, alcance)": "Then the receiver reads (integration API, scope)",
  "Firma y reintentos": "Signature and retries",
  "Verificar la firma": "Verifying the signature",
  "HMAC-SHA-256 sobre el cuerpo tal como llegó": "HMAC-SHA-256 over the body exactly as received",
  "// X-TMS-Signature: t=<segundos unix>,v1=<hex>\nfirma = hex(HMAC_SHA256(secreto, t + \".\" + cuerpoCrudo))\n\n1. Lee t y v1 de la cabecera.\n2. Rechaza si t se aleja más de 5 minutos de tu reloj.\n3. Compara firma con v1 en tiempo constante.\n4. Solo entonces parsea el JSON.":
    "// X-TMS-Signature: t=<unix seconds>,v1=<hex>\nsignature = hex(HMAC_SHA256(secret, t + \".\" + rawBody))\n\n1. Read t and v1 from the header.\n2. Reject it if t is more than 5 minutes away from your clock.\n3. Compare signature with v1 in constant time.\n4. Only then parse the JSON.",
  "Qué hace TMS con tu respuesta": "What TMS does with your response",

  // ── Respuestas del webhook ──
  "5xx, 408, 425, 429, timeout o sin conexión": "5xx, 408, 425, 429, timeout or no connection",
  "Otro 4xx (incl. 410) o 3xx": "Any other 4xx (incl. 410) or 3xx",
  "Entregado. El cuerpo de la respuesta se descarta.": "Delivered. The response body is discarded.",
  "Se reintenta: ~1, 3, 7, 15 y 45 minutos; luego queda FAILED.": "Retried at ~1, 3, 7, 15 and 45 minutes; then it is FAILED.",
  "Fallo definitivo, no se reintenta. Las redirecciones nunca se siguen.": "Permanent failure, not retried. Redirects are never followed.",

  // ── Comunes ──
  "Comprobar la credencial": "Check the credential",
  "Credencial válida": "Valid credential",
  "No escribe nada. La empresa sale de la credencial: no se envía X-Company-Id.":
    "It writes nothing. The company comes from the credential: X-Company-Id is not sent.",
  "Credencial ausente, mal formada, revocada o con secreto incorrecto": "Credential missing, malformed, revoked or with a wrong secret",
  "Campo inválido u obligatorio ausente": "Invalid field or missing required field",
  "Idempotency-Key reutilizada con otro cuerpo": "Idempotency-Key reused with another body",

  // ── integration.location:write ──
  "Crear y actualizar ubicaciones (tiendas, almacenes, plantas). Es un upsert: la identidad es (externalSystem, externalReference) o, sin ella, el código.":
    "Create and update locations (stores, warehouses, plants). It is an upsert: the identity is (externalSystem, externalReference) or, without it, the code.",
  "Crear o actualizar una ubicación": "Create or update a location",
  "Creada (200 si ya existía: UPDATED o UNCHANGED)": "Created (200 if it existed: UPDATED or UNCHANGED)",
  "El contacto de entrega de la ubicación se mantiene en TMS: este contrato no lo envía ni lo borra.":
    "The location's delivery contact is kept in TMS: this contract neither sends nor clears it.",
  "Lote de ubicaciones": "Batch of locations",
  "Alguna fila rechazada (200 si entraron todas)": "Some item refused (200 if all went in)",
  "Hasta 200 elementos por lote. Cada elemento va en su propia transacción.": "Up to 200 items per batch. Each item runs in its own transaction.",

  // ── integration.order:write ──
  "Crear y actualizar pedidos de transporte. La identidad es (externalSource, externalReference); reenviar el mismo pedido lo actualiza.":
    "Create and update transport orders. The identity is (externalSource, externalReference); sending the same order again updates it.",
  "Crear o actualizar un pedido": "Create or update an order",
  "Creado (200 si ya existía)": "Created (200 if it existed)",
  "Creado (200 si ya existía: UPDATED o UNCHANGED)": "Created (200 if it existed: UPDATED or UNCHANGED)",
  "El pedido ya está planificado o anulado": "The order is already planned or cancelled",
  "Las medidas físicas de la línea (lengthM, widthM, heightM, stackMode…) son opcionales; si no vienen se copia el perfil del material del almacén.":
    "The line's physical measures (lengthM, widthM, heightM, stackMode…) are optional; without them the warehouse's material profile is copied.",
  "releaseRefusedBy lista por qué no se liberó cuando se pidió markReadyForPlanning y el pedido quedó NOT_READY.":
    "releaseRefusedBy lists why the order was not released when markReadyForPlanning was asked and it stayed NOT_READY.",
  "Lote de pedidos": "Batch of orders",
  "Algún pedido rechazado (200 si entraron todos)": "Some order refused (200 if all went in)",

  // ── integration.shipment:read ──
  "Leer los embarques confirmados (el plan de transporte) y el feed de cambios. Solo lectura; un embarque en borrador no existe para esta API.":
    "Read confirmed shipments (the transport plan) and the change feed. Read-only; a draft shipment does not exist for this API.",
  "Listar embarques": "List shipments",
  "Página de cabeceras": "Page of headers",
  "status admite CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT, COMPLETED y CANCELLED (por defecto CONFIRMED). Orden: el menos reciente primero.":
    "status accepts CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT, COMPLETED and CANCELLED (CONFIRMED by default). Order: least recent first.",
  "Un embarque con sus paradas y pedidos": "One shipment with its stops and orders",
  "El plan completo": "The full plan",
  "No existe o sigue en borrador": "It does not exist or is still a draft",
  "delivery es null hasta que se registra la entrega del pedido.": "delivery is null until the order's delivery is recorded.",
  "Feed de cambios": "Change feed",
  "Eventos, el más antiguo primero": "Events, oldest first",
  "El evento dice que algo cambió, no qué: se relee el embarque con GET /shipments/{shipmentNumber}.":
    "The event says something changed, not what: read the shipment again with GET /shipments/{shipmentNumber}.",

  // ── integration.tracking:write ──
  "Reportar posiciones GPS de los vehículos de un embarque en curso. No da lectura de nada.":
    "Report GPS positions of the vehicles of a shipment under way. It grants no read.",
  "Enviar posiciones": "Send positions",
  "Todas aceptadas (207 si alguna se rechazó)": "All accepted (207 if any was refused)",
  "outcome: RECORDED, DUPLICATE, THINNED o STALE (aceptadas); UNKNOWN_SHIPMENT, NOT_TRACKABLE o INVALID (rechazadas).":
    "outcome: RECORDED, DUPLICATE, THINNED or STALE (accepted); UNKNOWN_SHIPMENT, NOT_TRACKABLE or INVALID (refused).",

  // ── integration.tender:respond ──
  "Ver y responder las ofertas de viaje hechas a un transportista. La credencial debe tener un transportista asignado.":
    "See and answer the trip offers made to a carrier. The credential must have a carrier assigned.",
  "Ofertas pendientes de este transportista": "This carrier's open offers",
  "Ofertas que aún se pueden responder": "Offers that can still be answered",
  "?shipmentNumber=SH-… trae solo esa oferta. Una oferta vencida ya no aparece.": "?shipmentNumber=SH-… fetches only that offer. An expired offer no longer appears.",
  "Aceptar o rechazar una oferta": "Accept or reject an offer",
  "Respuesta registrada": "Answer recorded",
  "La oferta ya no se puede responder, o attempt no es el vigente": "The offer can no longer be answered, or attempt is not the current one",
  "decision: ACCEPTED o REJECTED. reason es obligatorio al rechazar. Envía siempre el attempt de la oferta que respondes.":
    "decision: ACCEPTED or REJECTED. reason is required when rejecting. Always send the attempt of the offer you answer.",

  // ── integration.dispatch:write ──
  "El almacén informa lo que despachó físicamente (documento SLS). TMS lo concilia contra el plan y, según el modo de despacho de la empresa, despacha el viaje.":
    "The warehouse reports what it physically dispatched (SLS document). TMS reconciles it against the plan and, depending on the company's dispatch mode, dispatches the trip.",
  "Confirmar un despacho": "Confirm a dispatch",
  "Recibido por primera vez (200 si se repite)": "First receipt (200 on a repeat)",
  "outcome: APPLIED, RECONCILED, UNAPPLIED, RECORDED_UNMATCHED, UNCHANGED o STALE.":
    "outcome: APPLIED, RECONCILED, UNAPPLIED, RECORDED_UNMATCHED, UNCHANGED or STALE.",
  "Una discrepancia nunca es un 4xx: el despacho se registra igual. Una corrección se envía con la misma dispatchReference y revision + 1.":
    "A discrepancy is never a 4xx: the dispatch is recorded anyway. A correction is sent with the same dispatchReference and revision + 1.",

  // ── integration.warehouse-milestone:write ──
  "Hitos del almacén: inicio de carga, carga lista, carga anulada. Informativos: nunca mueven un estado de TMS.":
    "Warehouse milestones: loading started, load ready, load cancelled. Informative: they never move a TMS status.",
  "Enviar hitos": "Send milestones",
  "Todos registrados (207 si alguno se rechazó)": "All recorded (207 if any was refused)",
  "type: LOADING_STARTED, LOAD_READY o LOAD_CANCELLED. result: RECORDED, DUPLICATE, UNKNOWN_SHIPMENT o INVALID.":
    "type: LOADING_STARTED, LOAD_READY or LOAD_CANCELLED. result: RECORDED, DUPLICATE, UNKNOWN_SHIPMENT or INVALID.",

  // ── integration.document:write ──
  "Registrar los documentos que emitió el ERP para los pedidos (factura, guía de remisión…). TMS los recibe y los muestra; no emite ni valida nada ante SUNAT o el SRI.":
    "Record the documents the ERP issued for the orders (invoice, remission guide…). TMS receives and shows them; it issues or validates nothing with SUNAT or the SRI.",
  "Registrar un documento": "Record a document",
  "documentType: INVOICE, DELIVERY_NOTE, REMISSION_GUIDE, GRE_REMITENTE, GRE_TRANSPORTISTA u OTHER.":
    "documentType: INVOICE, DELIVERY_NOTE, REMISSION_GUIDE, GRE_REMITENTE, GRE_TRANSPORTISTA or OTHER.",
  "Un pedido aún desconocido vuelve en unknownOrders y se enlaza al reenviar el documento.":
    "An order not yet known comes back in unknownOrders and is linked when the document is sent again.",
  "Campos fiscales opcionales: fiscalCountry, fiscalDocumentCode, authorizationNumber, transportStartDate, carrierTaxId, vehiclePlate, recipients[]…":
    "Optional fiscal fields: fiscalCountry, fiscalDocumentCode, authorizationNumber, transportStartDate, carrierTaxId, vehiclePlate, recipients[]…",

  // ── integration.material:write ──
  "El almacén escribe el perfil físico de sus materiales (medidas, apilado). TMS lo copia a las líneas de los pedidos nuevos.":
    "The warehouse writes the physical profile of its materials (measures, stacking). TMS copies it onto the lines of new orders.",
  "Crear o actualizar un perfil": "Create or update a profile",
  "Un campo ausente conserva el valor guardado; un null explícito lo borra. outcome: CREATED, UPDATED, UNCHANGED o STALE.":
    "An absent field keeps the stored value; an explicit null clears it. outcome: CREATED, UPDATED, UNCHANGED or STALE.",
  "Lote de perfiles": "Batch of profiles",
  "Todos válidos (207 si alguno es INVALID)": "All valid (207 if any is INVALID)",
  "Hasta 500 perfiles por lote.": "Up to 500 profiles per batch.",

  // ── integration.order:read ──
  "Leer el estado de los pedidos de transporte, uno por número o el listado por estado. Lo que consulta el almacén tras un webhook ORDER_CANCELLED.":
    "Read the status of transport orders, one by number or the list by status. What the warehouse reads after an ORDER_CANCELLED webhook.",
  "Un pedido por número": "One order by number",
  "Estado del pedido": "Order status",
  "Pedidos por estado": "Orders by status",
  "Página de pedidos": "Page of orders",
  "Sin status devuelve los CANCELLED. Ordenados por updatedAt y número de pedido.":
    "Without status it returns the CANCELLED ones. Ordered by updatedAt and order number.",

  // ── integration.warehouse-order-event:write ──
  "El almacén informa que anuló su pedido o lo cerró con faltante. TMS nunca anula por su cuenta: pone una retención y decide una persona.":
    "The warehouse reports that it cancelled its order or closed it short. TMS never cancels by itself: it places a hold and a person decides.",
  "Enviar eventos de pedido": "Send order events",
  "type: ORDER_CANCELLED u ORDER_SHORT_CLOSED.": "type: ORDER_CANCELLED or ORDER_SHORT_CLOSED.",
  "result: HOLD_PLACED, ALREADY_HELD, NOTED, ORDER_CLOSED, UNKNOWN_ORDER, DUPLICATE o INVALID.":
    "result: HOLD_PLACED, ALREADY_HELD, NOTED, ORDER_CLOSED, UNKNOWN_ORDER, DUPLICATE or INVALID.",

  // ── integration.payable-payment:write ──
  "El ERP informa lo que pagó contra una factura de transportista ya exportada. TMS lo registra y no mueve dinero.":
    "The ERP reports what it paid against an exported carrier invoice. TMS records it and moves no money.",
  "Registrar un pago": "Record a payment",
  "Pago registrado (200 DUPLICATE si se repite)": "Payment recorded (200 DUPLICATE on a repeat)",
  "La factura se identifica con uno solo de: invoiceId; carrierTaxId + documentType + documentNumber; o carrierCode + invoiceNumber.":
    "The invoice is named by exactly one of: invoiceId; carrierTaxId + documentType + documentNumber; or carrierCode + invoiceNumber.",
  "422 payment-exceeds-balance si el monto supera el saldo; 409 si la factura aún no se exportó.":
    "422 payment-exceeds-balance when the amount exceeds the balance; 409 when the invoice has not been exported yet.",

  // ── Eventos ──
  "El plan se confirmó: el viaje pasa a ser un embarque comprometido.": "The plan was confirmed: the trip becomes a committed shipment.",
  "El viaje quedó cargado y listo para salir.": "The trip is loaded and ready to leave.",
  "El viaje salió.": "The trip left.",
  "El viaje terminó.": "The trip finished.",
  "Se canceló un viaje que ya estaba confirmado.": "A trip that was already confirmed was cancelled.",
  "Cambió el conductor de un embarque confirmado o listo para despacho. Lo que lleva el embarque no cambia.":
    "The driver of a confirmed or ready-for-dispatch shipment changed. What the shipment carries does not change.",
  "Se registró o corrigió el resultado de entrega de un pedido del viaje.": "The delivery result of an order on the trip was recorded or corrected.",
  "El resultado está en orders[].delivery del embarque. Nunca mueve inventario en el almacén.":
    "The result is in the shipment's orders[].delivery. It never moves stock in the warehouse.",
  "Se ofreció el viaje a un transportista.": "The trip was offered to a carrier.",
  "El transportista aceptó la oferta.": "The carrier accepted the offer.",
  "El transportista rechazó la oferta.": "The carrier rejected the offer.",
  "La oferta venció sin respuesta.": "The offer expired unanswered.",
  "La oferta se retiró antes de que el transportista respondiera.": "The offer was withdrawn before the carrier answered.",
  "Se anuló un pedido de transporte en TMS. Es una señal, no una orden: el almacén alerta y decide.":
    "A transport order was cancelled in TMS. It is a signal, not a command: the warehouse raises an alert and decides.",
  "Una persona aprobó una factura, nota de crédito o de débito de un transportista.": "A person approved a carrier's invoice, credit note or debit note.",
  "La factura aprobada se entregó a quien paga.": "The approved invoice was handed to whoever pays.",
  "Los pagos registrados cubren el saldo de la factura. TMS no mueve dinero.": "The recorded payments cover the invoice's balance. TMS moves no money.",
  "No hay endpoint de lectura de facturas: resource.id es el mismo invoiceId de la exportación y el que acepta POST /payable-payments.":
    "There is no invoice read endpoint: resource.id is the same invoiceId as the export's and the one POST /payable-payments accepts.",
  "Leer el estado actual del embarque": "Read the shipment's current state",
  "Leer la oferta": "Read the offer",
  "La oferta, si aún se puede responder": "The offer, if it can still be answered",
  "Leer el pedido anulado": "Read the cancelled order",
};
