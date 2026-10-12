import type { PortalDocument, PortalReportInput } from "../../../shared/api/portalApi";
import { classifySendError, isOnline as browserOnline, sendErrorMessage } from "./network";
import { byCreatedAt, type OfflineStore, type OutboxEntry } from "./types";

/**
 * La cola de reportes hechos sin red y su envío (ADR-019 §1), sin React: se prueba con un
 * almacén en memoria y un `send` falso.
 *
 * Reglas:
 * - de la más antigua a la más nueva, una a la vez;
 * - un documento nunca adelanta a otro reporte anterior suyo que siga en cola: la corrección
 *   llegaría antes que el reporte que corrige;
 * - sin respuesta o 5xx: se reintenta con espera exponencial (5 s, 10 s, 20 s… hasta 5 min);
 *   sin respuesta además corta la pasada, porque el resto tampoco saldrá;
 * - 2xx: la entrada se borra;
 * - 400/403/404/409: FAILED con la razón del servidor; no se reintenta sola;
 * - 401: la cola se pausa hasta que haya una sesión (`resume`).
 *
 * El `Idempotency-Key` y el `reportedAt` viajan guardados en la entrada: un reenvío de algo que
 * el servidor sí recibió devuelve lo ya registrado, sin un segundo evento (ADR-018 §5).
 */

export const BACKOFF_BASE_MS = 5_000;
export const BACKOFF_MAX_MS = 5 * 60_000;

export function backoffDelay(attempts: number): number {
  return Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, attempts - 1));
}

export type OutboxDraft = Omit<OutboxEntry,
  "id" | "userId" | "createdAt" | "attempts" | "lastError" | "state" | "nextAttemptAt">;

export interface OutboxEngineDeps {
  store: OfflineStore;
  userId: string;
  send: (entry: OutboxEntry) => Promise<PortalDocument>;
  now?: () => number;
  isOnline?: () => boolean;
  newId?: () => string;
  /** Tras un 2xx: para invalidar las lecturas del viaje. */
  onSent?: (entry: OutboxEntry, document: PortalDocument) => void;
  /** Cada vez que cambia algo de la cola: para repintar. */
  onChange?: () => void;
}

export interface SyncResult {
  sent: number;
  failed: number;
  deferred: number;
  /** La pasada se cortó por un 401. */
  paused: boolean;
}

export interface OutboxEngine {
  enqueue(draft: OutboxDraft): Promise<OutboxEntry>;
  list(): Promise<OutboxEntry[]>;
  /** `force` ignora la espera (el botón «Sincronizar ahora»). */
  sync(options?: { force?: boolean }): Promise<SyncResult>;
  /** Devuelve una entrada FAILED (o en espera) a la cola, sin espera. */
  retry(id: string): Promise<void>;
  discard(id: string): Promise<void>;
  /** Una entrada que quedó SENDING porque la app se cerró a mitad: vuelve a PENDING. */
  recover(): Promise<void>;
  /** Hay sesión otra vez tras un 401. */
  resume(): void;
  isPaused(): boolean;
}

/** El cuerpo del POST a partir de una entrada guardada. */
export function entryToReport(entry: OutboxEntry): PortalReportInput {
  return {
    status: entry.status,
    reasonCode: entry.reasonCode,
    remark: entry.remark,
    reportedAt: entry.reportedAt,
    photos: entry.photos,
    signature: entry.signature,
  };
}

export function createOutboxEngine(deps: OutboxEngineDeps): OutboxEngine {
  const now = deps.now ?? (() => Date.now());
  const online = deps.isOnline ?? browserOnline;
  const newId = deps.newId ?? (() => crypto.randomUUID());
  const changed = () => deps.onChange?.();
  let paused = false;
  let running: Promise<SyncResult> | null = null;

  async function update(entry: OutboxEntry): Promise<void> {
    await deps.store.putOutbox(entry);
    changed();
  }

  async function runOnce(force: boolean): Promise<SyncResult> {
    const result: SyncResult = { sent: 0, failed: 0, deferred: 0, paused: false };
    if (paused || !online()) return { ...result, paused };

    const entries = (await deps.store.listOutbox(deps.userId)).sort(byCreatedAt);
    // Los documentos con un reporte anterior aún en cola: los posteriores esperan su turno.
    const blocked = new Set<string>();
    const docKey = (entry: OutboxEntry) => `${entry.companyId}|${entry.tripId}|${entry.documentId}`;

    for (const entry of entries) {
      if (entry.state === "FAILED") continue;
      const key = docKey(entry);
      if (blocked.has(key)) { result.deferred++; continue; }
      if (!force && entry.nextAttemptAt !== null && Date.parse(entry.nextAttemptAt) > now()) {
        blocked.add(key);
        result.deferred++;
        continue;
      }

      await update({ ...entry, state: "SENDING" });
      try {
        const document = await deps.send(entry);
        await deps.store.deleteOutbox(entry.id);
        changed();
        result.sent++;
        deps.onSent?.(entry, document);
      } catch (error) {
        const failure = classifySendError(error);
        if (failure === "auth") {
          await update({ ...entry, state: "PENDING" });
          paused = true;
          result.paused = true;
          break;
        }
        if (failure === "rejected") {
          await update({ ...entry, state: "FAILED", lastError: sendErrorMessage(error), nextAttemptAt: null });
          result.failed++;
          continue;
        }
        const attempts = entry.attempts + 1;
        await update({
          ...entry,
          state: "PENDING",
          attempts,
          lastError: sendErrorMessage(error),
          nextAttemptAt: new Date(now() + backoffDelay(attempts)).toISOString(),
        });
        blocked.add(key);
        result.deferred++;
        if (failure === "network") break;
      }
    }
    return result;
  }

  return {
    async enqueue(draft) {
      const entry: OutboxEntry = {
        ...draft,
        id: newId(),
        userId: deps.userId,
        createdAt: new Date(now()).toISOString(),
        attempts: 0,
        lastError: null,
        state: "PENDING",
        nextAttemptAt: null,
      };
      await update(entry);
      return entry;
    },
    list: () => deps.store.listOutbox(deps.userId),
    sync(options = {}) {
      // Una sola pasada a la vez: los disparadores (online, visibilidad, intervalo, botón) se
      // solapan, y dos pasadas a la vez enviarían la misma entrada dos veces.
      running ??= runOnce(options.force === true).finally(() => { running = null; });
      return running;
    },
    async retry(id) {
      const entry = await deps.store.getOutbox(id);
      if (!entry || entry.state === "SENDING") return;
      await update({ ...entry, state: "PENDING", nextAttemptAt: null, lastError: entry.state === "FAILED" ? null : entry.lastError });
    },
    async discard(id) {
      const entry = await deps.store.getOutbox(id);
      if (!entry || entry.state === "SENDING") return;
      await deps.store.deleteOutbox(id);
      changed();
    },
    async recover() {
      const entries = await deps.store.listOutbox(deps.userId);
      for (const entry of entries) {
        if (entry.state === "SENDING") await deps.store.putOutbox({ ...entry, state: "PENDING" });
      }
      changed();
    },
    resume() { paused = false; },
    isPaused: () => paused,
  };
}
