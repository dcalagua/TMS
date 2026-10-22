import type { TripAssignmentView, TripDetailView, TripStopView } from "../../shared/api/planningApi";

/** Las paradas en orden de visita, cada una con los pedidos que se le entregan. */
export function stopsWithOrders(detail: TripDetailView): { stop: TripStopView | null; orders: TripAssignmentView[] }[] {
  const stops = [...detail.stops].sort((a, b) => a.sequence - b.sequence);
  const groups = stops.map((stop) => ({
    stop: stop as TripStopView | null,
    orders: detail.assignments.filter((a) => a.destinationId === stop.destinationId),
  }));
  // Un pedido sin parada no debería existir; si existe, se imprime igual y se dice, en vez de
  // dejarlo fuera de un papel que alguien va a firmar.
  const placed = new Set(groups.flatMap((g) => g.orders.map((o) => o.assignmentId)));
  const orphans = detail.assignments.filter((a) => !placed.has(a.assignmentId));
  if (orphans.length > 0) groups.push({ stop: null, orders: orphans });
  return groups;
}
