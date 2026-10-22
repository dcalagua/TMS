import { describe, expect, it } from "vitest";
import type { PortalDocument, PortalTripDetail } from "../../../shared/api/portalApi";
import { applyOutboxOverlay, nextPendingDocument, queuedByTrip } from "./overlay";
import type { DocumentOutboxEntry as OutboxEntry, ExecutionOutboxEntry } from "./types";

/**
 * Lo que se enseña mientras un reporte espera en la cola (ADR-019 §1): el estado que eligió el
 * transportista, marcado. Si siguiera viéndose «Pendiente», lo reportaría otra vez.
 */

function doc(documentId: string, overrides: Partial<PortalDocument> = {}): PortalDocument {
  return {
    id: `dd-${documentId}`, documentId, documentType: "INVOICE", documentNumber: `F-${documentId}`, orderNumbers: [],
    customerName: null, status: "PENDING", reasonCode: null, remark: null, reportedAt: null, recordedAt: null,
    returnedAt: null, editable: true, evidence: [], ...overrides,
  };
}

const DETAIL: PortalTripDetail = {
  trip: {
    tripId: "t-1", shipmentNumber: "SH-1", status: "DISPATCHED", planningDate: "2026-10-12", dispatchedAt: null,
    originName: null, vehicleLicensePlate: null, driverName: null, stopCount: 2, documentCount: 4,
    pendingCount: 3, deliveredCount: 0, rejectedCount: 0, notDeliveredCount: 0, returnedCount: 1,
  },
  evidenceEnabled: true,
  stops: [
    { stopId: "s-1", sequence: 1, destinationName: "A", address: null, documents: [doc("d-1"), doc("d-2")] },
    {
      stopId: "s-2", sequence: 2, destinationName: "B", address: null,
      documents: [doc("d-3"), doc("d-4", { status: "DELIVERED", editable: false, returnedAt: "2026-10-12T10:00:00Z" })],
    },
  ],
};

function entry(documentId: string, overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    id: `e-${documentId}-${overrides.createdAt ?? "1"}`, userId: "u-1", companyId: "c-1", tripId: "t-1", documentId,
    idempotencyKey: "k", status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", remark: null,
    reportedAt: "2026-10-12T14:00:00.000Z", photos: [], signature: null, createdAt: "2026-10-12T14:00:00.000Z",
    attempts: 0, lastError: null, state: "PENDING", nextAttemptAt: null, documentType: "INVOICE",
    documentNumber: `F-${documentId}`, customerName: null, shipmentNumber: "SH-1", ...overrides,
  };
}

describe("el reflejo de la cola sobre el viaje", () => {
  it("enseña el estado en cola, marcado, y recalcula los contadores", () => {
    const shown = applyOutboxOverlay(DETAIL, [entry("d-1")]);

    const first = shown.stops[0].documents[0];
    expect(first).toMatchObject({ status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", pendingSync: true });
    expect(shown.stops[0].documents[1]).toMatchObject({ status: "PENDING", pendingSync: false });
    expect(shown.trip).toMatchObject({ pendingCount: 2, rejectedCount: 1, deliveredCount: 1, returnedCount: 1 });
  });

  it("de varios reportes del mismo documento cuenta el último", () => {
    const shown = applyOutboxOverlay(DETAIL, [
      entry("d-1", { status: "NOT_DELIVERED", createdAt: "2026-10-12T15:00:00.000Z" }),
      entry("d-1", { status: "REJECTED", createdAt: "2026-10-12T14:00:00.000Z" }),
    ]);

    expect(shown.stops[0].documents[0].status).toBe("NOT_DELIVERED");
  });

  it("una entrada FAILED no cambia el estado: se marca para que se vea que no llegó", () => {
    const shown = applyOutboxOverlay(DETAIL, [entry("d-2", { state: "FAILED" })]);

    expect(shown.stops[0].documents[1]).toMatchObject({ status: "PENDING", pendingSync: false, syncFailed: true });
    expect(shown.trip.pendingCount).toBe(3);
  });

  it("un documento cerrado por la oficina no se pisa", () => {
    const shown = applyOutboxOverlay(DETAIL, [entry("d-4", { status: "REJECTED" })]);

    expect(shown.stops[1].documents[1]).toMatchObject({ status: "DELIVERED", pendingSync: false });
  });

  it("ignora la cola de otros viajes", () => {
    const shown = applyOutboxOverlay(DETAIL, [entry("d-1", { tripId: "t-9" })]);

    expect(shown.stops[0].documents[0].status).toBe("PENDING");
  });

  it("cuenta lo que espera por viaje para la lista", () => {
    const counts = queuedByTrip([entry("d-1"), entry("d-2"), entry("d-3", { state: "FAILED" }), entry("d-9", { companyId: "c-2" })], "c-1");

    expect(counts.get("t-1")).toBe(2);
  });

  it("el siguiente pendiente sigue el orden de las paradas y salta el que se acaba de guardar", () => {
    const shown = applyOutboxOverlay(DETAIL, [entry("d-1")]);

    expect(nextPendingDocument(shown)?.documentId).toBe("d-2");
    expect(nextPendingDocument(shown, "d-2")?.documentId).toBe("d-3");
    expect(nextPendingDocument(shown, "d-3")?.documentId).toBe("d-2");
  });
});

describe("V70: el reflejo de las acciones de parada en cola", () => {
  const onTheRoad: PortalTripDetail = {
    ...DETAIL,
    trip: { ...DETAIL.trip, status: "IN_TRANSIT" },
    incidents: [],
    stops: DETAIL.stops.map((stop) => ({
      ...stop, executionStatus: "PENDING" as const, allowedExecutionTransitions: ["ARRIVED", "SKIPPED", "FAILED"] as const,
      actualArrivalAt: null, serviceStartedAt: null, actualDepartureAt: null,
      orders: [{ orderId: `o-${stop.stopId}`, orderNumber: `TO-${stop.stopId}`, customerName: null, assignedWeightKg: 10,
        assignedVolumeM3: 1, assignedPallets: 1, delivery: null }],
    })).map((stop) => ({ ...stop, allowedExecutionTransitions: [...stop.allowedExecutionTransitions] })),
  };

  function execution(id: string, createdAt: string, action: ExecutionOutboxEntry["action"],
    state: ExecutionOutboxEntry["state"] = "PENDING"): ExecutionOutboxEntry {
    return {
      id, userId: "u-1", companyId: "c-1", tripId: "t-1", kind: "EXECUTION", idempotencyKey: id, action, title: id,
      reportedAt: createdAt, createdAt, attempts: 0, lastError: null, state, nextAttemptAt: null, shipmentNumber: "SH-1",
    };
  }

  it("aplica llegada, entrega e incidencia en el orden en que se hicieron, y lo marca", () => {
    const shown = applyOutboxOverlay(onTheRoad, [
      execution("e-2", "2026-10-12T14:05:00.000Z", { type: "DELIVERY", stopId: "s-1", orderId: "o-s-1",
        input: { result: "PARTIAL", deliveredAt: "2026-10-12T14:05:00.000Z", notes: "1 roto", refusedPallets: 1,
          photos: [new Blob(["x"])], signature: null } }),
      execution("e-1", "2026-10-12T14:00:00.000Z", { type: "STOP", stopId: "s-1", transition: "arrive",
        occurredAt: "2026-10-12T14:00:00.000Z" }),
      execution("e-3", "2026-10-12T14:06:00.000Z", { type: "INCIDENT", tripStopId: "s-1", exceptionType: "OTHER",
        occurredAt: "2026-10-12T14:06:00.000Z", notes: "Film roto" }),
    ]);

    const stop = shown.stops[0];
    expect(stop).toMatchObject({ executionStatus: "ARRIVED", actualArrivalAt: "2026-10-12T14:00:00.000Z", pendingSync: true });
    expect(stop.allowedExecutionTransitions).toEqual(["IN_SERVICE", "COMPLETED", "FAILED"]);
    expect(stop.orders[0]).toMatchObject({ pendingSync: true, delivery: { result: "PARTIAL", source: "CARRIER_PORTAL",
      refusedPallets: 1, evidenceCount: 1 } });
    expect(shown.incidents).toEqual([expect.objectContaining({ exceptionType: "OTHER", stopSequence: 1, pendingSync: true })]);
    // La otra parada no se toca.
    expect(shown.stops[1]).toMatchObject({ executionStatus: "PENDING", pendingSync: false });
  });

  it("lo que el servidor rechazó no se aplica: se marca para que la persona lo vea", () => {
    const shown = applyOutboxOverlay(onTheRoad, [
      execution("e-1", "2026-10-12T14:00:00.000Z", { type: "STOP", stopId: "s-2", transition: "arrive",
        occurredAt: "2026-10-12T14:00:00.000Z" }, "FAILED"),
    ]);

    expect(shown.stops[1]).toMatchObject({ executionStatus: "PENDING", pendingSync: false, syncFailed: true });
  });

  it("un viaje cacheado por una versión anterior, sin datos de ejecución, se enseña igual", () => {
    const shown = applyOutboxOverlay(DETAIL, []);

    expect(shown.stops[0].orders).toEqual([]);
    expect(shown.incidents).toEqual([]);
  });
});
