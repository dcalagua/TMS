import { describe, expect, it } from "vitest";
import type { TransportEventView } from "../../shared/api/planningApi";
import { arrivalDistanceByStop, fmtDistance } from "./arrivalDistance";

/** La distancia de la llegada (V75), la misma en la tarjeta de la parada y en la historia del viaje. */

function arrival(overrides: Partial<TransportEventView>): TransportEventView {
  return {
    id: "e", tripId: "t-1", tripStopId: "s-1", stopSequence: 1, stopDestinationCode: null, stopDestinationName: null,
    eventType: "ARRIVED_AT_STOP", eventTime: "2026-11-05T15:25:00Z", recordedAt: "2026-11-05T15:25:00Z",
    source: "CARRIER_PORTAL", actorName: "chofer@trlit.example", notes: null, metadata: null, distanceToStopKm: null,
    ...overrides,
  };
}

describe("la distancia de la llegada", () => {
  it("se escribe en metros por debajo de un kilómetro y en kilómetros por encima", () => {
    expect(fmtDistance(0.1779)).toBe("180 m");
    expect(fmtDistance(0)).toBe("0 m");
    expect(fmtDistance(2.1127)).toMatch(/^2[,.]1 km$/);
  });

  it("se toma de la llegada de cada parada, y una parada sin ubicación no tiene ninguna", () => {
    const byStop = arrivalDistanceByStop([
      arrival({ id: "e-1", tripStopId: "s-1", distanceToStopKm: 0.18 }),
      arrival({ id: "e-2", tripStopId: "s-2", distanceToStopKm: null }),
      arrival({ id: "e-3", tripStopId: "s-3", eventType: "STOP_COMPLETED", distanceToStopKm: 5 }),
    ]);

    expect(byStop.get("s-1")).toBe(0.18);
    expect(byStop.has("s-2")).toBe(false);
    expect(byStop.has("s-3")).toBe(false);
  });
});
