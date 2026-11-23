/**
 * Ejemplos de petición y respuesta de la API de integración, por alcance (entrada) y por evento
 * (salida), para la acción «Documentación» de Integraciones.
 *
 * Es documentación, no un contrato: los contratos de verdad son `docs/integrations/INBOUND_API_V1.md`,
 * `OUTBOUND_SHIPMENT_V1.md`, `WAREHOUSE_EXECUTION_V1.md` y `WEBHOOKS_V1.md`, y los DTO del backend
 * (`com.ebim.tms.integration.application.*V1`). Cada cuerpo de aquí tiene la forma de esos DTO —
 * mismos campos, mismos nombres, `null` donde el backend manda `null` en vez de omitir — con datos
 * de ejemplo. Un alcance o un evento que el backend añada y que aquí falte se ve en el panel como
 * «sin ejemplo documentado», nunca como un error.
 */

import { appEnv } from "../../shared/config/env";

export type HttpMethod = "GET" | "POST" | "PUT";

export interface DocResponse {
  status: number;
  /** Cuándo se da esta respuesta (p. ej. «Creada»). */
  label: string;
  body?: unknown;
}

export interface DocExchange {
  key: string;
  title: string;
  method: HttpMethod;
  /** Relativo a la base `/integration/v1`, con su query string si la lleva. */
  path: string;
  /** Cabeceras además de `Authorization` (que añade el panel con el clientId de la credencial). */
  headers?: Record<string, string>;
  body?: unknown;
  responses: DocResponse[];
  notes?: string[];
}

export interface ScopeDoc {
  code: string;
  summary: string;
  exchanges: DocExchange[];
}

export interface EventDoc {
  type: string;
  summary: string;
  resource: { type: string; id: string; reference: string };
  /** Lo que el receptor consulta después para saber el estado actual, y el alcance que necesita. */
  followUp?: { scope: string; exchange: DocExchange };
  notes?: string[];
}

export const INTEGRATION_BASE_PATH = "/integration/v1";

/**
 * La URL base de la API de integración de este despliegue: la misma máquina que sirve `/api/v1`,
 * bajo `/integration/v1`. Si la base de la API es relativa (detrás del mismo proxy que la web), se
 * completa con el origen de la página, que es lo que un socio tendría que escribir.
 */
export function integrationBaseUrl(apiBaseUrl: string = appEnv.apiBaseUrl): string {
  const host = apiBaseUrl.replace(/\/api\/v1\/?$/, "");
  const absolute = /^https?:\/\//.test(host) ? host : `${window.location.origin}${host}`;
  return `${absolute}${INTEGRATION_BASE_PATH}`;
}

// ---------------------------------------------------------------------------
// Datos de ejemplo compartidos: el mismo embarque, pedido y ubicaciones en todos los ejemplos,
// para que una respuesta de un alcance se pueda seguir en la petición de otro.
// ---------------------------------------------------------------------------

const COMPANY_ID = "6b1c2d3e-4a5b-4c7d-8e9f-0a1b2c3d4e5f";
const SHIPMENT_ID = "8a1f0c2e-5b7d-4c1e-9f3a-2d6b8e0c4a11";
const SHIPMENT_NUMBER = "SH-00000042";
const ORDER_ID = "c92f7a13-4e8b-4d2a-a1c5-7f0e3b9d6a23";
const ORDER_NUMBER = "TO-00010011";
const INVOICE_ID = "0b5a3c2e-8f1e-4d0a-9b8e-3c6d2e1f4a77";
const ERP_SOURCE = "SAPB1_PE";
const ERP_REFERENCE = "PED-1011";

const DRIVER = { code: "DRV-007", name: "Juan Pérez", documentNumber: "45781236" };

const SHIPMENT_HEADER = {
  id: SHIPMENT_ID,
  companyCode: "CO-LIMA",
  companyName: "EBIM Perú",
  shipmentNumber: SHIPMENT_NUMBER,
  planNumber: "PL-00000017",
  planningDate: "2026-11-24",
  status: "CONFIRMED",
  originCode: "CD-LIMA",
  originName: "Centro de distribución Lima",
  originLatitude: -12.0464,
  originLongitude: -77.0428,
  plannedDepartureAt: "2026-11-24T08:00:00-05:00",
  readyAt: null,
  actualDepartureAt: null,
  actualCompletionAt: null,
  carrierCode: "TRSA",
  carrierName: "Transportes SA",
  vehicleCode: "VH-014",
  vehicleLicensePlate: "B7K-812",
  vehicleTypeCode: "TRUCK-8T",
  capacitySource: "SNAPSHOT",
  maxWeightKg: 8000.0,
  maxVolumeM3: 32.0,
  maxPallets: 18,
  usedWeightKg: 6120.0,
  usedVolumeM3: 21.4,
  usedPallets: 12.5,
  weightUtilizationPct: 76.5,
  volumeUtilizationPct: 66.9,
  palletsUtilizationPct: 69.4,
  stopCount: 2,
  orderCount: 3,
  version: 4,
  createdAt: "2026-11-23T14:02:11Z",
  updatedAt: "2026-11-24T06:15:47Z",
  originExternalReference: "CD01",
  routeCode: "RT-LIMA-SUR",
  driver: DRIVER,
};

const SHIPMENT_PLAN = {
  shipment: SHIPMENT_HEADER,
  stops: [
    {
      locationId: "b41d8e02-6c3a-4f1b-8d2e-0a9c4b7e4711",
      sequence: 1,
      locationCode: "ST-4711",
      locationName: "Tienda Miraflores",
      latitude: -12.1215,
      longitude: -77.0297,
      serviceWindowStart: "08:00:00",
      serviceWindowEnd: "12:00:00",
    },
    {
      locationId: "b41d8e02-6c3a-4f1b-8d2e-0a9c4b7e4712",
      sequence: 2,
      locationCode: "ST-4712",
      locationName: "Tienda Surco",
      latitude: null,
      longitude: null,
      serviceWindowStart: null,
      serviceWindowEnd: null,
    },
  ],
  orders: [
    {
      orderId: ORDER_ID,
      orderNumber: ORDER_NUMBER,
      externalSource: ERP_SOURCE,
      externalReference: ERP_REFERENCE,
      destinationCode: "ST-4711",
      weightKg: 420.5,
      volumeM3: 1.8,
      pallets: 2,
      deliveryResult: "PARTIAL",
      deliveredAt: "2026-11-24T10:42:00-05:00",
      deliveryReceiverName: "M. Quispe",
      deliveryNotes: "Devolvieron 10 unidades con empaque dañado",
      evidenceCount: 2,
      stopSequence: 1,
      delivery: {
        result: "PARTIAL",
        deliveredAt: "2026-11-24T10:42:00-05:00",
        receiverName: "M. Quispe",
        evidenceCount: 2,
        attempted: { weightKg: 420.5, volumeM3: 1.8, pallets: 2 },
        delivered: { weightKg: 380.0, volumeM3: 1.6, pallets: 2 },
        refused: { weightKg: 40.5, volumeM3: 0.2, pallets: 0 },
        lines: [
          {
            lineNumber: 1, materialCode: "SKU-100", uom: "UN",
            quantityAttempted: 100, quantityDelivered: 90, quantityRefused: 10,
          },
        ],
      },
    },
    {
      orderId: "c92f7a13-4e8b-4d2a-a1c5-7f0e3b9d6a24",
      orderNumber: "TO-00010012",
      externalSource: ERP_SOURCE,
      externalReference: "PED-1012",
      destinationCode: "ST-4712",
      weightKg: 860.0,
      volumeM3: 3.1,
      pallets: 4,
      deliveryResult: null,
      deliveredAt: null,
      deliveryReceiverName: null,
      deliveryNotes: null,
      evidenceCount: 0,
      stopSequence: 2,
      delivery: null,
    },
  ],
};

const TENDER_OFFER = {
  shipmentNumber: SHIPMENT_NUMBER,
  attempt: 2,
  status: "SENT",
  planningDate: "2026-11-24",
  plannedDepartureAt: "2026-11-24T08:00:00-05:00",
  originCode: "CD-LIMA",
  originName: "Centro de distribución Lima",
  stopCount: 2,
  offeredAmount: 1240.0,
  currency: "PEN",
  notes: "Carga 06:00, puerta B",
  sentAt: "2026-11-23T14:10:00Z",
  expiresAt: "2026-11-23T22:00:00Z",
  respondedAt: null,
  responseNotes: null,
};

const ORDER_STATUS = {
  orderNumber: ORDER_NUMBER,
  externalSource: ERP_SOURCE,
  externalReference: ERP_REFERENCE,
  status: "CANCELLED",
  cancelledAt: "2026-11-23T09:12:44Z",
  cancelReason: "Cliente anuló la compra",
  updatedAt: "2026-11-23T09:12:44Z",
};

// ---------------------------------------------------------------------------
// Entrada: los intercambios comunes a toda credencial.
// ---------------------------------------------------------------------------

/** `GET /ping`: no exige alcance. El panel lo rellena con el clientId, el nombre y los alcances reales. */
export function pingExchange(clientId: string, name: string, scopes: string[]): DocExchange {
  return {
    key: "ping",
    title: "Comprobar la credencial",
    method: "GET",
    path: "/ping",
    responses: [
      {
        status: 200,
        label: "Credencial válida",
        body: {
          clientId,
          name,
          companyCode: "CO-LIMA",
          companyName: "EBIM Perú",
          companyTimeZone: "America/Lima",
          scopes: [...scopes].sort(),
        },
      },
    ],
    notes: ["No escribe nada. La empresa sale de la credencial: no se envía X-Company-Id."],
  };
}

/** Los errores con la misma forma para todos los alcances (RFC 9457). */
export const COMMON_ERRORS: DocResponse[] = [
  {
    status: 401,
    label: "Credencial ausente, mal formada, revocada o con secreto incorrecto",
    body: {
      type: "urn:tms:problem:unauthenticated",
      title: "Authentication required",
      status: 401,
      detail: "The integration credential is missing, malformed, revoked or not valid.",
      instance: "/integration/v1/orders",
      code: "unauthenticated",
      timestamp: "2026-11-24T11:04:22.317Z",
      correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
    },
  },
  {
    status: 400,
    label: "Campo inválido u obligatorio ausente",
    body: {
      type: "urn:tms:problem:validation-failed",
      title: "Request validation failed",
      status: 400,
      detail: "One or more fields are not valid.",
      instance: "/integration/v1/locations",
      code: "validation-failed",
      timestamp: "2026-11-24T11:04:22.317Z",
      correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
      errors: [{ field: "timeZone", message: "must not be blank" }],
    },
  },
  {
    status: 409,
    label: "Idempotency-Key reutilizada con otro cuerpo",
    body: {
      type: "urn:tms:problem:idempotency-key-reused",
      title: "Idempotency key reused",
      status: 409,
      detail: "Idempotency-Key 'erp-2026-11-24-orders-0042' was already used by this client for a different payload. Use a new key for a new object.",
      instance: "/integration/v1/orders",
      code: "idempotency-key-reused",
      timestamp: "2026-11-24T11:07:03.882Z",
      correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
    },
  },
];

// ---------------------------------------------------------------------------
// Entrada: un bloque por alcance, en el orden del enum IntegrationScope.
// ---------------------------------------------------------------------------

const LOCATION = {
  code: "ST-4711",
  name: "Tienda Miraflores",
  type: "STORE",
  roles: ["DESTINATION"],
  address: "Av. Larco 1234",
  addressReference: "Frente al parque Kennedy",
  district: "Miraflores",
  province: "Lima",
  department: "Lima",
  country: "PE",
  timeZone: "America/Lima",
  latitude: -12.1215,
  longitude: -77.0297,
  zoneCode: "ZN-LIMA-SUR",
  serviceTimeMinutes: 25,
  externalSystem: ERP_SOURCE,
  externalReference: "4711",
  active: true,
};

const ORDER = {
  externalSource: ERP_SOURCE,
  externalReference: ERP_REFERENCE,
  originCode: "CD-LIMA",
  destinationCode: "ST-4711",
  customerName: "Retail Andino S.A.C.",
  customerReference: "OC-88213",
  serviceDate: "2026-11-24",
  priority: "NORMAL",
  requestedWindowStart: "08:00",
  requestedWindowEnd: "12:00",
  lines: [
    {
      materialCode: "SKU-100", materialDescription: "Agua 625 ml x15",
      quantity: 100, uom: "UN", unitWeightKg: 4.2, unitVolumeM3: 0.018, palletQuantity: 0.02,
    },
    {
      materialCode: "PLP-1601", materialDescription: "Balde 20 L",
      quantity: 240, uom: "UN", lengthM: 0.32, widthM: 0.32, heightM: 0.29,
      stackMode: "NESTABLE", nestIncrementM: 0.03, stackMaxQuantity: 40,
    },
  ],
  markReadyForPlanning: true,
};

export const SCOPE_DOCS: ScopeDoc[] = [
  {
    code: "integration.location:write",
    summary: "Crear y actualizar ubicaciones (tiendas, almacenes, plantas). Es un upsert: la identidad es (externalSystem, externalReference) o, sin ella, el código.",
    exchanges: [
      {
        key: "location-upsert",
        title: "Crear o actualizar una ubicación",
        method: "POST",
        path: "/locations",
        headers: { "Idempotency-Key": "erp-loc-ST-4711-v3" },
        body: LOCATION,
        responses: [
          {
            status: 201,
            label: "Creada (200 si ya existía: UPDATED o UNCHANGED)",
            body: { id: "b41d8e02-6c3a-4f1b-8d2e-0a9c4b7e4711", code: "ST-4711", outcome: "CREATED" },
          },
        ],
        notes: ["El contacto de entrega de la ubicación se mantiene en TMS: este contrato no lo envía ni lo borra."],
      },
      {
        key: "location-batch",
        title: "Lote de ubicaciones",
        method: "POST",
        path: "/locations/batch",
        body: {
          locations: [
            {
              code: "ST-4711", name: "Tienda Miraflores", type: "STORE", roles: ["DESTINATION"],
              country: "PE", timeZone: "America/Lima", externalSystem: ERP_SOURCE, externalReference: "4711",
            },
            {
              code: "ST-4712", name: "Tienda Surco", type: "STORE", roles: ["DESTINATION"],
              country: "PE", timeZone: "America/Lima", zoneCode: "ZN-NO-EXISTE",
              externalSystem: ERP_SOURCE, externalReference: "4712",
            },
          ],
        },
        responses: [
          {
            status: 207,
            label: "Alguna fila rechazada (200 si entraron todas)",
            body: {
              submitted: 2,
              succeeded: 1,
              failed: 1,
              results: [
                {
                  index: 0,
                  reference: "ST-4711",
                  result: { id: "b41d8e02-6c3a-4f1b-8d2e-0a9c4b7e4711", code: "ST-4711", outcome: "UNCHANGED" },
                  error: null,
                },
                {
                  index: 1,
                  reference: "ST-4712",
                  result: null,
                  error: {
                    code: "resource-not-found",
                    message: "No zone with code 'ZN-NO-EXISTE' exists in this company.",
                    fields: [],
                  },
                },
              ],
            },
          },
        ],
        notes: ["Hasta 200 elementos por lote. Cada elemento va en su propia transacción."],
      },
    ],
  },
  {
    code: "integration.order:write",
    summary: "Crear y actualizar pedidos de transporte. La identidad es (externalSource, externalReference); reenviar el mismo pedido lo actualiza.",
    exchanges: [
      {
        key: "order-upsert",
        title: "Crear o actualizar un pedido",
        method: "POST",
        path: "/orders",
        headers: { "Idempotency-Key": "erp-ped-1011-v1" },
        body: ORDER,
        responses: [
          {
            status: 201,
            label: "Creado (200 si ya existía)",
            body: { id: ORDER_ID, orderNumber: ORDER_NUMBER, status: "READY_FOR_PLANNING", outcome: "CREATED", releaseRefusedBy: [] },
          },
          {
            status: 409,
            label: "El pedido ya está planificado o anulado",
            body: {
              type: "urn:tms:problem:conflict",
              title: "Conflicting request",
              status: 409,
              detail: "Order 'PED-1011' is PLANNED and can no longer be changed by an integration. Cancel or unassign it in TMS first.",
              instance: "/integration/v1/orders",
              code: "conflict",
              timestamp: "2026-11-24T11:04:22.317Z",
              correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
            },
          },
        ],
        notes: [
          "Las medidas físicas de la línea (lengthM, widthM, heightM, stackMode…) son opcionales; si no vienen se copia el perfil del material del almacén.",
          "releaseRefusedBy lista por qué no se liberó cuando se pidió markReadyForPlanning y el pedido quedó NOT_READY.",
        ],
      },
      {
        key: "order-batch",
        title: "Lote de pedidos",
        method: "POST",
        path: "/orders/batch",
        body: {
          orders: [
            {
              externalSource: ERP_SOURCE, externalReference: ERP_REFERENCE, originCode: "CD-LIMA",
              destinationCode: "ST-4711", serviceDate: "2026-11-24", priority: "NORMAL", declaredWeightKg: 420.5,
            },
            {
              externalSource: ERP_SOURCE, externalReference: "PED-1013", originCode: "CD-LIMA",
              destinationCode: "ST-9999", serviceDate: "2026-11-24", priority: "HIGH", declaredWeightKg: 820.0,
            },
          ],
        },
        responses: [
          {
            status: 207,
            label: "Algún pedido rechazado (200 si entraron todos)",
            body: {
              submitted: 2,
              succeeded: 1,
              failed: 1,
              results: [
                {
                  index: 0,
                  reference: ERP_REFERENCE,
                  result: { id: ORDER_ID, orderNumber: ORDER_NUMBER, status: "NOT_READY", outcome: "UPDATED", releaseRefusedBy: [] },
                  error: null,
                },
                {
                  index: 1,
                  reference: "PED-1013",
                  result: null,
                  error: {
                    code: "resource-not-found",
                    message: "No destination with code 'ST-9999' exists in this company.",
                    fields: [],
                  },
                },
              ],
            },
          },
        ],
      },
    ],
  },
  {
    code: "integration.shipment:read",
    summary: "Leer los embarques confirmados (el plan de transporte) y el feed de cambios. Solo lectura; un embarque en borrador no existe para esta API.",
    exchanges: [
      {
        key: "shipment-list",
        title: "Listar embarques",
        method: "GET",
        path: "/shipments?status=CONFIRMED&updatedSince=2026-11-24T00:00:00Z&page=0&size=50",
        responses: [{ status: 200, label: "Página de cabeceras", body: { content: [SHIPMENT_HEADER], page: 0, size: 50, totalElements: 1 } }],
        notes: ["status admite CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT, COMPLETED y CANCELLED (por defecto CONFIRMED). Orden: el menos reciente primero."],
      },
      {
        key: "shipment-detail",
        title: "Un embarque con sus paradas y pedidos",
        method: "GET",
        path: `/shipments/${SHIPMENT_NUMBER}`,
        responses: [
          { status: 200, label: "El plan completo", body: SHIPMENT_PLAN },
          {
            status: 404,
            label: "No existe o sigue en borrador",
            body: {
              type: "urn:tms:problem:resource-not-found",
              title: "Resource not found",
              status: 404,
              detail: "No confirmed or cancelled shipment 'SH-00000099' exists in this company.",
              instance: "/integration/v1/shipments/SH-00000099",
              code: "resource-not-found",
              timestamp: "2026-11-24T11:04:22.317Z",
              correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
            },
          },
        ],
        notes: ["delivery es null hasta que se registra la entrega del pedido."],
      },
      {
        key: "shipment-events",
        title: "Feed de cambios",
        method: "GET",
        path: "/shipments/events?since=2026-11-24T06:00:00Z&page=0&size=50",
        responses: [
          {
            status: 200,
            label: "Eventos, el más antiguo primero",
            body: {
              content: [
                { id: "d5e2b8f1-3c4a-4e6b-9d7f-1a2b3c4d5e01", eventType: "SHIPMENT_CONFIRMED", shipmentNumber: SHIPMENT_NUMBER, occurredAt: "2026-11-24T06:15:47Z" },
              ],
              page: 0,
              size: 50,
              totalElements: 1,
            },
          },
        ],
        notes: ["El evento dice que algo cambió, no qué: se relee el embarque con GET /shipments/{shipmentNumber}."],
      },
    ],
  },
  {
    code: "integration.tracking:write",
    summary: "Reportar posiciones GPS de los vehículos de un embarque en curso. No da lectura de nada.",
    exchanges: [
      {
        key: "tracking-positions",
        title: "Enviar posiciones",
        method: "POST",
        path: "/tracking/positions",
        body: {
          provider: "acme-telematics",
          positions: [
            {
              shipmentNumber: SHIPMENT_NUMBER, occurredAt: "2026-11-24T14:56:00Z",
              latitude: -12.046374, longitude: -77.042793, speedKph: 62.5, headingDegrees: 183.4,
              externalVehicleReference: "TRK-0431", correlationReference: "ping-991",
            },
            {
              shipmentNumber: SHIPMENT_NUMBER, occurredAt: "2026-11-24T14:56:20Z",
              latitude: -12.047102, longitude: -77.042811, speedKph: 58.0, headingDegrees: 181.0,
              externalVehicleReference: "TRK-0431", correlationReference: "ping-992",
            },
          ],
        },
        responses: [
          {
            status: 200,
            label: "Todas aceptadas (207 si alguna se rechazó)",
            body: {
              submitted: 2,
              accepted: 2,
              stored: 1,
              refused: 0,
              results: [
                { index: 0, shipmentNumber: SHIPMENT_NUMBER, outcome: "RECORDED", error: null },
                { index: 1, shipmentNumber: SHIPMENT_NUMBER, outcome: "THINNED", error: null },
              ],
            },
          },
        ],
        notes: ["outcome: RECORDED, DUPLICATE, THINNED o STALE (aceptadas); UNKNOWN_SHIPMENT, NOT_TRACKABLE o INVALID (rechazadas)."],
      },
    ],
  },
  {
    code: "integration.tender:respond",
    summary: "Ver y responder las ofertas de viaje hechas a un transportista. La credencial debe tener un transportista asignado.",
    exchanges: [
      {
        key: "tender-list",
        title: "Ofertas pendientes de este transportista",
        method: "GET",
        path: "/tenders",
        responses: [{ status: 200, label: "Ofertas que aún se pueden responder", body: [TENDER_OFFER] }],
        notes: ["?shipmentNumber=SH-… trae solo esa oferta. Una oferta vencida ya no aparece."],
      },
      {
        key: "tender-respond",
        title: "Aceptar o rechazar una oferta",
        method: "POST",
        path: `/tenders/${SHIPMENT_NUMBER}/response`,
        body: { decision: "ACCEPTED", reason: null, attempt: 2 },
        responses: [
          {
            status: 200,
            label: "Respuesta registrada",
            body: { ...TENDER_OFFER, status: "ACCEPTED", respondedAt: "2026-11-23T15:02:10Z" },
          },
          {
            status: 409,
            label: "La oferta ya no se puede responder, o attempt no es el vigente",
            body: {
              type: "urn:tms:problem:conflict",
              title: "Conflicting request",
              status: 409,
              detail: "This answer names attempt 1 on shipment SH-00000042, but the offer outstanding for this carrier is attempt 2. Re-read the offer and answer that one; a late redelivery of an older answer must not be applied to a newer offer.",
              instance: "/integration/v1/tenders/SH-00000042/response",
              code: "conflict",
              timestamp: "2026-11-23T15:02:10.114Z",
              correlationId: "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
            },
          },
        ],
        notes: ["decision: ACCEPTED o REJECTED. reason es obligatorio al rechazar. Envía siempre el attempt de la oferta que respondes."],
      },
    ],
  },
  {
    code: "integration.dispatch:write",
    summary: "El almacén informa lo que despachó físicamente (documento SLS). TMS lo concilia contra el plan y, según el modo de despacho de la empresa, despacha el viaje.",
    exchanges: [
      {
        key: "dispatch-confirmation",
        title: "Confirmar un despacho",
        method: "POST",
        path: "/dispatch-confirmations",
        headers: { "Idempotency-Key": "ewm-sls-000001-r1" },
        body: {
          sourceSystem: "EWM_EBIM",
          dispatchReference: "SLS-000001",
          revision: 1,
          transportReference: SHIPMENT_NUMBER,
          loadReference: "CRG-000001",
          warehouseCode: "CD01",
          actualDispatchAt: "2026-11-24T08:12:00-05:00",
          carrier: { code: "TRSA", name: "Transportes SA" },
          vehicle: { licensePlate: "B7K-812", trailerLicensePlate: null, type: "FURGON" },
          driver: { name: "Juan Pérez", documentNumber: null },
          sealNumber: "PRE-00991",
          transportDocumentNumber: "T001-000123",
          totals: { handlingUnits: 3, weightKg: 1180.0, volumeM3: 4.6 },
          orders: [
            {
              externalSource: ERP_SOURCE,
              externalReference: ERP_REFERENCE,
              warehouseOrderNumber: "ORR-001-00000007",
              status: "PARTIALLY_SHIPPED",
              handlingUnits: 2,
              weightKg: 790.0,
              lines: [
                { lineNumber: 1, materialCode: "SKU-100", lotCode: "L2409", quantity: 97, uom: "UN", handlingUnitCode: "OBLPN-00000001" },
              ],
            },
          ],
        },
        responses: [
          {
            status: 201,
            label: "Recibido por primera vez (200 si se repite)",
            body: {
              id: "0b7c6a1e-2d3f-4a5b-8c9d-0e1f2a3b4c5d",
              dispatchReference: "SLS-000001",
              revision: 1,
              outcome: "APPLIED",
              transportReference: SHIPMENT_NUMBER,
              verificationStatus: "MISMATCH",
              discrepancies: [
                {
                  code: "QUANTITY_VARIANCE", severity: "ERROR", orderReference: ERP_REFERENCE, lineNumber: 1,
                  planned: 100, dispatched: 97, uom: "UN", detail: null,
                },
                {
                  code: "MISSING_ORDER", severity: "ERROR", orderReference: "PED-1012", lineNumber: null,
                  planned: null, dispatched: null, uom: null, detail: null,
                },
              ],
            },
          },
        ],
        notes: [
          "outcome: APPLIED, RECONCILED, UNAPPLIED, RECORDED_UNMATCHED, UNCHANGED o STALE.",
          "Una discrepancia nunca es un 4xx: el despacho se registra igual. Una corrección se envía con la misma dispatchReference y revision + 1.",
        ],
      },
    ],
  },
  {
    code: "integration.warehouse-milestone:write",
    summary: "Hitos del almacén: inicio de carga, carga lista, carga anulada. Informativos: nunca mueven un estado de TMS.",
    exchanges: [
      {
        key: "warehouse-milestones",
        title: "Enviar hitos",
        method: "POST",
        path: "/warehouse-milestones",
        body: {
          sourceSystem: "EWM_EBIM",
          milestones: [
            {
              eventId: "8c1f2d3e-4b5a-4c6d-9e7f-0a1b2c3d4e5f", type: "LOADING_STARTED",
              transportReference: SHIPMENT_NUMBER, loadReference: "CRG-000001", warehouseCode: "CD01",
              occurredAt: "2026-11-24T07:20:00-05:00",
            },
          ],
        },
        responses: [
          {
            status: 200,
            label: "Todos registrados (207 si alguno se rechazó)",
            body: {
              received: 1,
              recorded: 1,
              refused: 0,
              results: [{ index: 0, eventId: "8c1f2d3e-4b5a-4c6d-9e7f-0a1b2c3d4e5f", result: "RECORDED", message: null }],
            },
          },
        ],
        notes: ["type: LOADING_STARTED, LOAD_READY o LOAD_CANCELLED. result: RECORDED, DUPLICATE, UNKNOWN_SHIPMENT o INVALID."],
      },
    ],
  },
  {
    code: "integration.document:write",
    summary: "Registrar los documentos que emitió el ERP para los pedidos (factura, guía de remisión…). TMS los recibe y los muestra; no emite ni valida nada ante SUNAT o el SRI.",
    exchanges: [
      {
        key: "logistics-document",
        title: "Registrar un documento",
        method: "POST",
        path: "/logistics-documents",
        headers: { "Idempotency-Key": "sap-gre-T001-000123" },
        body: {
          sourceSystem: ERP_SOURCE,
          documentType: "GRE_REMITENTE",
          documentNumber: "T001-000123",
          issueDate: "2026-11-24",
          issuerTaxId: "20600000011",
          recipientTaxId: "20512345678",
          recipientName: "Retail Andino S.A.C.",
          amount: null,
          currency: null,
          status: "ACEPTADO",
          orders: [{ externalSource: ERP_SOURCE, externalReference: ERP_REFERENCE }],
        },
        responses: [
          {
            status: 201,
            label: "Creado (200 si ya existía: UPDATED o UNCHANGED)",
            body: { id: "5d8e1c2a-7b3f-4e9d-a1c2-3b4d5e6f7a8b", outcome: "CREATED", linkedOrders: 1, unknownOrders: [] },
          },
        ],
        notes: [
          "documentType: INVOICE, DELIVERY_NOTE, REMISSION_GUIDE, GRE_REMITENTE, GRE_TRANSPORTISTA u OTHER.",
          "Un pedido aún desconocido vuelve en unknownOrders y se enlaza al reenviar el documento.",
          "Campos fiscales opcionales: fiscalCountry, fiscalDocumentCode, authorizationNumber, transportStartDate, carrierTaxId, vehiclePlate, recipients[]…",
        ],
      },
    ],
  },
  {
    code: "integration.material:write",
    summary: "El almacén escribe el perfil físico de sus materiales (medidas, apilado). TMS lo copia a las líneas de los pedidos nuevos.",
    exchanges: [
      {
        key: "material-profile",
        title: "Crear o actualizar un perfil",
        method: "PUT",
        path: "/material-profiles/PLP-571",
        headers: { "Idempotency-Key": "ewm-matp-PLP-571-20261124T140512" },
        body: {
          sourceSystem: "EWM_EBIM",
          sourceUpdatedAt: "2026-11-24T14:05:12-05:00",
          description: "VASO PLP-571 7 OZ",
          lengthM: 0.105,
          widthM: 0.105,
          heightM: 0.175,
          unitWeightKg: 0.012,
          unitVolumeM3: 0.0019,
          stackMode: "NESTABLE",
          stackMaxQuantity: 100,
          nestIncrementM: 0.012,
          maxTopLoadKg: null,
          orientationLocked: false,
        },
        responses: [
          {
            status: 201,
            label: "Creado (200 si ya existía)",
            body: { materialCode: "PLP-571", outcome: "CREATED", version: 0, sourceUpdatedAt: "2026-11-24T19:05:12Z" },
          },
        ],
        notes: ["Un campo ausente conserva el valor guardado; un null explícito lo borra. outcome: CREATED, UPDATED, UNCHANGED o STALE."],
      },
      {
        key: "material-batch",
        title: "Lote de perfiles",
        method: "POST",
        path: "/material-profiles/batch",
        body: {
          sourceSystem: "EWM_EBIM",
          profiles: [
            {
              materialCode: "PLP-571", sourceUpdatedAt: "2026-11-24T14:05:12-05:00",
              lengthM: 0.105, widthM: 0.105, heightM: 0.175, stackMode: "NESTABLE",
              stackMaxQuantity: 100, nestIncrementM: 0.012,
            },
          ],
        },
        responses: [
          {
            status: 200,
            label: "Todos válidos (207 si alguno es INVALID)",
            body: {
              received: 1,
              applied: 1,
              refused: 0,
              results: [{ index: 0, materialCode: "PLP-571", outcome: "UPDATED", message: null }],
            },
          },
        ],
        notes: ["Hasta 500 perfiles por lote."],
      },
    ],
  },
  {
    code: "integration.order:read",
    summary: "Leer el estado de los pedidos de transporte, uno por número o el listado por estado. Lo que consulta el almacén tras un webhook ORDER_CANCELLED.",
    exchanges: [
      {
        key: "order-status",
        title: "Un pedido por número",
        method: "GET",
        path: `/orders/${ORDER_NUMBER}`,
        responses: [{ status: 200, label: "Estado del pedido", body: ORDER_STATUS }],
      },
      {
        key: "order-search",
        title: "Pedidos por estado",
        method: "GET",
        path: "/orders?status=CANCELLED&updatedSince=2026-11-23T00:00:00Z&page=0&size=200",
        responses: [{ status: 200, label: "Página de pedidos", body: { content: [ORDER_STATUS], page: 0, size: 200, totalElements: 1 } }],
        notes: ["Sin status devuelve los CANCELLED. Ordenados por updatedAt y número de pedido."],
      },
    ],
  },
  {
    code: "integration.warehouse-order-event:write",
    summary: "El almacén informa que anuló su pedido o lo cerró con faltante. TMS nunca anula por su cuenta: pone una retención y decide una persona.",
    exchanges: [
      {
        key: "warehouse-order-events",
        title: "Enviar eventos de pedido",
        method: "POST",
        path: "/warehouse-order-events",
        headers: { "Idempotency-Key": "ewm-ordcx:2b9e7c1d-0a4f-4e3b-9c8d-7f6e5d4c3b2a" },
        body: {
          sourceSystem: "EWM_EBIM",
          events: [
            {
              eventId: "2b9e7c1d-0a4f-4e3b-9c8d-7f6e5d4c3b2a", type: "ORDER_SHORT_CLOSED",
              externalSource: ERP_SOURCE, externalReference: ERP_REFERENCE,
              warehouseOrderNumber: "ORR-001-00000007", warehouseCode: "CD01",
              occurredAt: "2026-11-24T09:30:00-05:00", reason: "Saldo anulado tras despacho parcial",
              lines: [{ lineNumber: 1, materialCode: "SKU-100", uom: "UN", requested: 100, shipped: 97, cancelled: 3 }],
            },
          ],
        },
        responses: [
          {
            status: 200,
            label: "Todos válidos (207 si alguno es INVALID)",
            body: {
              received: 1,
              recorded: 1,
              refused: 0,
              results: [
                { index: 0, eventId: "2b9e7c1d-0a4f-4e3b-9c8d-7f6e5d4c3b2a", result: "NOTED", orderNumber: ORDER_NUMBER, message: null },
              ],
            },
          },
        ],
        notes: [
          "type: ORDER_CANCELLED u ORDER_SHORT_CLOSED.",
          "result: HOLD_PLACED, ALREADY_HELD, NOTED, ORDER_CLOSED, UNKNOWN_ORDER, DUPLICATE o INVALID.",
        ],
      },
    ],
  },
  {
    code: "integration.payable-payment:write",
    summary: "El ERP informa lo que pagó contra una factura de transportista ya exportada. TMS lo registra y no mueve dinero.",
    exchanges: [
      {
        key: "payable-payment",
        title: "Registrar un pago",
        method: "POST",
        path: "/payable-payments",
        headers: { "Idempotency-Key": "4f7c1c9e-2a1d-4b55-9a0e-6d2f3c7b8a10" },
        body: {
          invoice: { carrierTaxId: "20600000011", documentType: "01", documentNumber: "F001-000123" },
          paymentReference: "SAP-PAY-2026-11-0042",
          paidOn: "2026-11-24",
          amount: 560.0,
          currency: "PEN",
          withholdings: [
            { kind: "PE_DETRACTION", amount: 40.0, certificateNumber: "DET-889120", certificateDate: "2026-11-23" },
          ],
          sourceSystem: "SAP",
        },
        responses: [
          {
            status: 201,
            label: "Pago registrado (200 DUPLICATE si se repite)",
            body: {
              invoiceId: INVOICE_ID,
              invoiceNumber: "F001-123",
              paymentId: "6e1d0c9b-7a2f-4e3d-8c1b-5a4f3e2d1c0b",
              outcome: "RECORDED",
              status: "PAID",
              paidAmount: 560.0,
              openBalance: 0.0,
              dueDate: "2026-12-09",
            },
          },
        ],
        notes: [
          "La factura se identifica con uno solo de: invoiceId; carrierTaxId + documentType + documentNumber; o carrierCode + invoiceNumber.",
          "422 payment-exceeds-balance si el monto supera el saldo; 409 si la factura aún no se exportó.",
        ],
      },
    ],
  },
];

// ---------------------------------------------------------------------------
// Salida: un bloque por evento, en el orden del enum WebhookEventType.
// ---------------------------------------------------------------------------

/** Las cabeceras de cada entrega (WEBHOOKS_V1 §4). */
export function webhookHeaders(eventType: string, eventId: string): Record<string, string> {
  return {
    "Content-Type": "application/json; charset=utf-8",
    "X-TMS-Event-Id": eventId,
    "X-TMS-Event-Type": eventType,
    "X-TMS-Delivery-Id": "e4d3c2b1-0a9f-4e8d-b7c6-5a4b3c2d1e0f",
    "X-TMS-Delivery-Attempt": "1",
    "X-TMS-Signature": "t=1764000930,v1=5f1c0b8e2a7d4c3f9e6b1a0d8c7f2e5b4a3d9c8b7e6f1a2d3c4b5a6e7f8d9c0b",
    "X-Correlation-Id": "1c9e7b52-4f0a-4d3e-8b6a-2e5f7c9d0a13",
    "User-Agent": "TMS-by-EBIM-Webhooks/1.0",
  };
}

/** El sobre que viaja en cada entrega (WebhookEventV1). Sin detalle de negocio, a propósito. */
export function webhookEnvelope(event: EventDoc, eventId: string) {
  return {
    apiVersion: "v1",
    id: eventId,
    type: event.type,
    occurredAt: "2026-11-24T13:15:30Z",
    companyId: COMPANY_ID,
    resource: event.resource,
  };
}

/** Qué responde el receptor, y qué hace TMS con cada respuesta (WEBHOOKS_V1 §6). */
export const WEBHOOK_RESPONSE_RULES: { response: string; outcome: string }[] = [
  { response: "2xx", outcome: "Entregado. El cuerpo de la respuesta se descarta." },
  { response: "5xx, 408, 425, 429, timeout o sin conexión", outcome: "Se reintenta: ~1, 3, 7, 15 y 45 minutos; luego queda FAILED." },
  { response: "Otro 4xx (incl. 410) o 3xx", outcome: "Fallo definitivo, no se reintenta. Las redirecciones nunca se siguen." },
];

const SHIPMENT_RESOURCE = { type: "shipment", id: SHIPMENT_ID, reference: SHIPMENT_NUMBER };

const SHIPMENT_FOLLOW_UP = {
  scope: "integration.shipment:read",
  exchange: {
    key: "follow-shipment",
    title: "Leer el estado actual del embarque",
    method: "GET" as const,
    path: `/shipments/${SHIPMENT_NUMBER}`,
    responses: [{ status: 200, label: "El plan completo", body: SHIPMENT_PLAN }],
  },
};

const TENDER_FOLLOW_UP = {
  scope: "integration.tender:respond",
  exchange: {
    key: "follow-tender",
    title: "Leer la oferta",
    method: "GET" as const,
    path: `/tenders?shipmentNumber=${SHIPMENT_NUMBER}`,
    responses: [{ status: 200, label: "La oferta, si aún se puede responder", body: [TENDER_OFFER] }],
  },
};

const ORDER_FOLLOW_UP = {
  scope: "integration.order:read",
  exchange: {
    key: "follow-order",
    title: "Leer el pedido anulado",
    method: "GET" as const,
    path: `/orders/${ORDER_NUMBER}`,
    responses: [{ status: 200, label: "Estado del pedido", body: ORDER_STATUS }],
  },
};

const INVOICE_RESOURCE = { type: "carrier-invoice", id: INVOICE_ID, reference: "F001-123" };
const INVOICE_NOTE = "No hay endpoint de lectura de facturas: resource.id es el mismo invoiceId de la exportación y el que acepta POST /payable-payments.";

export const EVENT_DOCS: EventDoc[] = [
  { type: "SHIPMENT_CONFIRMED", summary: "El plan se confirmó: el viaje pasa a ser un embarque comprometido.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "SHIPMENT_READY", summary: "El viaje quedó cargado y listo para salir.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "SHIPMENT_DISPATCHED", summary: "El viaje salió.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "SHIPMENT_COMPLETED", summary: "El viaje terminó.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "SHIPMENT_CANCELLED", summary: "Se canceló un viaje que ya estaba confirmado.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  {
    type: "SHIPMENT_CHANGED",
    summary: "Cambió el conductor de un embarque confirmado o listo para despacho. Lo que lleva el embarque no cambia.",
    resource: SHIPMENT_RESOURCE,
    followUp: SHIPMENT_FOLLOW_UP,
  },
  {
    type: "DELIVERY_RESULT_RECORDED",
    summary: "Se registró o corrigió el resultado de entrega de un pedido del viaje.",
    resource: SHIPMENT_RESOURCE,
    followUp: SHIPMENT_FOLLOW_UP,
    notes: ["El resultado está en orders[].delivery del embarque. Nunca mueve inventario en el almacén."],
  },
  { type: "TENDER_SENT", summary: "Se ofreció el viaje a un transportista.", resource: SHIPMENT_RESOURCE, followUp: TENDER_FOLLOW_UP },
  { type: "TENDER_ACCEPTED", summary: "El transportista aceptó la oferta.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "TENDER_REJECTED", summary: "El transportista rechazó la oferta.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "TENDER_EXPIRED", summary: "La oferta venció sin respuesta.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  { type: "TENDER_CANCELLED", summary: "La oferta se retiró antes de que el transportista respondiera.", resource: SHIPMENT_RESOURCE, followUp: SHIPMENT_FOLLOW_UP },
  {
    type: "ORDER_CANCELLED",
    summary: "Se anuló un pedido de transporte en TMS. Es una señal, no una orden: el almacén alerta y decide.",
    resource: { type: "order", id: ORDER_ID, reference: ORDER_NUMBER },
    followUp: ORDER_FOLLOW_UP,
  },
  { type: "INVOICE_APPROVED", summary: "Una persona aprobó una factura, nota de crédito o de débito de un transportista.", resource: INVOICE_RESOURCE, notes: [INVOICE_NOTE] },
  { type: "PAYABLE_EXPORTED", summary: "La factura aprobada se entregó a quien paga.", resource: INVOICE_RESOURCE, notes: [INVOICE_NOTE] },
  { type: "PAYABLE_PAID", summary: "Los pagos registrados cubren el saldo de la factura. TMS no mueve dinero.", resource: INVOICE_RESOURCE, notes: [INVOICE_NOTE] },
];

const SCOPES_BY_CODE = new Map(SCOPE_DOCS.map((doc) => [doc.code, doc]));
const EVENTS_BY_TYPE = new Map(EVENT_DOCS.map((doc) => [doc.type, doc]));

export function scopeDoc(code: string): ScopeDoc | undefined {
  return SCOPES_BY_CODE.get(code);
}

export function eventDoc(type: string): EventDoc | undefined {
  return EVENTS_BY_TYPE.get(type);
}

/**
 * Los alcances de una credencial en el orden del catálogo (el del enum del backend), con los que
 * el catálogo no conoce al final, para que nada de lo que tiene la credencial quede sin mostrar.
 */
export function orderedByCatalogue<T extends string>(values: T[], catalogue: string[]): T[] {
  const position = (value: string) => {
    const index = catalogue.indexOf(value);
    return index === -1 ? Number.MAX_SAFE_INTEGER : index;
  };
  return [...values].sort((a, b) => position(a) - position(b));
}

export const SCOPE_ORDER = SCOPE_DOCS.map((doc) => doc.code);
export const EVENT_ORDER = EVENT_DOCS.map((doc) => doc.type);

const STATUS_TEXT: Record<number, string> = {
  200: "OK",
  201: "Created",
  202: "Accepted",
  204: "No Content",
  207: "Multi-Status",
  400: "Bad Request",
  401: "Unauthorized",
  403: "Forbidden",
  404: "Not Found",
  409: "Conflict",
  422: "Unprocessable Content",
  423: "Locked",
};

export function statusText(status: number): string {
  return STATUS_TEXT[status] ?? "";
}

/** Una petición como se ve en el cable: línea de petición, cabeceras y cuerpo. */
export function renderRequest(method: HttpMethod, url: string, headers: Record<string, string>, body?: unknown): string {
  const head = [`${method} ${url}`, ...Object.entries(headers).map(([name, value]) => `${name}: ${value}`)];
  return body === undefined ? head.join("\n") : `${head.join("\n")}\n\n${JSON.stringify(body, null, 2)}`;
}

export function renderJson(body: unknown): string {
  return JSON.stringify(body, null, 2);
}
