import { createContext, useContext } from "react";
import type { OutboxDraft } from "./outboxEngine";
import type { OfflineStore, OutboxEntry } from "./types";

/** El contexto del modo sin señal del portal. Lo publica `PortalOfflineProvider`. */
export interface PortalOfflineValue {
  userId: string | null;
  store: OfflineStore;
  online: boolean;
  entries: OutboxEntry[];
  /** PENDING + SENDING: lo que todavía va a salir. */
  pendingCount: number;
  failedEntries: OutboxEntry[];
  syncing: boolean;
  enqueue(draft: OutboxDraft): Promise<OutboxEntry>;
  sync(options?: { force?: boolean }): Promise<void>;
  retry(id: string): Promise<void>;
  discard(id: string): Promise<void>;
  /** Borra copias y cola de este usuario. Lo llama el cierre de sesión. */
  wipe(): Promise<void>;
  /** Hay un reporte de ese documento esperando: uno nuevo tiene que ir detrás, a la cola. */
  hasQueued(tripId: string, documentId: string): boolean;
  /**
   * Hay una acción de parada de ese viaje esperando (V70): la siguiente tiene que ir detrás, a la
   * cola, aunque haya red — si no, la entrega llegaría antes que la llegada a la parada.
   */
  hasQueuedExecution(tripId: string): boolean;
}

export const PortalOfflineContext = createContext<PortalOfflineValue | null>(null);

export function usePortalOffline(): PortalOfflineValue {
  const value = useContext(PortalOfflineContext);
  if (!value) throw new Error("usePortalOffline debe usarse dentro de un PortalOfflineProvider");
  return value;
}

/** La cola, tal y como la pinta la app: cuántos esperan, cuáles fallaron, y las acciones. */
export function useOutbox() {
  const { pendingCount, failedEntries, entries, syncing, online, sync, discard, retry } = usePortalOffline();
  return { pendingCount, failedEntries, entries, syncing, online, sync, discard, retry };
}
