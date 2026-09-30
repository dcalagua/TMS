import { describe, expect, it, vi } from "vitest";
import type { ControlTowerTripView } from "../../shared/api/controlTowerApi";
import type { PageResponse } from "../../shared/api/pageResponse";
import type { OrderDeliveryView, TripAssignmentView, TripView } from "../../shared/api/planningApi";
import {
  BOARD_MAX_PAGES, BOARD_PAGE_SIZE, aggregateKpis, deliverySummary, fetchBoardSnapshot, matchesSearch, stopProgress,
} from "./deliveryTracking";

/**
 * Seguimiento de reparto (fase 9): KPI del día, progreso por fila y el resumen de entregas.
 *
 * Las reglas: un viaje sin paradas no es un 0% de progreso, un pedido sin entrega registrada es
 * "sin registrar" y no un estado inventado, y los KPI salen del día entero y no de la página.
 */

function row(overrides: Partial<ControlTowerTripView> & { status?: TripView["status"]; shipmentNumber?: string } = {}): ControlTowerTripView {
  const { status = "IN_TRANSIT", shipmentNumber = "SH-1", ...rest } = overrides;
  return {
    trip: {
      id: shipmentNumber, shipmentNumber, status, vehicleLicensePlate: "ABC-123", driverName: "Pérez, Ana",
      carrierName: "Transportes Andinos", routeName: "Lima Norte",
    } as TripView,
    departureTimeliness: "ON_TIME",
    departureDelayMinutes: null,
    stopsTotal: 4,
    stopsResolved: 1,
    stopsPastWindow: 0,
    nextStopSequence: 2,
    nextStopDueAt: null,
    openExceptions: 0,
    ...rest,
  };
}

describe("aggregateKpis", () => {
  it("suma paradas, pendientes, ventanas e incidencias y calcula el progreso", () => {
    const kpis = aggregateKpis([
      row({ stopsTotal: 4, stopsResolved: 1, openExceptions: 1 }),
      row({ stopsTotal: 6, stopsResolved: 6, stopsPastWindow: 2, status: "COMPLETED" }),
    ]);

    expect(kpis).toEqual({
      tripsInTransit: 1, stopsTotal: 10, stopsResolved: 7, stopsPending: 3,
      stopsPastWindow: 2, openExceptions: 1, progressPercent: 70,
    });
  });

  it("sin paradas no hay progreso que medir: null y no cero", () => {
    expect(aggregateKpis([]).progressPercent).toBeNull();
    expect(aggregateKpis([row({ stopsTotal: 0, stopsResolved: 0 })]).progressPercent).toBeNull();
  });

  it("nunca cuenta más resueltas que paradas", () => {
    expect(aggregateKpis([row({ stopsTotal: 2, stopsResolved: 3 })]).stopsPending).toBe(0);
  });
});

describe("stopProgress", () => {
  it("redondea el porcentaje y devuelve null sin paradas", () => {
    expect(stopProgress({ stopsTotal: 3, stopsResolved: 1 })).toBe(33);
    expect(stopProgress({ stopsTotal: 0, stopsResolved: 0 })).toBeNull();
  });
});

describe("matchesSearch", () => {
  it("busca por envío, placa, conductor, transportista o ruta sin distinguir mayúsculas", () => {
    const candidate = row({ shipmentNumber: "SH-00000845" });
    expect(matchesSearch(candidate, "")).toBe(true);
    expect(matchesSearch(candidate, "0845")).toBe(true);
    expect(matchesSearch(candidate, "abc")).toBe(true);
    expect(matchesSearch(candidate, "ana")).toBe(true);
    expect(matchesSearch(candidate, "andinos")).toBe(true);
    expect(matchesSearch(candidate, "norte")).toBe(true);
    expect(matchesSearch(candidate, "callao")).toBe(false);
  });
});

describe("fetchBoardSnapshot", () => {
  function page(content: ControlTowerTripView[], pageNumber: number, totalElements: number): PageResponse<ControlTowerTripView> {
    return { content, page: pageNumber, size: BOARD_PAGE_SIZE, totalElements };
  }

  it("trae páginas hasta completar el día", async () => {
    const fetchPage = vi.fn()
      .mockResolvedValueOnce(page(Array.from({ length: BOARD_PAGE_SIZE }, (_, i) => row({ shipmentNumber: `A${i}` })), 0, 250))
      .mockResolvedValueOnce(page(Array.from({ length: 50 }, (_, i) => row({ shipmentNumber: `B${i}` })), 1, 250));

    const snapshot = await fetchBoardSnapshot({ companyId: "c-1", date: "2026-09-28" }, fetchPage);

    expect(fetchPage).toHaveBeenCalledTimes(2);
    expect(fetchPage.mock.calls[1][0]).toMatchObject({ companyId: "c-1", date: "2026-09-28", page: 1, size: BOARD_PAGE_SIZE });
    expect(snapshot.rows).toHaveLength(250);
    expect(snapshot.truncated).toBe(false);
  });

  it("se detiene en el tope y dice que no lo trajo todo", async () => {
    const full = Array.from({ length: BOARD_PAGE_SIZE }, () => row());
    const fetchPage = vi.fn().mockImplementation(({ page: n }: { page: number }) => Promise.resolve(page(full, n, 5000)));

    const snapshot = await fetchBoardSnapshot({ companyId: "c-1" }, fetchPage);

    expect(fetchPage).toHaveBeenCalledTimes(BOARD_MAX_PAGES);
    expect(snapshot.truncated).toBe(true);
    expect(snapshot.totalElements).toBe(5000);
  });

  it("un día vacío es una sola petición", async () => {
    const fetchPage = vi.fn().mockResolvedValue(page([], 0, 0));
    const snapshot = await fetchBoardSnapshot({ companyId: "c-1" }, fetchPage);
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(snapshot).toEqual({ rows: [], totalElements: 0, truncated: false });
  });
});

describe("deliverySummary", () => {
  const assignment = (orderId: string) => ({ orderId } as TripAssignmentView);
  const delivery = (orderId: string, result: OrderDeliveryView["result"]) => ({ orderId, result } as OrderDeliveryView);

  it("cuenta por pedido asignado y deja sin registrar los que no tienen entrega", () => {
    const summary = deliverySummary({
      assignments: ["o1", "o2", "o3", "o4", "o5", "o6"].map(assignment),
      deliveries: [
        delivery("o1", "DELIVERED"), delivery("o2", "PARTIAL"), delivery("o3", "REJECTED"),
        delivery("o4", "FAILED"), delivery("o5", "NOT_ATTEMPTED"),
      ],
    });

    expect(summary).toEqual({ orders: 6, delivered: 1, partial: 1, notDelivered: 2, notAttempted: 1, unrecorded: 1 });
  });

  it("un pedido asignado dos veces al viaje cuenta una vez", () => {
    const summary = deliverySummary({ assignments: [assignment("o1"), assignment("o1")], deliveries: [] });
    expect(summary.orders).toBe(1);
    expect(summary.unrecorded).toBe(1);
  });
});
