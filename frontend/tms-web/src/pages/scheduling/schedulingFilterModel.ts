import type { OrderPriority, OrderStatus } from "../../shared/api/ordersApi";
import type { Eligibility, SchedulingFilterParams } from "../../shared/api/schedulingApi";

/** Filtros tal como los edita la pantalla: cadenas vacías en lugar de `undefined`. */
export interface Filters {
  originId: string;
  routeCode: string;
  serviceDateFrom: string;
  serviceDateTo: string;
  customer: string;
  priority: OrderPriority | "";
  eligibility: Eligibility | "";
  hold: "" | "with" | "without";
  frequency: string;
  status: "" | Extract<OrderStatus, "NOT_READY" | "READY_FOR_PLANNING">;
}

function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function plusDays(iso: string, days: number): string {
  const date = new Date(`${iso}T00:00:00`);
  date.setDate(date.getDate() + days);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

export function defaultFilters(): Filters {
  const today = todayIso();
  return {
    originId: "", routeCode: "", serviceDateFrom: today, serviceDateTo: plusDays(today, 7), customer: "",
    priority: "", eligibility: "", hold: "", frequency: "", status: "",
  };
}

/** Los filtros de pantalla en el vocabulario de la API. */
export function toParams(filters: Filters): SchedulingFilterParams {
  return {
    originId: filters.originId || undefined,
    routeCode: filters.routeCode || undefined,
    serviceDateFrom: filters.serviceDateFrom || undefined,
    serviceDateTo: filters.serviceDateTo || undefined,
    customer: filters.customer.trim() || undefined,
    priority: filters.priority || undefined,
    eligibility: filters.eligibility || undefined,
    hasHold: filters.hold === "" ? undefined : filters.hold === "with",
    frequency: filters.frequency.trim() || undefined,
    status: filters.status || undefined,
  };
}
