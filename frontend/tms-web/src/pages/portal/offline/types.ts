import type { DocumentDeliveryReason } from "../../../shared/api/documentDeliveriesApi";
import type { PortalExecutionAction } from "../../../shared/api/portalApi";
import type { ReportStatus } from "../portalReport";

/**
 * Lo que el portal guarda en el teléfono para trabajar sin señal (ADR-019 §1). Todo va por
 * usuario y empresa: un teléfono compartido no enseña a nadie lo que leyó o reportó otro.
 */

/** Una copia de una lectura correcta, para enseñarla sin red con su antigüedad. */
export interface Snapshot<T = unknown> {
  /** `${userId}|${companyId}|${key}`. */
  id: string;
  userId: string;
  companyId: string;
  /** `trips:<días>:<pendientes>` o `trip:<tripId>`. */
  key: string;
  data: T;
  /** ISO-8601: cuándo se leyó del servidor. */
  savedAt: string;
}

/**
 * - `PENDING`: espera a que haya red (o a que venza su espera tras un fallo).
 * - `SENDING`: en vuelo ahora mismo.
 * - `FAILED`: el servidor lo rechazó (400/403/404/409). No se reintenta solo: la persona lo
 *   descarta o lo rehace, y ve el motivo.
 */
export type OutboxState = "PENDING" | "SENDING" | "FAILED";

/** Lo que toda entrada de la cola lleva, sea del tipo que sea. */
interface OutboxEntryBase {
  id: string;
  userId: string;
  companyId: string;
  tripId: string;
  /** El de ADR-018 §5, creado al abrir el panel: un reenvío nunca registra dos veces. */
  idempotencyKey: string;
  createdAt: string;
  attempts: number;
  lastError: string | null;
  state: OutboxState;
  /** ISO-8601. Antes de esta hora el envío automático no lo reintenta (espera exponencial). */
  nextAttemptAt: string | null;
  /** Para enseñarlo en la cola sin tener que leer el viaje. */
  shipmentNumber: string | null;
}

/**
 * Un reporte de documento hecho sin red, guardado entero: campos, fotos y firma como blobs.
 * `kind` falta en las entradas guardadas antes de V70: todas eran reportes de documento.
 */
export interface DocumentOutboxEntry extends OutboxEntryBase {
  kind?: "DOCUMENT_REPORT";
  documentId: string;
  status: ReportStatus;
  reasonCode: DocumentDeliveryReason | null;
  remark: string | null;
  /** Cuándo tocó «Guardar» en la puerta del cliente, no cuándo se sincronizó. */
  reportedAt: string;
  photos: Blob[];
  signature: Blob | null;
  documentType: string;
  documentNumber: string;
  customerName: string | null;
}

/**
 * Una acción de la parada hecha sin red (V70): llegada, inicio de descarga, fin o no atención;
 * el resultado de entrega de un pedido con sus fotos y firma; o una incidencia. Guardada entera,
 * con la hora en que se tocó el botón, que es la que cuenta en el historial de la oficina.
 */
export interface ExecutionOutboxEntry extends OutboxEntryBase {
  kind: "EXECUTION";
  action: PortalExecutionAction;
  /** Cómo se nombra en la hoja de sincronización: «Llegada · Parada 2 Bodega San Martín». */
  title: string;
  /** Cuándo se tocó el botón. */
  reportedAt: string;
}

export type OutboxEntry = DocumentOutboxEntry | ExecutionOutboxEntry;

/** Un reporte de documento (incluidas las entradas sin `kind` de antes de V70). */
export function isDocumentEntry(entry: OutboxEntry): entry is DocumentOutboxEntry {
  return entry.kind !== "EXECUTION";
}

/**
 * El almacén, detrás de una interfaz: en el navegador es IndexedDB; en las pruebas (jsdom no
 * tiene IndexedDB) y donde IndexedDB no esté disponible, uno en memoria.
 */
export interface OfflineStore {
  getSnapshot<T>(userId: string, companyId: string, key: string): Promise<Snapshot<T> | null>;
  putSnapshot<T>(userId: string, companyId: string, key: string, data: T, savedAt?: string): Promise<void>;
  /** Las entradas de la cola de un usuario, de la más antigua a la más nueva. */
  listOutbox(userId: string): Promise<OutboxEntry[]>;
  getOutbox(id: string): Promise<OutboxEntry | null>;
  putOutbox(entry: OutboxEntry): Promise<void>;
  deleteOutbox(id: string): Promise<void>;
  /** Borra copias y cola de ese usuario: al cerrar sesión. */
  wipeUser(userId: string): Promise<void>;
  /** Borra lo de cualquier otro usuario: al entrar uno distinto en el mismo teléfono. */
  wipeOtherUsers(userId: string): Promise<void>;
  /** Borra copias leídas antes de esa fecha. La cola no caduca. */
  pruneSnapshots(olderThan: string): Promise<void>;
}

export const snapshotId = (userId: string, companyId: string, key: string) => `${userId}|${companyId}|${key}`;

export const byCreatedAt = (a: OutboxEntry, b: OutboxEntry) =>
  a.createdAt === b.createdAt ? a.id.localeCompare(b.id) : a.createdAt.localeCompare(b.createdAt);
