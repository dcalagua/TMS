import { describe, expect, it } from "vitest";
import type { PortalDocument, PortalTripDetail } from "../../../shared/api/portalApi";
import { applyOutboxOverlay, nextPendingDocument, queuedByTrip } from "./overlay";
import type { OutboxEntry } from "./types";

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
