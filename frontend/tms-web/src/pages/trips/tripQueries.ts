import { useQuery } from "@tanstack/react-query";
import { fetchTrip } from "../../shared/api/planningApi";
import { fetchTripTracking } from "../../shared/api/trackingApi";
import { fetchTripWarehouse } from "../../shared/api/warehouseApi";

/**
 * Las lecturas de un viaje que comparten el espacio de trabajo y el seguimiento de reparto.
 *
 * Mismas claves en las dos pantallas a propósito: abrir un viaje desde el seguimiento y pasar
 * después a su espacio de trabajo no vuelve a pedir lo que ya está en caché, y una escritura en el
 * espacio de trabajo (que invalida estas claves) refresca también el panel del seguimiento.
 */
export const tripQueryKeys = {
  detail: (companyId: string, tripId: string | undefined) => ["trip", companyId, tripId] as const,
  tracking: (companyId: string, tripId: string | undefined) => ["trip-tracking", companyId, tripId] as const,
  warehouse: (companyId: string, tripId: string | undefined) => ["trip-warehouse", companyId, tripId] as const,
};

export function useTripDetail(companyId: string, tripId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: tripQueryKeys.detail(companyId, tripId),
    queryFn: ({ signal }) => fetchTrip(companyId, tripId as string, signal),
    enabled: enabled && companyId !== "" && tripId !== undefined,
  });
}

/** El rastreo puede fallar legítimamente (un despliegue sin feed): no se reintenta. */
export function useTripTracking(companyId: string, tripId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: tripQueryKeys.tracking(companyId, tripId),
    queryFn: ({ signal }) => fetchTripTracking(companyId, tripId as string, signal),
    enabled: enabled && companyId !== "" && tripId !== undefined,
    retry: false,
  });
}

/** Cómo salió y qué dijo el almacén (ADR-013); trae también el modo de despacho de la empresa. */
export function useTripWarehouse(companyId: string, tripId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: tripQueryKeys.warehouse(companyId, tripId),
    queryFn: ({ signal }) => fetchTripWarehouse(companyId, tripId as string, signal),
    enabled: enabled && companyId !== "" && tripId !== undefined,
    retry: false,
  });
}
