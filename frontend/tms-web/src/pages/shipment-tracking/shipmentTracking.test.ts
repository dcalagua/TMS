import { describe, expect, it } from "vitest";
import type { DocumentDeliveryRow } from "../../shared/api/documentDeliveriesApi";
import type { ShipmentTrackingRow, ShipmentTrackingStop } from "../../shared/api/shipmentTrackingApi";
import { countsFromStatuses, stopDocumentState } from "../../shared/maps/shipmentMarkers";
import {
  defaultShipmentFilters, groupDocumentsByStop, overviewPoints, percent, stopStateFromDocuments, toQueryParams,
} from "./shipmentTracking";

/**
 * Seguimiento de despachos (ADR-019 §2): la regla de color de las paradas, los filtros como
 * parámetros, los puntos del mapa y cómo cada documento cae en su parada.
 */

function stop(overrides: Partial<ShipmentTrackingStop> = {}): ShipmentTrackingStop {
  return {
    stopId: "st-1", sequence: 1, destinationName: "Bodega San Martín", address: "Av. Perú 123",
    latitude: -12.05, longitude: -77.04, executionStatus: "PENDING", documentCount: 2, pendingCount: 0,
    ...overrides,
  };
}

function row(overrides: Partial<ShipmentTrackingRow> = {}): ShipmentTrackingRow {
  return {
    tripId: "trip-1", shipmentNumber: "SH-1", tripStatus: "IN_TRANSIT", planningDate: "2026-10-12",
    carrierId: "car-1", carrierName: "Transportes Andinos", dispatchReference: "DSP-1", dispatchedAt: "2026-10-12T12:00:00Z",
    dispatchVerification: "MATCHED", vehicleLicensePlate: "ABC-123", driverName: "Juan Quispe",
    originName: "CD Lima", originLatitude: -12.1, originLongitude: -77.1, stopsTotal: 1, stopsResolved: 0,
    documentCount: 2, pendingCount: 0, deliveredCount: 2, rejectedCount: 0, notDeliveredCount: 0, returnedCount: 0,
    inconsistentCount: 0, lastReportAt: null, stops: [stop()],
    ...overrides,
  };
}

function doc(id: string, status: DocumentDeliveryRow["status"], orderNumbers: string[], tripId = "trip-1"): DocumentDeliveryRow {
  return {
    id, tripId, shipmentNumber: "SH-1", tripStatus: "IN_TRANSIT", dispatchedAt: null, carrierId: null, carrierName: null,
    vehicleLicensePlate: null, driverName: null, documentId: `doc-${id}`, documentType: "INVOICE", documentNumber: `F-${id}`,
    orderNumbers, customerName: null, status, reasonCode: null, remark: null, reportedAt: null, recordedAt: null,
    recordedByName: null, returnedAt: null, returnedByName: null, evidenceCount: 0, inconsistency: null,
  };
}

describe("la regla de color de una parada", () => {
  it("sin documentos es gris", () => {
    expect(stopDocumentState({ documentCount: 0, pendingCount: 0 })).toBe("NONE");
  });

  it("todos entregados es verde", () => {
    expect(stopDocumentState({ documentCount: 3, pendingCount: 0, deliveredCount: 3 })).toBe("DELIVERED");
    expect(stopDocumentState({ documentCount: 3, pendingCount: 0, rejectedCount: 0, notDeliveredCount: 0 })).toBe("DELIVERED");
  });

  it("con alguno pendiente es ámbar", () => {
    expect(stopDocumentState({ documentCount: 3, pendingCount: 1, rejectedCount: 0 })).toBe("PENDING");
  });

  it("un rechazo o una no entrega es rojo, aunque quede alguno pendiente", () => {
    expect(stopDocumentState({ documentCount: 3, pendingCount: 1, rejectedCount: 1 })).toBe("PROBLEM");
    expect(stopDocumentState({ documentCount: 3, pendingCount: 0, notDeliveredCount: 1 })).toBe("PROBLEM");
    // Solo con entregados: lo que no está pendiente ni entregado es un problema.
    expect(stopDocumentState({ documentCount: 3, pendingCount: 0, deliveredCount: 2 })).toBe("PROBLEM");
  });

  it("sin desglose por parada, un envío con problemas pinta en rojo la parada ya reportada, nunca en verde", () => {
    expect(stopDocumentState({ documentCount: 2, pendingCount: 0 }, { tripHasProblems: false })).toBe("DELIVERED");
    expect(stopDocumentState({ documentCount: 2, pendingCount: 0 }, { tripHasProblems: true })).toBe("PROBLEM");
    // Si en la parada no se reportó nada, no puede ser la del problema.
    expect(stopDocumentState({ documentCount: 2, pendingCount: 2 }, { tripHasProblems: true })).toBe("PENDING");
  });

  it("con los documentos uno a uno el color es exacto", () => {
    expect(countsFromStatuses(["DELIVERED", "PENDING", "REJECTED", "NOT_DELIVERED"]))
      .toEqual({ documentCount: 4, pendingCount: 1, deliveredCount: 1, rejectedCount: 1, notDeliveredCount: 1 });
    const twoDocs = stop({ documentCount: 2, pendingCount: 0 });
    expect(stopStateFromDocuments(twoDocs, [doc("a", "DELIVERED", []), doc("b", "DELIVERED", [])], true)).toBe("DELIVERED");
    expect(stopStateFromDocuments(twoDocs, [doc("a", "DELIVERED", []), doc("b", "REJECTED", [])], false)).toBe("PROBLEM");
    // Con una parte de los documentos se vuelve a los contadores.
    expect(stopStateFromDocuments(twoDocs, [doc("a", "DELIVERED", [])], true)).toBe("PROBLEM");
  });
});

describe("los filtros", () => {
  it("por defecto, hoy a hoy y sin más filtros", () => {
    const defaults = defaultShipmentFilters();
    expect(defaults.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(defaults.dateTo).toBe(defaults.dateFrom);
    expect(toQueryParams(defaults)).toEqual({
      dateFrom: defaults.dateFrom, dateTo: defaults.dateTo, carrierId: undefined, status: undefined, documents: undefined, q: undefined,
    });
  });

  it("pasa cada filtro con su nombre de parámetro y quita los vacíos", () => {
    expect(toQueryParams({
      q: "  SH-9 ", dateFrom: "2026-10-01", dateTo: "2026-10-12", carrierId: "car-1", status: "IN_TRANSIT", documents: "PENDING",
    })).toEqual({ dateFrom: "2026-10-01", dateTo: "2026-10-12", carrierId: "car-1", status: "IN_TRANSIT", documents: "PENDING", q: "SH-9" });
  });

  it("el porcentaje de nada es null, no 0", () => {
    expect(percent(0, 0)).toBeNull();
    expect(percent(1, 4)).toBe(25);
    expect(percent(9, 4)).toBe(100);
  });
});

describe("los puntos del mapa de conjunto", () => {
  it("un origen por coordenada y una parada por parada, con su color", () => {
    const rows = [
      row(),
      row({ tripId: "trip-2", shipmentNumber: "SH-2", rejectedCount: 1, deliveredCount: 1,
        stops: [stop({ stopId: "st-2", pendingCount: 0 }), stop({ stopId: "st-3", sequence: 2, latitude: null, longitude: null, documentCount: 0 })] }),
    ];
    const points = overviewPoints(rows);
    const origins = points.filter((p) => p.kind === "origin");
    expect(origins).toHaveLength(1);
    // Compartido por dos envíos: no selecciona a ninguno.
    expect(origins[0].groupId).toBeNull();
    const stops = points.filter((p) => p.kind === "stop");
    expect(stops.map((p) => [p.id, p.state])).toEqual([
      ["trip-1:st-1", "DELIVERED"],
      ["trip-2:st-2", "PROBLEM"],
      ["trip-2:st-3", "NONE"],
    ]);
    expect(stops[2].latitude).toBeNull();
  });
});

describe("cada documento en su parada", () => {
  const stops = [stop({ stopId: "st-1", sequence: 1 }), stop({ stopId: "st-2", sequence: 2 })];
  const detail = {
    assignments: [
      { orderNumber: "TO-1", destinationId: "dest-1" },
      { orderNumber: "TO-2", destinationId: "dest-2" },
    ],
    stops: [{ id: "st-1", destinationId: "dest-1" }, { id: "st-2", destinationId: "dest-2" }],
  };

  it("sigue documento → pedido → destino → parada, y lo que no casa queda aparte", () => {
    const grouped = groupDocumentsByStop(stops, [
      doc("a", "DELIVERED", ["TO-2"]),
      doc("b", "PENDING", ["TO-1", "TO-2"]),
      doc("c", "PENDING", ["TO-9"]),
    ], detail);
    expect(grouped.stops.map((s) => s.documents.map((d) => d.id))).toEqual([["b"], ["a"]]);
    expect(grouped.unassigned.map((d) => d.id)).toEqual(["c"]);
  });

  it("sin detalle del viaje, todos quedan como documentos del envío", () => {
    const grouped = groupDocumentsByStop(stops, [doc("a", "DELIVERED", ["TO-1"])], null);
    expect(grouped.stops.every((s) => s.documents.length === 0)).toBe(true);
    expect(grouped.unassigned).toHaveLength(1);
  });
});
