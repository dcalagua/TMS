import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ApiError } from "../../../shared/api/httpClient";
import { usePortalOffline } from "./offlineContext";
import { OfflineNoDataError, isNetworkError, isOnline } from "./network";
import type { OfflineStore } from "./types";

/**
 * Lo que devuelve una lectura del portal: los datos y, si vienen de la copia del teléfono
 * porque no hubo red, cuándo se leyeron (`savedAt`). `savedAt === null` = recién leídos.
 */
export interface Served<T> {
  data: T;
  savedAt: string | null;
}

interface PortalQueryOptions<T> {
  queryKey: readonly unknown[];
  companyId: string;
  /** La clave de la copia dentro del usuario y la empresa: `trips:14:false`, `trip:<id>`. */
  snapshotKey: string;
  fetcher: (signal: AbortSignal) => Promise<T>;
  enabled: boolean;
  keepPrevious?: boolean;
}

/**
 * Red primero; si la lectura sale bien se guarda la copia; si no hay red (o `fetch` lanza) se
 * enseña la copia con su hora. Sin copia, un error que lo dice. Un error del servidor (`ApiError`)
 * nunca se tapa con la copia: un 403 o un 404 tienen que verse.
 *
 * Exportada sin React para probarla con un almacén en memoria.
 */
export async function readNetworkFirst<T>(args: {
  store: OfflineStore;
  userId: string | null;
  companyId: string;
  snapshotKey: string;
  fetcher: (signal: AbortSignal) => Promise<T>;
  signal: AbortSignal;
  online?: () => boolean;
}): Promise<Served<T>> {
  const { store, userId, companyId, snapshotKey, fetcher, signal } = args;
  const online = args.online ?? isOnline;
  const fromSnapshot = async (): Promise<Served<T> | null> => {
    if (!userId) return null;
    const snapshot = await store.getSnapshot<T>(userId, companyId, snapshotKey).catch(() => null);
    return snapshot ? { data: snapshot.data, savedAt: snapshot.savedAt } : null;
  };

  if (!online()) {
    const served = await fromSnapshot();
    if (served) return served;
    throw new OfflineNoDataError();
  }

  try {
    const data = await fetcher(signal);
    if (userId) void store.putSnapshot(userId, companyId, snapshotKey, data).catch(() => undefined);
    return { data, savedAt: null };
  } catch (error) {
    if (isNetworkError(error) && !signal.aborted) {
      const served = await fromSnapshot();
      if (served) return served;
      throw new OfflineNoDataError();
    }
    throw error;
  }
}

export function usePortalQuery<T>({ queryKey, companyId, snapshotKey, fetcher, enabled, keepPrevious }: PortalQueryOptions<T>) {
  const { store, userId } = usePortalOffline();
  return useQuery<Served<T>>({
    queryKey,
    queryFn: ({ signal }) => readNetworkFirst({ store, userId, companyId, snapshotKey, fetcher, signal }),
    enabled,
    // `always`: sin red la consulta tiene que correr igualmente para leer la copia. Con el modo
    // por defecto (`online`) TanStack la deja en pausa y la pantalla se queda cargando.
    networkMode: "always",
    placeholderData: keepPrevious ? keepPreviousData : undefined,
    retry: (failureCount, error) => {
      if (error instanceof OfflineNoDataError) return false;
      if (error instanceof ApiError && error.status < 500) return false;
      return failureCount < 2;
    },
  });
}
