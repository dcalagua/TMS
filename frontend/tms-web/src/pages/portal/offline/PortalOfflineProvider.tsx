import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { reportPortalDocument, type PortalDocument } from "../../../shared/api/portalApi";
import { clearMeCopy } from "../../../shared/company/meOfflineCopy";
import { portalTripKey, portalTripsKey } from "../portalReport";
import { defaultOfflineStore } from "./defaultStore";
import { isOnline } from "./network";
import { createOutboxEngine, entryToReport, type OutboxEngine } from "./outboxEngine";
import { PortalOfflineContext, type PortalOfflineValue } from "./offlineContext";
import type { OfflineStore, OutboxEntry } from "./types";

/**
 * El estado sin señal del portal (ADR-019 §1) para toda la app del transportista: si hay red,
 * qué espera en la cola y el envío de esa cola.
 *
 * La cola se envía sola: al montar (arranque de la app), al volver la red (`online`), al volver
 * a la app (`visibilitychange`), cada minuto mientras quede algo pendiente, y cuando la persona
 * pulsa «Sincronizar ahora».
 *
 * Al montar con un usuario se borra lo de cualquier otro usuario de este teléfono; al cerrar
 * sesión (`wipe`), lo de este. Una sesión que caduca NO borra nada: al volver a entrar la misma
 * persona, su cola sigue ahí y se envía.
 */

const SYNC_INTERVAL_MS = 60_000;
/** Las copias de lectura más viejas que esto se borran al arrancar. La cola no caduca. */
const SNAPSHOT_TTL_MS = 30 * 24 * 60 * 60_000;

interface PortalOfflineProviderProps {
  userId: string | null;
  children: ReactNode;
  /** Pruebas: un almacén en memoria. */
  store?: OfflineStore;
  /** Pruebas: el envío. Por defecto, el POST multipart del portal. */
  send?: (entry: OutboxEntry) => Promise<PortalDocument>;
  /** Pruebas: la hora. */
  now?: () => number;
}

function defaultSend(entry: OutboxEntry): Promise<PortalDocument> {
  return reportPortalDocument(entry.companyId, entry.tripId, entry.documentId, entryToReport(entry), entry.idempotencyKey);
}

export function PortalOfflineProvider({ userId, children, store: injectedStore, send, now }: PortalOfflineProviderProps) {
  const queryClient = useQueryClient();
  const store = useMemo(() => injectedStore ?? defaultOfflineStore(), [injectedStore]);
  const [entries, setEntries] = useState<OutboxEntry[]>([]);
  const [online, setOnline] = useState(isOnline);
  const [syncing, setSyncing] = useState(false);
  const entriesRef = useRef<OutboxEntry[]>([]);
  // El envío y el reloj, fijados al montar: una función nueva en cada render (las que inyectan
  // las pruebas) no debe rehacer la cola.
  const [hooks] = useState(() => ({ send: send ?? defaultSend, now: now ?? Date.now }));
  // Cada cambio de la cola sube la versión, y la versión vuelve a leer la lista.
  const [version, setVersion] = useState(0);

  const engine = useMemo<OutboxEngine | null>(() => {
    if (!userId) return null;
    return createOutboxEngine({
      store,
      userId,
      now: () => hooks.now(),
      send: (entry) => hooks.send(entry),
      onChange: () => setVersion((v) => v + 1),
      onSent: (entry) => {
        void queryClient.invalidateQueries({ queryKey: portalTripsKey(entry.companyId) });
        void queryClient.invalidateQueries({ queryKey: portalTripKey(entry.companyId, entry.tripId) });
      },
    });
  }, [store, userId, queryClient, hooks]);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    if (!engine) return;
    let active = true;
    engine.list().then((list) => {
      if (!active) return;
      entriesRef.current = list;
      setEntries(list);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [engine, version]);

  const sync = useCallback(async (options: { force?: boolean } = {}) => {
    if (!engine) return;
    const anything = entriesRef.current.some((entry) => entry.state !== "FAILED");
    // El chip «Sincronizando» solo cuando hay algo que enviar: el intervalo no debe parpadear.
    if (anything || options.force) setSyncing(true);
    try {
      await engine.sync(options);
    } catch {
      /* el almacén falló: se reintenta en el próximo disparador */
    } finally {
      setSyncing(false);
      refresh();
    }
  }, [engine, refresh]);

  // Arranque: limpiar lo de otros usuarios, rescatar envíos cortados, podar copias viejas, enviar.
  useEffect(() => {
    if (!engine || !userId) return;
    let active = true;
    void (async () => {
      try {
        clearMeCopy(userId);
        await store.wipeOtherUsers(userId);
        await engine.recover();
        await store.pruneSnapshots(new Date(Date.now() - SNAPSHOT_TTL_MS).toISOString());
      } catch {
        /* sin almacén la app sigue funcionando en línea */
      }
      if (!active) return;
      refresh();
      await sync();
    })();
    return () => { active = false; };
  }, [engine, store, userId, refresh, sync]);

  useEffect(() => {
    const goOnline = () => {
      setOnline(true);
      void queryClient.invalidateQueries({ queryKey: ["portal-trips"] });
      void queryClient.invalidateQueries({ queryKey: ["portal-trip"] });
      void sync();
    };
    const goOffline = () => setOnline(false);
    const onVisible = () => { if (document.visibilityState === "visible") void sync(); };
    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [queryClient, sync]);

  const hasPending = entries.some((entry) => entry.state === "PENDING");
  useEffect(() => {
    if (!hasPending) return;
    const timer = window.setInterval(() => void sync(), SYNC_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [hasPending, sync]);

  const value = useMemo<PortalOfflineValue>(() => ({
    userId,
    store,
    online,
    entries,
    pendingCount: entries.filter((entry) => entry.state !== "FAILED").length,
    failedEntries: entries.filter((entry) => entry.state === "FAILED"),
    syncing,
    async enqueue(draft) {
      if (!engine) throw new Error("Sin usuario no hay cola");
      const entry = await engine.enqueue(draft);
      refresh();
      return entry;
    },
    sync,
    async retry(id) {
      if (!engine) return;
      await engine.retry(id);
      await sync({ force: true });
    },
    async discard(id) {
      if (!engine) return;
      await engine.discard(id);
      refresh();
    },
    async wipe() {
      if (userId) await store.wipeUser(userId).catch(() => undefined);
      clearMeCopy();
      queryClient.removeQueries({ queryKey: ["portal-trips"] });
      queryClient.removeQueries({ queryKey: ["portal-trip"] });
      entriesRef.current = [];
      setEntries([]);
    },
    hasQueued(tripId, documentId) {
      return entriesRef.current.some((entry) =>
        entry.tripId === tripId && entry.documentId === documentId && entry.state !== "FAILED");
    },
  }), [userId, store, online, entries, syncing, engine, refresh, sync, queryClient]);

  return <PortalOfflineContext.Provider value={value}>{children}</PortalOfflineContext.Provider>;
}
