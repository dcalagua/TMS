import type { DocumentDeliveryListParams, DocumentDeliveryStatus } from "../../shared/api/documentDeliveriesApi";
import { toIsoDate, today } from "../../lib/locale";

/** Prefijo de todas las lecturas del monitor de una empresa: lista, resumen y detalle. */
export const documentDeliveriesKey = (companyId: string) => ["document-deliveries", companyId] as const;
export const documentDeliveryDetailKey = (companyId: string, id: string) =>
  [...documentDeliveriesKey(companyId), "detail", id] as const;

/** Los filtros de la pantalla, todos texto: `""` es «sin filtro». */
export interface MonitorFilters {
  q: string;
  dateFrom: string;
  dateTo: string;
  carrierId: string;
  status: DocumentDeliveryStatus | "";
  returned: "" | "true" | "false";
}

/** La última semana, hoy incluido: es lo que la oficina persigue, cargos que aún no volvieron. */
export function defaultMonitorFilters(now = new Date()): MonitorFilters {
  const from = new Date(now);
  from.setDate(from.getDate() - 6);
  return { q: "", dateFrom: toIsoDate(from), dateTo: today(), carrierId: "", status: "", returned: "" };
}

/** Los filtros como parámetros de la API, sin los vacíos. */
export function toListParams(filters: MonitorFilters): Omit<DocumentDeliveryListParams, "companyId" | "signal"> {
  return {
    dateFrom: filters.dateFrom || undefined,
    dateTo: filters.dateTo || undefined,
    carrierId: filters.carrierId || undefined,
    status: filters.status || undefined,
    returned: filters.returned === "" ? undefined : filters.returned === "true",
    q: filters.q.trim() || undefined,
  };
}
