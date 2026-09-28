import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TRANSPORT_EVENT_TYPES, type TransportEventView } from "../../shared/api/planningApi";
import { enumLabel } from "../../lib/enums";
import { TripTimeline } from "./TripTimeline";

/**
 * La línea de tiempo del viaje y los hitos del almacén (ADR-013 §13.10).
 *
 * Cada tipo que el backend puede escribir tiene etiqueta en español, y un hito escrito por una
 * integración se distingue a simple vista de uno que tecleó una persona.
 */

function event(overrides: Partial<TransportEventView> = {}): TransportEventView {
  return {
    id: "e-1",
    tripId: "t-1",
    tripStopId: null,
    stopSequence: null,
    stopDestinationCode: null,
    stopDestinationName: null,
    eventType: "WAREHOUSE_LOAD_READY",
    eventTime: "2026-09-28T12:00:00Z",
    recordedAt: "2026-09-28T12:00:00Z",
    source: "INTEGRATION",
    actorName: "EWM_EBIM",
    notes: null,
    metadata: null,
    ...overrides,
  };
}

describe("la línea de tiempo", () => {
  it("tiene una etiqueta para cada tipo de evento conocido", () => {
    for (const type of TRANSPORT_EVENT_TYPES) {
      expect(enumLabel("transportEventType", type)).not.toBe(type);
    }
  });

  it("nombra los hitos del almacén y marca que los escribió una integración", () => {
    render(<TripTimeline events={[
      event(),
      event({ id: "e-2", eventType: "WAREHOUSE_DISPATCH_CONFIRMED" }),
    ]} />);

    expect(screen.getByText("Carga lista en el almacén")).toBeInTheDocument();
    expect(screen.getByText("Despacho confirmado por el almacén")).toBeInTheDocument();
    expect(screen.getAllByText("Integración")).toHaveLength(2);
  });

  it("no marca como integración lo que registró un operador", () => {
    render(<TripTimeline events={[event({ eventType: "TRIP_DISPATCHED", source: "OPERATOR", actorName: "ana@ebim.pe" })]} />);

    expect(screen.getByText("Salida")).toBeInTheDocument();
    expect(screen.queryByText("Integración")).not.toBeInTheDocument();
  });
});
